package io.github.stlauncher;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.widget.Toast;

import java.util.Locale;

final class DownloadHandler {
    private DownloadHandler() {}

    static void download(Context context, String url, String userAgent,
                         String contentDisposition, String mimeType) {
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                throw new IllegalArgumentException("不支持的文件链接");
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
            if (userAgent != null && !userAgent.isEmpty()) request.addRequestHeader("User-Agent", userAgent);
            String cookie = CookieManager.getInstance().getCookie(url);
            if (cookie != null && !cookie.isEmpty()) request.addRequestHeader("Cookie", cookie);
            context.getSystemService(DownloadManager.class).enqueue(request);
            Toast.makeText(context, "已加入下载队列：" + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(context, "下载失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
