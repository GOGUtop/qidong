package io.github.stlauncher;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.Toast;

import androidx.webkit.JavaScriptReplyProxy;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Writes authenticated same-origin, main-frame WebMessage chunks to MediaStore.
 * Disk I/O is serialized off the WebView/UI thread; reply messages go back on UI.
 */
final class BlobExportManager {
    private static final long MAX_BYTES = 1024L * 1024L * 1024L;
    private final Context context;
    private final ContentResolver resolver;
    private final String expectedOrigin;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private Session current;
    private volatile boolean released;

    private static final class Session {
        String id;
        String name;
        Uri destination;
        OutputStream stream;
        long bytes;
        long expectedSize;
        int nextSequence;
    }

    BlobExportManager(Context context, String expectedOrigin) {
        this.context = context.getApplicationContext();
        resolver = context.getContentResolver();
        this.expectedOrigin = expectedOrigin;
    }

    void onMessage(String raw, Uri sourceOrigin, boolean isMainFrame,
                   JavaScriptReplyProxy reply) {
        if (released || !isMainFrame || sourceOrigin == null || raw == null
                || raw.length() > 160000
                || !BoundUrl.sameOrigin(expectedOrigin, sourceOrigin.toString())) return;
        try { worker.execute(() -> handleMessage(raw, reply)); }
        catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private void handleMessage(String raw, JavaScriptReplyProxy reply) {
        if (released) return;
        String id = "";
        String kind = "";
        int sequence = 0;
        try {
            JSONObject json = new JSONObject(raw);
            id = json.optString("id", "");
            kind = json.optString("kind", "");
            sequence = json.optInt("seq", 0);
            if (id.isEmpty() || id.length() > 64) throw new IOException("无效导出标识");
            switch (kind) {
                case "start":
                    start(id, json.optString("name", "export.bin"),
                            json.optString("mime", "application/octet-stream"),
                            json.optLong("size", -1));
                    break;
                case "chunk":
                    append(id, sequence, json.optString("data", ""));
                    break;
                case "finish":
                    finish(id);
                    break;
                case "cancel":
                    if (current != null && current.id.equals(id)) closeSession();
                    break;
                default:
                    throw new IOException("未知导出指令");
            }
            acknowledge(reply, id, kind, sequence, true, "");
        } catch (Exception error) {
            closeSession();
            String detail = error.getMessage() == null ? "导出失败" : error.getMessage();
            acknowledge(reply, id, kind, sequence, false, detail);
            showToast("文件导出失败：" + detail);
        }
    }

    private void start(String id, String filename, String mime, long size) throws IOException {
        if (current != null) throw new IOException("上一份文件尚未完成导出");
        if (size < 0 || size > MAX_BYTES) throw new IOException("单文件大小超过 1 GiB 限制");

        Session session = new Session();
        session.id = id;
        session.name = safeFileName(filename);
        session.expectedSize = size;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, session.name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,
                mime != null && mime.length() > 0 && mime.length() < 128
                        ? mime : "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/SillyTavern/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        session.destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (session.destination == null) throw new IOException("无法在下载目录创建文件");
        current = session; // track even if openOutputStream fails, for cleanup
        session.stream = resolver.openOutputStream(session.destination, "w");
        if (session.stream == null) throw new IOException("无法向下载目录写入文件");
    }

    private void append(String id, int sequence, String base64) throws IOException {
        if (current == null || !current.id.equals(id)) throw new IOException("导出会话已失效");
        if (sequence != current.nextSequence) throw new IOException("文件分块序号不连续");
        if (base64.length() > 120000) throw new IOException("文件分块过大");
        byte[] decoded;
        try {
            decoded = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException error) {
            throw new IOException("文件分块内容不是 Base64", error);
        }
        if (current.bytes + decoded.length > current.expectedSize
                || current.bytes + decoded.length > MAX_BYTES) {
            throw new IOException("文件写入长度超过声明大小");
        }
        current.stream.write(decoded);
        current.bytes += decoded.length;
        current.nextSequence++;
    }

    private void finish(String id) throws IOException {
        if (current == null || !current.id.equals(id)) throw new IOException("导出会话不存在");
        if (current.bytes != current.expectedSize) throw new IOException("文件尚未写完");
        Session completed = current;
        completed.stream.close();
        completed.stream = null;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (resolver.update(completed.destination, values, null, null) <= 0) {
            throw new IOException("下载文件保存失败");
        }
        current = null;
        showToast("文件已保存到 下载/SillyTavern/：" + completed.name);
    }

    void close() {
        if (released) return;
        released = true;
        worker.execute(this::closeSession);
        worker.shutdown();
    }

    private void closeSession() {
        Session old = current;
        current = null;
        if (old == null) return;
        try { if (old.stream != null) old.stream.close(); } catch (IOException ignored) {}
        if (old.destination != null) {
            try { resolver.delete(old.destination, null, null); } catch (Exception ignored) {}
        }
    }

    private void acknowledge(JavaScriptReplyProxy reply, String id, String kind,
                             int seq, boolean ok, String error) {
        try {
            JSONObject message = new JSONObject();
            message.put("id", id);
            message.put("kind", kind);
            message.put("seq", seq);
            message.put("ok", ok);
            message.put("error", error);
            String response = message.toString();
            mainHandler.post(() -> {
                try { reply.postMessage(response); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    private void showToast(String value) {
        mainHandler.post(() -> Toast.makeText(context, value, Toast.LENGTH_LONG).show());
    }

    static String safeFileName(String filename) {
        String value = filename == null ? "" : filename.trim();
        value = value.replaceAll("[\\\\/\\p{Cntrl}:*?\"<>|]", "_")
                .replaceAll("[\\u202A-\\u202E\\u2066-\\u2069]", "");
        if (value.equals(".") || value.equals("..") || value.isEmpty()) value = "export.bin";
        return value.length() > 120 ? value.substring(value.length() - 120) : value;
    }
}
