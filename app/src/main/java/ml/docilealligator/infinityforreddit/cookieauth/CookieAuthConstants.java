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
     *
     * <p>Deliberately <b>not</b> used by
     * {@code ml.docilealligator.infinityforreddit.webclient.RedditWebActivity} -- see
     * {@link #DESKTOP_CHROME_USER_AGENT}.
     */
    public static final String CHROME_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    /**
     * Used only by {@code RedditWebActivity}'s browsing WebView (the item-17 pivot's main screen),
     * never for anything that establishes or authenticates the session itself.
     *
     * <p>Confirmed on a real device: forcing a wide virtual viewport while still sending
     * {@link #CHROME_USER_AGENT} (a mobile UA) was not enough to get Reddit's desktop layout --
     * the page kept rendering its mobile shell regardless of viewport width. Reddit's modern web
     * app evidently decides mobile vs. desktop template server-side from the request's
     * User-Agent, not from the client's viewport, so an actual desktop UA is required.
     *
     * <p>This does diverge from {@link #CHROME_USER_AGENT}'s own "byte-identical everywhere"
     * rule, which exists to avoid a UA change reading as session hijacking. That risk was always
     * a judgment call (reasoned about up front, never confirmed against Reddit's real behavior),
     * and real users routinely browse the same logged-in reddit.com account from both a phone
     * browser and a desktop browser -- different UAs, same session cookies, all day, with no
     * known Reddit mechanism that invalidates a session over that. Login itself ({@link
     * CookieLoginActivity}) and every native-UI network call ({@link RedditCookieInterceptor})
     * still use the mobile UA unchanged, so this only affects what this one WebView's own
     * requests look like.
     */
    public static final String DESKTOP_CHROME_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static final String COOKIE_PREFS_NAME = "reddit_cookie";
    public static final String COOKIE_PREFS_KEY = "cookies";
}

