package io.github.stlauncher;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.widget.Toast;

/** HTTP(S) downloads (JS-generated Blob exports are handled separately). */
final class DownloadHandler {
    private DownloadHandler() {}

    static void download(Context context, String url, String userAgent,
                         String contentDisposition, String mimeType, String referringPage) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("此下载链接不是 HTTP(S)");
            }
            String guessed = URLUtil.guessFileName(url, contentDisposition, mimeType);
            String fileName = BlobExportManager.safeFileName(guessed);
            DownloadManager.Request request = new DownloadManager.Request(uri)
                    .setTitle(fileName)
                    .setDescription("SillyTavern 文件下载")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,
                            "SillyTavern/" + System.currentTimeMillis() + "_" + fileName);
            if (mimeType != null && !mimeType.trim().isEmpty()) request.setMimeType(mimeType);
            if (userAgent != null && !userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", userAgent);
            }
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
            // Send an origin-only Referer for same-origin downloads (never leak
            // the user's current chat URL to a third-party download host).
            if (referringPage != null && BoundUrl.sameOrigin(referringPage, url)) {
                request.addRequestHeader("Referer", BoundUrl.origin(referringPage) + "/");
            }
            DownloadManager manager = context.getSystemService(DownloadManager.class);
            if (manager == null) throw new IllegalStateException("系统下载服务不可用");
            manager.enqueue(request);
            Toast.makeText(context, "已开始下载：" + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            Toast.makeText(context, "下载无法开始：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
