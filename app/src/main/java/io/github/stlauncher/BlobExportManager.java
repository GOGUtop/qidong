package io.github.stlauncher;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.widget.Toast;

import androidx.webkit.JavaScriptReplyProxy;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;

/** Receives a same-origin, main-frame-only, acknowledged stream of blob chunks. */
final class BlobExportManager {
    private static final long MAX_BYTES = 1024L * 1024L * 1024L; // 1 GiB per export
    private final Context context;
    private final ContentResolver resolver;
    private final String expectedOrigin;
    private Session current;

    private static final class Session {
        String id;
        String name;
        Uri destination;
        OutputStream stream;
        long bytes;
        int nextSequence;
    }

    BlobExportManager(Context context, String expectedOrigin) {
        this.context = context;
        this.resolver = context.getContentResolver();
        this.expectedOrigin = expectedOrigin;
    }

    void onMessage(String raw, Uri sourceOrigin, boolean isMainFrame, JavaScriptReplyProxy reply) {
        if (!isMainFrame || raw == null || raw.length() > 160000
                || !BoundUrl.sameOrigin(expectedOrigin, sourceOrigin.toString())) return;

        String id = "";
        String kind = "";
        int sequence = 0;
        try {
            JSONObject json = new JSONObject(raw);
            id = json.optString("id", "");
            kind = json.optString("kind", "");
            sequence = json.optInt("seq", 0);
            if (id.length() < 1 || id.length() > 64) throw new IOException("无效导出标识");

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
                    close();
                    break;
                default:
                    throw new IOException("未知导出指令");
            }
            acknowledge(reply, id, kind, sequence, true, "");
        } catch (Exception e) {
            close();
            acknowledge(reply, id, kind, sequence, false,
                    e.getMessage() == null ? "导出失败" : e.getMessage());
            Toast.makeText(context, "文件导出失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void start(String id, String filename, String mime, long size) throws IOException {
        if (current != null) throw new IOException("另一份文件正在导出");
        if (size < 0 || size > MAX_BYTES) throw new IOException("文件大小超过 1 GiB 限制");

        Session session = new Session();
        session.id = id;
        session.name = safeFileName(filename);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, session.name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,
                mime.length() > 0 && mime.length() < 128 ? mime : "application/octet-stream");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SillyTavern/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        session.destination = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (session.destination == null) throw new IOException("无法创建下载文件");
        current = session; // Track destination so errors can clean it up.
        session.stream = resolver.openOutputStream(session.destination, "w");
        if (session.stream == null) throw new IOException("无法写入下载目录");
    }

    private void append(String id, int sequence, String base64) throws IOException {
        if (current == null || !current.id.equals(id)) throw new IOException("导出会话已失效");
        if (sequence != current.nextSequence) throw new IOException("文件分块序号不连续");
        if (base64.length() > 120000) throw new IOException("分块过大");
        byte[] decoded;
        try {
            decoded = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            throw new IOException("分块编码错误", e);
        }
        if (current.bytes + decoded.length > MAX_BYTES) throw new IOException("文件超过 1 GiB 限制");
        current.stream.write(decoded);
        current.bytes += decoded.length;
        current.nextSequence++;
    }

    private void finish(String id) throws IOException {
        if (current == null || !current.id.equals(id)) throw new IOException("导出会话不存在");
        Session completed = current;
        completed.stream.close();
        completed.stream = null;
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        if (resolver.update(completed.destination, values, null, null) <= 0) {
            throw new IOException("文件保存失败");
        }
        current = null;
        Toast.makeText(context, "已导出到 下载/SillyTavern/：" + completed.name,
                Toast.LENGTH_LONG).show();
    }

    synchronized void close() {
        if (current == null) return;
        Session old = current;
        current = null;
        try { if (old.stream != null) old.stream.close(); } catch (IOException ignored) {}
        if (old.destination != null) {
            try { resolver.delete(old.destination, null, null); } catch (Exception ignored) {}
        }
    }

    private static void acknowledge(JavaScriptReplyProxy reply, String id, String kind,
                                    int seq, boolean ok, String error) {
        try {
            JSONObject message = new JSONObject();
            message.put("id", id);
            message.put("kind", kind);
            message.put("seq", seq);
            message.put("ok", ok);
            message.put("error", error);
            reply.postMessage(message.toString());
        } catch (Exception ignored) {}
    }

    static String safeFileName(String filename) {
        String value = filename == null ? "" : filename.trim();
        value = value.replaceAll("[\\\\/\\p{Cntrl}:*?\"<>|]", "_")
                .replaceAll("[\\u202A-\\u202E\\u2066-\\u2069]", "");
        if (value.equals(".") || value.equals("..") || value.isEmpty()) value = "export.bin";
        return value.length() > 120 ? value.substring(value.length() - 120) : value;
    }
}
