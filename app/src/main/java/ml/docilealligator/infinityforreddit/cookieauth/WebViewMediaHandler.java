package ml.docilealligator.infinityforreddit.cookieauth;

import android.content.Context;
import android.content.SharedPreferences;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.Nullable;

/**
 * Fallback renderer for Reddit-hosted media (v.redd.it DASH video, reddit.com/gallery/*,
 * redd.it short links) for use alongside the cookie-session auth path: those URLs require the
 * session cookie once Reddit's unauthenticated CDN access is gone, and loading the real
 * reddit.com page in a WebView with the cookie synced in is the one approach that works
 * unconditionally, regardless of whether the app's normal ExoPlayer/Glide pipeline can be handed
 * a Cookie header for a given URL shape.
 *
 * <p>This is additive: it does not replace the app's existing media pipeline. See the disclosure
 * given to the user before this was built for why it is not wired in as the default renderer.
 */
public final class WebViewMediaHandler {
    private WebViewMediaHandler() {
    }

    public static boolean isRedditMedia(@Nullable String url) {
        if (url == null) {
            return false;
        }
        return url.contains("v.redd.it")
                || url.contains("redd.it")
                || url.contains("reddit.com/gallery/");
    }

    /**
     * Builds a WebView preloaded with the saved session cookie and pointed at {@code postUrl}.
     * The caller owns the returned view's lifecycle (attach it to a layout, call
     * {@link WebView#destroy()} when done with it).
     */
    public static WebView buildMediaWebView(Context context, String postUrl) {
        WebView webView = new WebView(context);

        SharedPreferences prefs = context.getSharedPreferences(
                CookieAuthConstants.COOKIE_PREFS_NAME, Context.MODE_PRIVATE);
        String cookie = prefs.getString(CookieAuthConstants.COOKIE_PREFS_KEY, null);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        if (cookie != null && !cookie.isEmpty()) {
            for (String part : cookie.split(";")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                cookieManager.setCookie("https://www.reddit.com", trimmed);
                cookieManager.setCookie("https://v.redd.it", trimmed);
            }
            cookieManager.flush();
        }

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        webView.getSettings().setUserAgentString(CookieAuthConstants.CHROME_USER_AGENT);
        webView.setWebViewClient(new WebViewClient());

        webView.loadUrl(postUrl);
        return webView;
    }
}
