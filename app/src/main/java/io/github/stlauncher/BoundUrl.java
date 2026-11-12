package io.github.stlauncher;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

final class BoundUrl {
    private BoundUrl() {}

    static String normalize(String input) {
        String value = input == null ? "" : input.trim();
        if (value.isEmpty()) throw new IllegalArgumentException("请填写酒馆网站地址");
        // For a user-configured SillyTavern endpoint, never silently upgrade to HTTPS.
        // A missing scheme defaults to HTTP; explicit https:// is preserved unchanged.
        if (!value.contains("://")) {
            value = "http://" + value;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("仅支持 http:// 或 https:// 网站");
            }
            if (uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535 || uri.getPort() == 0) {
                throw new IllegalArgumentException("网址无效，示例：http://192.168.1.5:8000");
            }
            // Keep the configured path, query and fragment: reverse proxy paths are valid.
            return uri.normalize().toASCIIString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("网址格式错误，请检查空格、端口和特殊字符");
        }
    }

    static String origin(String address) {
        try {
            URI uri = new URI(address);
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://"
                    + uri.getHost().toLowerCase(Locale.ROOT)
                    + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        } catch (Exception ignored) {
            return "";
        }
    }

    static boolean sameOrigin(String address, String candidate) {
        try {
            URI a = new URI(address);
            URI b = new URI(candidate);
            return a.getHost() != null && b.getHost() != null
                    && a.getHost().equalsIgnoreCase(b.getHost())
                    && a.getScheme().equalsIgnoreCase(b.getScheme())
                    && effectivePort(a) == effectivePort(b);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
