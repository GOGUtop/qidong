package io.github.stlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Dark, user-configurable Android wrapper for one SillyTavern site. */
public final class MainActivity extends Activity {
    static final String PREFS = "launcher_settings";
    static final String KEY_URL = "bound_url";
    static final String ACTION_CONFIRM_UNBIND = "io.github.stlauncher.action.CONFIRM_UNBIND";
    static final String ACTION_OPEN_SETTINGS = "io.github.stlauncher.action.OPEN_SETTINGS";

    private static final int FILE_REQUEST = 1201;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 1202;

    // Gaming-inspired navy / charcoal surface colors, with restrained yellow accents.
    private static final int BACKGROUND = Color.rgb(8, 13, 25);
    private static final int PANEL = Color.rgb(23, 30, 45);
    private static final int PANEL_LIGHT = Color.rgb(31, 39, 56);
    private static final int OUTLINE = Color.rgb(48, 57, 75);
    private static final int ACCENT = Color.rgb(255, 212, 59);
    private static final int MUTED = Color.rgb(146, 156, 175);
    private static final int WHITE = Color.rgb(241, 244, 249);

    private FrameLayout host;
    private LinearLayout root;
    private ScrollView settingsOverlay;
    private LinearLayout webHeader;
    private BlockableWebView webView;
    private BlobExportManager blobExportManager;
    private ValueCallback<Uri[]> fileCallback;
    private ProgressBar progressBar;
    private String boundUrl;
    private final Map<String, String> scriptCache = new HashMap<>();
    private boolean isVisible;
    private long lastAlertTime;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

        host = new FrameLayout(this);
        host.setBackgroundColor(BACKGROUND);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        host.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(host);
        configureInsets();

        boundUrl = LauncherOptions.prefs(this).getString(KEY_URL, null);
        if (boundUrl == null || boundUrl.isEmpty()) {
            showBindingScreen();
        } else {
            showWebScreen();
            NotificationHelper.show(this);
            requestNotificationPermissionOnce();
            if (LauncherOptions.enabled(this, LauncherOptions.KEEP_ALIVE)) startSessionService();
        }
        handleIntentAction(getIntent());
    }

    private void configureInsets() {
        // Android 15+ enforces edge-to-edge. Insets belong to the container, never
        // to the WebView itself, so changing fullscreen does not reload the page.
        if (Build.VERSION.SDK_INT >= 35) {
            host.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                boolean full = fullscreenWeb();
                host.setPadding(bars.left, full ? 0 : bars.top, bars.right,
                        Math.max(full ? 0 : bars.bottom, keyboard.bottom));
                return insets;
            });
            host.requestApplyInsets();
        }
    }

    private boolean fullscreenWeb() {
        return boundUrl != null && settingsOverlay == null
                && LauncherOptions.enabled(this, LauncherOptions.FULL_SCREEN);
    }

    private void applyFullscreen() {
        boolean full = fullscreenWeb();
        if (webHeader != null) webHeader.setVisibility(full ? View.GONE : View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getDecorView().getWindowInsetsController();
            if (controller != null) {
                if (full) {
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                } else {
                    controller.show(WindowInsets.Type.systemBars());
                }
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(full
                    ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                      | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                      | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    : 0);
        }
        if (Build.VERSION.SDK_INT >= 35) host.requestApplyInsets();
    }

    private void showBindingScreen() {
        removeSettingsOverlay();
        disposeWebView();
        webHeader = null;
        root.removeAllViews();
        applyFullscreen();

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout column = vertical();
        column.setPadding(dp(26), dp(44), dp(26), dp(38));
        scroll.addView(column, new ScrollView.LayoutParams(-1, -2));

        TextView overline = text("ST / MOBILE LAUNCHER", 12, ACCENT, true);
        overline.setLetterSpacing(.16f);
        column.addView(overline);
        TextView icon = text("ST", 25, ACCENT, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(rounded(PANEL_LIGHT, 23, OUTLINE));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        iconLp.topMargin = dp(36);
        column.addView(icon, iconLp);
        TextView heading = text("你的酒馆，\n即刻开启。", 31, WHITE, true);
        heading.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(-1, -2);
        headingLp.topMargin = dp(24);
        column.addView(heading, headingLp);
        TextView subtitle = text("绑定一次，之后打开 App 直接进入 SillyTavern。", 14, MUTED, false);
        subtitle.setPadding(0, dp(13), 0, 0);
        column.addView(subtitle);

        LinearLayout card = card(column, 35);
        card.addView(text("CONNECT YOUR TAVERN", 11, ACCENT, true));
        TextView label = text("酒馆服务器地址", 19, WHITE, true);
        label.setPadding(0, dp(12), 0, dp(5));
        card.addView(label);
        card.addView(text("支持 HTTP / HTTPS；省略协议时默认 HTTP", 12, MUTED, false));

        EditText field = new EditText(this);
        field.setTextSize(14);
        field.setTextColor(WHITE);
        field.setHintTextColor(MUTED);
        field.setHint("http://你的酒馆域名:8000");
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setImeOptions(EditorInfo.IME_ACTION_GO);
        field.setSelectAllOnFocus(true);
        field.setPadding(dp(14), 0, dp(14), 0);
        field.setBackground(rounded(BACKGROUND, 13, OUTLINE));
        LinearLayout.LayoutParams fieldLp = new LinearLayout.LayoutParams(-1, dp(52));
        fieldLp.topMargin = dp(20);
        card.addView(field, fieldLp);

        Button bindButton = yellowButton("绑定并进入酒馆  →");
        LinearLayout.LayoutParams bindLp = new LinearLayout.LayoutParams(-1, dp(52));
        bindLp.topMargin = dp(18);
        card.addView(bindButton, bindLp);
        View.OnClickListener binder = v -> {
            try {
                String parsed = BoundUrl.normalize(field.getText().toString());
                LauncherOptions.prefs(this).edit().putString(KEY_URL, parsed).apply();
                boundUrl = parsed;
                ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(field.getWindowToken(), 0);
                showWebScreen();
                NotificationHelper.show(this);
                requestNotificationPermissionOnce();
                if (LauncherOptions.enabled(this, LauncherOptions.KEEP_ALIVE)) startSessionService();
            } catch (IllegalArgumentException error) {
                field.setError(error.getMessage());
            }
        };
        bindButton.setOnClickListener(binder);
        field.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                binder.onClick(field);
                return true;
            }
            return false;
        });
        TextView note = text("提示：酒馆服务端需要已启动且手机可以访问。" +
                "HTTP 地址请仅在可信局域网或 VPN 内使用。", 12, MUTED, false);
        note.setLineSpacing(dp(5), 1f);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = dp(22);
        column.addView(note, noteLp);
    }

    private void showWebScreen() {
        removeSettingsOverlay();
        disposeWebView();
        root.removeAllViews();
        if (boundUrl == null) { showBindingScreen(); return; }

        webHeader = new LinearLayout(this);
        webHeader.setGravity(Gravity.CENTER_VERTICAL);
        webHeader.setPadding(dp(15), dp(5), dp(7), dp(5));
        webHeader.setBackgroundColor(BACKGROUND);
        root.addView(webHeader, new LinearLayout.LayoutParams(-1, dp(56)));
        TextView mark = text("ST", 15, ACCENT, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(PANEL, 11, OUTLINE));
        webHeader.addView(mark, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout names = vertical();
        names.setPadding(dp(10), 0, 0, 0);
        webHeader.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
        names.addView(text("SillyTavern", 14, WHITE, true));
        TextView domain = text(BoundUrl.origin(boundUrl), 11, MUTED, false);
        domain.setSingleLine();
        domain.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        names.addView(domain);
        TextView refresh = toolbarButton("↻");
        refresh.setTextSize(22);
        webHeader.addView(refresh, new LinearLayout.LayoutParams(dp(38), dp(40)));
        refresh.setOnClickListener(v -> { if (webView != null) webView.reload(); });
        TextView menu = toolbarButton("⋮");
        menu.setTextSize(25);
        webHeader.addView(menu, new LinearLayout.LayoutParams(dp(36), dp(40)));
        menu.setOnClickListener(v -> showMenu(menu));

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        progressBar.setProgress(0);
        root.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(2)));

        webView = new BlockableWebView(this);
        webView.setBlockNativeSelection(LauncherOptions.enabled(this, LauncherOptions.BLOCK_SELECTION));
        webView.setBackgroundColor(BACKGROUND);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));

        WebSettings opts = webView.getSettings();
        opts.setJavaScriptEnabled(true);
        opts.setDomStorageEnabled(true);
        opts.setDatabaseEnabled(true);
        opts.setMediaPlaybackRequiresUserGesture(false);
        opts.setJavaScriptCanOpenWindowsAutomatically(false);
        opts.setSupportMultipleWindows(false);
        opts.setAllowFileAccess(false);
        opts.setAllowContentAccess(true);
        opts.setAllowFileAccessFromFileURLs(false);
        opts.setAllowUniversalAccessFromFileURLs(false);
        opts.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        String allowedOrigin = BoundUrl.origin(boundUrl);
        blobExportManager = new BlobExportManager(this, allowedOrigin);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "STNativeExport",
                    Collections.singleton(allowedOrigin),
                    (view, message, sourceOrigin, isMainFrame, reply) -> {
                        if (blobExportManager != null) {
                            blobExportManager.onMessage(message.getData(), sourceOrigin, isMainFrame, reply);
                        }
                    });
            WebViewCompat.addWebMessageListener(webView, "STNativeEvents",
                    Collections.singleton(allowedOrigin),
                    (view, message, sourceOrigin, isMainFrame, reply) -> {
                        if (!isMainFrame || !BoundUrl.sameOrigin(boundUrl, sourceOrigin.toString())) return;
                        String raw = message.getData();
                        if (raw == null || raw.length() > 256) return;
                        try {
                            String type = new JSONObject(raw).optString("type", "");
                            if ("reply_finished".equals(type)) runOnUiThread(() -> {
                                long now = SystemClock.elapsedRealtime();
                                if (now - lastAlertTime > 1300) {
                                    lastAlertTime = now;
                                    ReplyNotifier.notifyCompleted(this, isVisible);
                                }
                            });
                        } catch (Exception ignored) {}
                    });
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int progress) {
                if (progressBar != null) {
                    progressBar.setProgress(progress);
                    progressBar.setVisibility(progress == 100 ? View.GONE : View.VISIBLE);
                }
            }

            @Override public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent choose = params.createIntent();
                    choose.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivityForResult(choose, FILE_REQUEST);
                } catch (ActivityNotFoundException | SecurityException failure) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    toast("系统没有可用的文件选择器");
                }
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                        && BoundUrl.sameOrigin(boundUrl, uri.toString())) return false;
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)
                        || "mailto".equalsIgnoreCase(scheme) || "tel".equalsIgnoreCase(scheme)) {
                    openExternal(uri);
                } else toast("已阻止不支持的外部链接");
                return true;
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (boundUrl != null && BoundUrl.sameOrigin(boundUrl, url)
                        && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    injectScript(view, "blob_export.js");
                    injectScript(view, "reply_events.js");
                }
            }

            @Override public void onReceivedError(WebView view,
                    WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) toast("酒馆加载失败，请检查服务地址或网络");
            }

            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (view == webView) {
                    if (blobExportManager != null) blobExportManager.close();
                    ViewGroup parent = (ViewGroup) view.getParent();
                    if (parent != null) parent.removeView(view);
                    view.destroy();
                    webView = null;
                    root.post(() -> { toast("网页进程已重启"); showWebScreen(); });
                }
                return true;
            }
        });
        webView.setDownloadListener((url, userAgent, disposition, mime, size) -> {
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    webView.evaluateJavascript("(function(){var a=document.createElement('a');" +
                            "a.href=" + JSONObject.quote(url) + ";a.download='';a.click();})()", null);
                } else toast("请更新 Android System WebView 以支持文件导出");
            } else DownloadHandler.download(this, url, userAgent, disposition, mime);
        });
        webView.loadUrl(boundUrl);
        applyFullscreen();
    }

    private void injectScript(WebView view, String name) {
        try {
            String content = scriptCache.get(name);
            if (content == null) {
                try (InputStream stream = getAssets().open(name);
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[4096];
                    int n;
                    while ((n = stream.read(chunk)) != -1) output.write(chunk, 0, n);
                    content = output.toString(StandardCharsets.UTF_8.name());
                    scriptCache.put(name, content);
                }
            }
            view.evaluateJavascript(content, null);
        } catch (Exception failure) {
            toast("网页脚本加载失败：" + name);
        }
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "软件设置");
        popup.getMenu().add(0, 2, 1, "注销绑定 / 切换酒馆");
        popup.getMenu().add(0, 3, 2, "在浏览器打开");
        popup.getMenu().add(0, 4, 3, "退出应用");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: showSettingsScreen(); return true;
                case 2: confirmUnbind(); return true;
                case 3: if (boundUrl != null) openExternal(Uri.parse(boundUrl)); return true;
                case 4: finish(); return true;
                default: return false;
            }
        });
        popup.show();
    }

    /** Draws a settings overlay on top of the existing WebView without destroying it. */
    private void showSettingsScreen() {
        if (settingsOverlay != null || boundUrl == null) return;
        settingsOverlay = new ScrollView(this);
        settingsOverlay.setBackgroundColor(BACKGROUND);
        settingsOverlay.setFillViewport(true);
        settingsOverlay.setClipToPadding(false);
        settingsOverlay.setVerticalScrollBarEnabled(false);
        host.addView(settingsOverlay, new FrameLayout.LayoutParams(-1, -1));
        applyFullscreen(); // while settings are open Android system bars are accessible
        LinearLayout content = vertical();
        content.setPadding(dp(20), dp(20), dp(20), dp(34));
        settingsOverlay.addView(content, new ScrollView.LayoutParams(-1, -2));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(top, new LinearLayout.LayoutParams(-1, dp(44)));
        TextView back = toolbarButton("‹");
        back.setTextSize(34);
        back.setBackground(rounded(PANEL, 12, OUTLINE));
        top.addView(back, new LinearLayout.LayoutParams(dp(45), dp(43)));
        back.setOnClickListener(v -> closeSettings());
        TextView nav = text("SETTINGS / 控制中心", 12, MUTED, true);
        nav.setLetterSpacing(.10f);
        nav.setPadding(dp(14), 0, 0, 0);
        top.addView(nav);

        TextView kicker = text("SILLYTAVERN  /  LAUNCHER", 11, ACCENT, true);
        kicker.setLetterSpacing(.13f);
        LinearLayout.LayoutParams kickerLp = new LinearLayout.LayoutParams(-1, -2);
        kickerLp.topMargin = dp(27);
        content.addView(kicker, kickerLp);
        TextView mainTitle = text("酒馆控制中心", 29, WHITE, true);
        LinearLayout.LayoutParams mainLp = new LinearLayout.LayoutParams(-1, -2);
        mainLp.topMargin = dp(9);
        content.addView(mainTitle, mainLp);
        TextView subtitle = text("按照你的使用习惯，自由配置酒馆体验。", 13, MUTED, false);
        subtitle.setPadding(0, dp(7), 0, 0);
        content.addView(subtitle);

        LinearLayout boundCard = card(content, 24);
        boundCard.addView(text("CURRENT SESSION", 10, ACCENT, true));
        TextView boundTitle = text("●  已连接酒馆", 16, WHITE, true);
        boundTitle.setPadding(0, dp(10), 0, dp(7));
        boundCard.addView(boundTitle);
        TextView address = text(boundUrl, 12, MUTED, false);
        address.setMaxLines(2);
        address.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        boundCard.addView(address);
        if (boundUrl.regionMatches(true, 0, "https://", 0, 8)) {
            TextView useHttp = outlinedButton("当前网站只支持 HTTP？点击切换  →");
            LinearLayout.LayoutParams useHttpLp = new LinearLayout.LayoutParams(-1, dp(46));
            useHttpLp.topMargin = dp(12);
            boundCard.addView(useHttp, useHttpLp);
            useHttp.setOnClickListener(v -> confirmSwitchToHttp());
        }

        section(content, "DISPLAY", "显示与沉浸", 20);
        LinearLayout display = card(content, 10);
        toggleRow(display, "全屏沉浸模式", "隐藏系统状态栏、导航栏与顶部工具栏，滑动顶部可临时呼出系统栏。",
                LauncherOptions.FULL_SCREEN, () -> applyFullscreen());

        section(content, "BACKGROUND", "后台与运行", 22);
        LinearLayout keepCard = card(content, 10);
        toggleRow(keepCard, "增强后台保活", "用户开启前台服务并保留状态通知；降低被回收概率，但不能保证一直存活。",
                LauncherOptions.KEEP_ALIVE, () -> {
                    if (LauncherOptions.enabled(this, LauncherOptions.KEEP_ALIVE)) startSessionService();
                    else stopSessionService();
                    NotificationHelper.show(this);
                });
        TextView battery = outlinedButton("打开系统电池优化设置  ↗");
        LinearLayout.LayoutParams batteryLp = new LinearLayout.LayoutParams(-1, dp(46));
        batteryLp.topMargin = dp(12);
        keepCard.addView(battery, batteryLp);
        battery.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception e) { openAppInfo(); }
        });
        TextView keepNote = text("说明：系统省电、厂商后台限制和 WebView 冻结仍可能中断生成。" +
                "仅在需要时开启，以降低耗电。", 11, MUTED, false);
        keepNote.setPadding(0, dp(12), 0, 0);
        keepNote.setLineSpacing(dp(3), 1f);
        keepCard.addView(keepNote);

        section(content, "NOTIFICATIONS", "回复完成提醒", 22);
        LinearLayout alerts = card(content, 10);
        toggleRow(alerts, "开启回复完成提醒", "监听 SillyTavern 的生成与消息事件；正常生成完成时提醒。",
                LauncherOptions.REPLY_ALERTS, () -> {});
        separator(alerts);
        toggleRow(alerts, "播放提示音", "回复结束时播放系统默认通知铃声。",
                LauncherOptions.SOUND, () -> {});
        separator(alerts);
        toggleRow(alerts, "震动提醒", "回复结束时震动一次；后台通知受系统权限限制。",
                LauncherOptions.VIBRATE, () -> {});
        TextView notificationAccess = outlinedButton("系统通知权限与提醒渠道  ↗");
        LinearLayout.LayoutParams accessLp = new LinearLayout.LayoutParams(-1, dp(46));
        accessLp.topMargin = dp(12);
        alerts.addView(notificationAccess, accessLp);
        notificationAccess.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())); }
            catch (Exception ignored) { openAppInfo(); }
        });

        section(content, "INTERACTION", "输入与交互", 22);
        LinearLayout interaction = card(content, 10);
        toggleRow(interaction, "禁用安卓原生文本菜单", "阻止网页文字选择后弹出的复制 / 粘贴 / 全选工具栏；可能影响输入框内的复制粘贴。",
                LauncherOptions.BLOCK_SELECTION, () -> {
                    if (webView != null) {
                        webView.setBlockNativeSelection(LauncherOptions.enabled(this,
                                LauncherOptions.BLOCK_SELECTION));
                    }
                });

        section(content, "STORAGE", "缓存管理", 22);
        LinearLayout storage = card(content, 10);
        toggleRow(storage, "退出时自动清除网页缓存", "通过 App 的“退出应用”结束时清理缓存；保留 Cookie、登录状态及本地网站数据。",
                LauncherOptions.CLEAR_ON_EXIT, () -> {});
        TextView clear = outlinedButton("立即清理网页缓存  →");
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(-1, dp(46));
        clearLp.topMargin = dp(12);
        storage.addView(clear, clearLp);
        clear.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("清除网页缓存？")
                .setMessage("清理 WebView 的网页资源缓存，不清除酒馆绑定、Cookie、登录状态和服务端数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认清理", (d, w) -> {
                    if (webView != null) webView.clearCache(true);
                    else {
                        WebView temporary = new WebView(this);
                        temporary.clearCache(true);
                        temporary.destroy();
                    }
                    toast("网页资源缓存已清理");
                }).show());

        section(content, "ACCOUNT", "酒馆绑定", 22);
        TextView unbind = outlinedButton("注销当前绑定 / 更换酒馆  ↗");
        LinearLayout.LayoutParams unbindLp = new LinearLayout.LayoutParams(-1, dp(52));
        unbindLp.topMargin = dp(12);
        content.addView(unbind, unbindLp);
        unbind.setOnClickListener(v -> confirmUnbind());
        TextView foot = text("LAUNCHER  v0.2.1   •   非官方 SillyTavern 客户端", 11, MUTED, false);
        foot.setGravity(Gravity.CENTER);
        foot.setPadding(0, dp(32), 0, dp(8));
        content.addView(foot);
    }

    private void closeSettings() {
        removeSettingsOverlay();
        applyFullscreen();
    }

    private void removeSettingsOverlay() {
        if (settingsOverlay == null) return;
        host.removeView(settingsOverlay);
        settingsOverlay = null;
    }

    private void section(LinearLayout parent, String overline, String label, int marginTop) {
        LinearLayout block = vertical();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(marginTop);
        parent.addView(block, lp);
        TextView english = text(overline, 10, ACCENT, true);
        english.setLetterSpacing(.12f);
        block.addView(english);
        TextView heading = text(label, 17, WHITE, true);
        heading.setPadding(0, dp(6), 0, 0);
        block.addView(heading);
    }

    private void toggleRow(LinearLayout parent, String title, String detail,
                           String key, Runnable updated) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setPadding(0, dp(3), 0, dp(3));
        parent.addView(line, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout labels = vertical();
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, -2, 1);
        textLp.rightMargin = dp(9);
        line.addView(labels, textLp);
        labels.addView(text(title, 15, WHITE, true));
        TextView description = text(detail, 12, MUTED, false);
        description.setPadding(0, dp(5), 0, 0);
        description.setLineSpacing(dp(2), 1f);
        labels.addView(description);
        Switch control = new Switch(this);
        control.setShowText(false);
        control.setChecked(LauncherOptions.enabled(this, key));
        control.setThumbTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{ACCENT, MUTED}));
        control.setTrackTintList(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{Color.rgb(114, 92, 35), Color.rgb(69, 79, 100)}));
        line.addView(control, new LinearLayout.LayoutParams(-2, dp(52)));
        control.setOnCheckedChangeListener((button, checked) -> {
            LauncherOptions.set(this, key, checked);
            updated.run();
        });
        line.setOnClickListener(v -> control.setChecked(!control.isChecked()));
    }

    private void separator(LinearLayout parent) {
        View divider = new View(this);
        divider.setBackgroundColor(OUTLINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(1));
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(14);
        parent.addView(divider, lp);
    }

    private LinearLayout card(LinearLayout parent, int marginTop) {
        LinearLayout card = vertical();
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(PANEL, 19, OUTLINE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(marginTop);
        parent.addView(card, lp);
        return card;
    }

    private LinearLayout vertical() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private TextView outlinedButton(String text) {
        TextView button = text(text, 13, ACCENT, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(PANEL_LIGHT, 12, OUTLINE));
        button.setClickable(true);
        return button;
    }

    private Button yellowButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(15);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(BACKGROUND);
        button.setBackground(rounded(ACCENT, 12, 0));
        return button;
    }

    private TextView toolbarButton(String label) {
        TextView button = text(label, 13, WHITE, false);
        button.setGravity(Gravity.CENTER);
        button.setClickable(true);
        return button;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(int color, int radius, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void startSessionService() {
        if (boundUrl == null) return;
        try {
            Intent intent = new Intent(this, ForegroundSessionService.class)
                    .setAction(ForegroundSessionService.ACTION_START);
            startForegroundService(intent);
        } catch (RuntimeException failure) {
            LauncherOptions.set(this, LauncherOptions.KEEP_ALIVE, false);
            toast("系统不允许启动后台增强服务：" + failure.getClass().getSimpleName());
            NotificationHelper.show(this);
        }
    }

    private void stopSessionService() {
        stopService(new Intent(this, ForegroundSessionService.class));
        if (boundUrl != null) NotificationHelper.show(this);
    }

    private void openExternal(Uri uri) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
        catch (Exception error) { toast("无法打开外部应用"); }
    }

    private void openAppInfo() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null)));
        } catch (Exception ignored) {}
    }

    private void requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33 || NotificationHelper.permitted(this)) return;
        SharedPreferences prefs = LauncherOptions.prefs(this);
        if (!prefs.getBoolean("notification_permission_requested", false)) {
            prefs.edit().putBoolean("notification_permission_requested", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,
            String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            NotificationHelper.show(this);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_REQUEST || fileCallback == null) return;
        Uri[] values = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        if (values != null) {
            for (Uri value : values) {
                if (value == null || !"content".equalsIgnoreCase(value.getScheme())) {
                    values = null;
                    toast("仅支持通过系统文件选择器导入文件");
                    break;
                }
            }
        }
        fileCallback.onReceiveValue(values);
        fileCallback = null;
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntentAction(intent);
    }

    private void handleIntentAction(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        intent.setAction(null);
        if (ACTION_CONFIRM_UNBIND.equals(action)) {
            new Handler(Looper.getMainLooper()).post(this::confirmUnbind);
        } else if (ACTION_OPEN_SETTINGS.equals(action)) {
            new Handler(Looper.getMainLooper()).post(this::showSettingsScreen);
        }
    }

    /** Explicitly switch old HTTPS bindings to HTTP. Never downgrade silently. */
    private void confirmSwitchToHttp() {
        if (boundUrl == null || !boundUrl.regionMatches(true, 0, "https://", 0, 8)) return;
        final String newUrl = BoundUrl.normalize("http://" + boundUrl.substring(8));
        new AlertDialog.Builder(this)
                .setTitle("使用 HTTP 打开酒馆？")
                .setMessage("将地址改为：\n" + newUrl +
                        "\n\nHTTP 连接不加密，仅在可信的网络或 VPN 中使用。")
                .setNegativeButton("取消", null)
                .setPositiveButton("切换为 HTTP", (dialog, which) -> {
                    boundUrl = newUrl;
                    LauncherOptions.prefs(this).edit().putString(KEY_URL, newUrl).apply();
                    showWebScreen();
                    NotificationHelper.show(this);
                }).show();
    }

    private void confirmUnbind() {
        if (boundUrl == null) return;
        new AlertDialog.Builder(this)
                .setTitle("注销当前酒馆绑定？")
                .setMessage("只清除启动器保存的酒馆网址。不会删除服务端角色、聊天记录、Cookie 或已下载的文件。")
                .setNegativeButton("取消", null)
                .setPositiveButton("注销绑定", (dialog, which) -> {
                    LauncherOptions.prefs(this).edit().remove(KEY_URL).apply();
                    boundUrl = null;
                    stopSessionService();
                    NotificationHelper.cancel(this);
                    showBindingScreen();
                }).show();
    }

    private void disposeWebView() {
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        if (blobExportManager != null) { blobExportManager.close(); blobExportManager = null; }
        if (webView != null) {
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            if (webView.getParent() instanceof ViewGroup) {
                ((ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
    }

    @Override protected void onResume() {
        super.onResume();
        isVisible = true;
        if (webView != null) webView.onResume();
        if (boundUrl != null) NotificationHelper.show(this);
        applyFullscreen();
    }

    @Override protected void onPause() {
        isVisible = false;
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (isFinishing()) {
            if (LauncherOptions.enabled(this, LauncherOptions.CLEAR_ON_EXIT) && webView != null)
                webView.clearCache(true);
            stopSessionService();
        }
        disposeWebView();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (settingsOverlay != null) {
            closeSettings();
        } else if (fullscreenWeb() && webView != null && !webView.canGoBack()) {
            showSettingsScreen(); // Emergency entry if notification permission is denied.
        } else if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else if (webView != null) {
            moveTaskToBack(true); // keep open sessions available when backgrounding
        } else {
            super.onBackPressed();
        }
    }

    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
}
