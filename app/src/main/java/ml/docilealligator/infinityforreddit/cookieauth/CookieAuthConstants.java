package ml.docilealligator.infinityforreddit.cookieauth;

/**
 * Shared constants for the cookie-session Reddit auth path (Continuum spec, "implement_full"
 * option). This is a parallel, additive module: it does not replace the app's existing
 * (working) OAuth stack -- see the disclosure given before this was built. It exists so the six
 * classes the spec calls for compile and are available to wire in incrementally.
 */
public final class CookieAuthConstants {
    private CookieAuthConstants() {
    }

    /**
     * Must be byte-identical everywhere it is used ({@link RedditCookieInterceptor},
     * {@link CookieLoginActivity}, {@link WebViewMediaHandler}, {@link FourGetSearchClient}):
     * Reddit and 4get.ca both fingerprint the UA string, and a mismatch between the UA used to
     * log in and the UA used on subsequent requests is itself a signal of session hijacking.
     */
    public static final String CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    public static final String COOKIE_PREFS_NAME = "reddit_cookie";
    public static final String COOKIE_PREFS_KEY = "cookies";
}
