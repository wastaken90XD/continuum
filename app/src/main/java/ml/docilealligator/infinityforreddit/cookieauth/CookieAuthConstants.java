package ml.docilealligator.infinityforreddit.cookieauth;

/**
 * Shared constants for the cookie-session Reddit auth path (Continuum spec, "implement_full"
 * option). This is now the app's real, primary Reddit authentication path -- see
 * {@link RedditCookieInterceptor} and {@link CookieLoginActivity} -- not an optional add-on; see
 * the disclosure given to the user before it was made the default.
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
