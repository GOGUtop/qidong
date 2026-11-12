package io.github.stlauncher;

import android.Manifest;
import android.app.DownloadManager;
import android.view.DisplayCutout;
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
    private static final int BACKGROUND = Color.rgb(7, 12, 23);
    private static final int PANEL = Color.rgb(19, 28, 42);
    private static final int PANEL_LIGHT = Color.rgb(29, 39, 58);
    private static final int OUTLINE = Color.rgb(42, 54, 73);
    private static final int ACCENT = Color.rgb(255, 212, 59);
    private static final int MUTED = Color.rgb(149, 162, 181);
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
        if (Build.VERSION.SDK_INT >= 30) {
            // Own the insets on Android 11+, including Android 15 mandatory edge-to-edge.
            getWindow().setDecorFitsSystemWindows(false);
        }

        host = new FrameLayout(this);
        host.setBackgroundColor(BACKGROUND);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BACKGROUND);
        host.addView(root, new FrameLayout.LayoutParams(-1, -1));
        setContentView(host);
        configureInsets();

        // A fresh Activity cannot infer a prior WebView generation's live state.
        LauncherOptions.prefs(this).edit()
                .putBoolean(LauncherOptions.GENERATING_NOW, false)
                .putLong(LauncherOptions.GENERATION_EVENT_AT, SystemClock.elapsedRealtime())
                .apply();
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
        // Full screen must NOT mean drawing important web controls under a
        // punch-hole camera. Reserve the stable top inset even when the status
        // bar is hidden; compute cutout and keyboard insets on every update.
        if (Build.VERSION.SDK_INT >= 30) {
            host.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                Insets stableStatus = insets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.statusBars());
                Insets keyboard = insets.getInsets(WindowInsets.Type.ime());
                DisplayCutout displayCutout = insets.getDisplayCutout();
                int cutTop = displayCutout == null ? 0 : displayCutout.getSafeInsetTop();
                int cutLeft = displayCutout == null ? 0 : displayCutout.getSafeInsetLeft();
                int cutRight = displayCutout == null ? 0 : displayCutout.getSafeInsetRight();
                int cutBottom = displayCutout == null ? 0 : displayCutout.getSafeInsetBottom();
                int top = fullscreenWeb()
                        ? Math.max(stableStatus.top, cutTop)
                        : Math.max(bars.top, cutTop);
                host.setPadding(Math.max(bars.left, cutLeft), top,
                        Math.max(bars.right, cutRight),
                        Math.max(Math.max(bars.bottom, cutBottom), keyboard.bottom));
                return insets;
            });
            host.requestApplyInsets();
        } else {
            // Android 10 uses the system's native display-cutout avoidance.
            android.view.WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER;
            getWindow().setAttributes(attributes);
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
        if (Build.VERSION.SDK_INT >= 30) host.requestApplyInsets();
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
        icon.setBackground(highlightCard(22));
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        iconLp.topMargin = dp(36);
        column.addView(icon, iconLp);
        TextView heading = text("你的酒馆，\n即刻开启。", 34, WHITE, true);
        heading.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(-1, -2);
        headingLp.topMargin = dp(24);
        column.addView(heading, headingLp);
        TextView subtitle = text("绑定一次，之后打开 App 直接进入 SillyTavern。", 14, MUTED, false);
        subtitle.setPadding(0, dp(13), 0, 0);
        column.addView(subtitle);

        LinearLayout card = card(column, 35);
        card.addView(text("01  /  CONNECT YOUR TAVERN", 11, ACCENT, true));
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

        Button bindButton = yellowButton("绑定酒馆 · 开始冒险  →");
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
                            if ("generation_started".equals(type)
                                    || "generation_idle".equals(type)) {
                                boolean generating = "generation_started".equals(type);
                                LauncherOptions.prefs(this).edit()
                                        .putBoolean(LauncherOptions.GENERATING_NOW, generating)
                                        .putLong(LauncherOptions.GENERATION_EVENT_AT,
                                                SystemClock.elapsedRealtime()).apply();
                            }
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
            } else DownloadHandler.download(this, url, userAgent, disposition, mime,
                    webView == null ? boundUrl : webView.getUrl());
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

        LinearLayout hero = vertical();
        hero.setPadding(dp(20), dp(23), dp(20), dp(23));
        hero.setBackground(highlightCard(21));
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, -2);
        heroLp.topMargin = dp(22);
        content.addView(hero, heroLp);
        TextView kicker = text("SILLYTAVERN  /  MOBILE CONSOLE", 10, ACCENT, true);
        kicker.setLetterSpacing(.17f);
        hero.addView(kicker);
        TextView mainTitle = text("酒馆控制中心", 28, WHITE, true);
        mainTitle.setPadding(0, dp(12), 0, 0);
        hero.addView(mainTitle);
        TextView subtitle = text("沉浸式阅读 · 文件管理 · 后台提醒", 13, MUTED, false);
        subtitle.setPadding(0, dp(8), 0, 0);
        hero.addView(subtitle);
        TextView version = text("VERSION  0.4.0      •      CUSTOM WEBVIEW", 10, ACCENT, true);
        version.setPadding(0, dp(22), 0, 0);
        hero.addView(version);

        LinearLayout boundCard = card(content, 14);
        LinearLayout status = new LinearLayout(this);
        status.setGravity(Gravity.CENTER_VERTICAL);
        boundCard.addView(status);
        TextView boundTitle = text("●  已绑定酒馆", 15, WHITE, true);
        status.addView(boundTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView protocol = text(boundUrl.startsWith("https://") ? "HTTPS" : "HTTP", 11,
                boundUrl.startsWith("https://") ? Color.rgb(129, 225, 176) : ACCENT, true);
        protocol.setPadding(dp(10), dp(5), dp(10), dp(5));
        protocol.setBackground(rounded(PANEL_LIGHT, 10, OUTLINE));
        status.addView(protocol);
        TextView address = text(boundUrl, 13, MUTED, false);
        address.setMaxLines(2);
        address.setPadding(0, dp(10), 0, 0);
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
        toggleRow(display, "全屏沉浸模式", "隐藏启动器工具栏与系统栏；为挖孔摄像头保留安全区域。",
                LauncherOptions.FULL_SCREEN, () -> applyFullscreen());

        section(content, "BACKGROUND", "后台与运行", 22);
        LinearLayout keepCard = card(content, 10);
        toggleRow(keepCard, "增强后台保活", "开启前台服务及持续通知，提高进程存活优先级；不保证后台生成不断线。",
                LauncherOptions.KEEP_ALIVE, () -> {
                    if (LauncherOptions.enabled(this, LauncherOptions.KEEP_ALIVE)) startSessionService();
                    else stopSessionService();
                    NotificationHelper.show(this);
                });
        separator(keepCard);
        toggleRow(keepCard, "灵动岛媒体卡片 · 实验性",
                "模拟系统媒体状态，显示酒馆运行/生成中和会话计时进度。" +
                "不播放音视频、不抢音频焦点；须开启上方保活，显示效果取决于手机系统。",
                LauncherOptions.MEDIA_CARD, () -> {
                    if (LauncherOptions.enabled(this, LauncherOptions.KEEP_ALIVE)) {
                        startSessionService();
                    } else if (LauncherOptions.enabled(this, LauncherOptions.MEDIA_CARD)) {
                        toast("请先开启上方的增强后台保活，否则媒体卡片不会出现");
                    }
                });
        TextView battery = outlinedButton("打开系统电池优化设置  ↗");
        LinearLayout.LayoutParams batteryLp = new LinearLayout.LayoutParams(-1, dp(46));
        batteryLp.topMargin = dp(12);
        keepCard.addView(battery, batteryLp);
        battery.setOnClickListener(v -> {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); }
            catch (Exception e) { openAppInfo(); }
        });
        TextView keepNote = text("保活本体仍是 Android 前台服务；媒体卡片仅作状态展示。" +
                "进度表示本次后台会话已运行的时间，每小时循环，并非回复生成比例。" +
                "同时使用小红书视频时，系统可能只展示一个媒体卡片。" +
                "系统仍可能冻结 WebView 或中断网络连接。", 11, MUTED, false);
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

        section(content, "FILE ENGINE", "文件与导入导出", 22);
        LinearLayout files = card(content, 10);
        files.addView(text("支持角色卡、预设与聊天文件的常规系统选择器导入；" +
                "HTTP 文件下载和网页 Blob 导出分别处理。酒馆内的删除操作仍由网站执行。", 12,
                MUTED, false));
        TextView testExport = outlinedButton("测试导出 · 保存一个 TXT 文件  →");
        LinearLayout.LayoutParams testLp = new LinearLayout.LayoutParams(-1, dp(48));
        testLp.topMargin = dp(16);
        files.addView(testExport, testLp);
        testExport.setOnClickListener(v -> {
            if (webView == null) { toast("酒馆页面尚未打开"); return; }
            injectScript(webView, "blob_export.js");
            webView.evaluateJavascript("(function(){if(typeof window.__stLauncherTestExport===" +
                    "'function'){window.__stLauncherTestExport()}else{alert('原生导出接口未连接，" +
                    "请刷新酒馆重试')}})()", null);
        });
        TextView downloads = outlinedButton("打开系统下载列表  ↗");
        LinearLayout.LayoutParams dlLp = new LinearLayout.LayoutParams(-1, dp(48));
        dlLp.topMargin = dp(10);
        files.addView(downloads, dlLp);
        downloads.setOnClickListener(v -> {
            try { startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)); }
            catch (Exception e) { toast("可在文件管理器 → 下载 → SillyTavern 查看文件"); }
        });
        TextView fileNote = text("导出位置：下载 / SillyTavern。测试文件可验证" +
                " WebView → Android 存储链路；部分第三方插件采用非标准导出方式，仍可能不兼容。", 11,
                MUTED, false);
        fileNote.setPadding(0, dp(13), 0, 0);
        files.addView(fileNote);

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
        TextView foot = text("ST LAUNCHER  v0.3.0   •   非官方 SillyTavern 启动器", 11, MUTED, false);
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
        english.setLetterSpacing(.17f);
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
        card.setBackground(rounded(PANEL, 18, OUTLINE));
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
        button.setBackground(rounded(ACCENT, 13, 0));
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

    private GradientDrawable highlightCard(int radius) {
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(36, 47, 76), Color.rgb(20, 30, 48), Color.rgb(14, 22, 35)});
        gradient.setCornerRadius(dp(radius));
        gradient.setStroke(dp(1), Color.rgb(65, 74, 100));
        return gradient;
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
        // In opt-in foreground-session mode leave the WebView resumed while the
        // Activity is backgrounded. No promise: OS/Chromium may still throttle it.
        if (webView != null && !LauncherOptions.enabled(this, LauncherOptions.KEEP_ALIVE)) {
            webView.onPause();
        }
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
