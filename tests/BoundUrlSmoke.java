package io.github.stlauncher;
public final class BoundUrlSmoke {
    public static void main(String[] args) {
        assertEq("http://192.168.1.5:8000", BoundUrl.normalize("192.168.1.5:8000"));
        assertEq("https://tavern.example.com/st/", BoundUrl.normalize("https://tavern.example.com/st/"));
        assertEq("https://tavern.example.com", BoundUrl.origin("https://tavern.example.com/a?b=1"));
        if (!BoundUrl.sameOrigin("https://example.com", "https://example.com:443/page"))
            throw new AssertionError("default TLS port should be same-origin");
        if (BoundUrl.sameOrigin("https://example.com", "http://example.com"))
            throw new AssertionError("scheme mismatch must not be same-origin");
        try { BoundUrl.normalize("file:///etc/passwd"); throw new AssertionError("file scheme accepted"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("PASS: BoundUrl parsing and origin checks");
    }
    private static void assertEq(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " got " + actual);
    }
}
