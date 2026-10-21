package ml.docilealligator.infinityforreddit.cookieauth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/**
 * One-time WebView login screen for the cookie-session auth path (see
 * {@link RedditCookieInterceptor}). Loads Reddit's real login page and lets the user sign in
 * normally -- nothing here is scripted or automated, including any 2FA/captcha Reddit presents
 * -- then harvests the resulting session cookie and hands control back to the caller.
 *
 * <p>This is the one piece of the cookie-auth path that unavoidably impersonates a browser
 * rather than using Reddit's API: there is no official API for interactively signing a user in
 * and getting back a session cookie. Doing this is against Reddit's Terms of Service for
 * anything other than an actual browser session and risks the account being flagged -- see the
 * disclosure given to the user before this was implemented.
 */
public class CookieLoginActivity extends AppCompatActivity {
    public static final int REQUEST_CODE = 9041;
    private static final String LOGIN_URL = "https://www.reddit.com/login";

    private boolean finished;

    public static Intent createIntent(Context context) {
        return new Intent(context, CookieLoginActivity.class);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = new WebView(this);
        setContentView(webView);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(CookieAuthConstants.CHROME_USER_AGENT);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                maybeFinishWithCookie(url);
            }
        });

        webView.loadUrl(LOGIN_URL);
    }

    private void maybeFinishWithCookie(@Nullable String url) {
        if (finished || url == null || !url.contains("reddit.com") || url.contains("/login")) {
            // Either not on reddit.com yet, or still on the login page itself (which fires
            // onPageFinished for the initial GET before the user has done anything) -- keep
            // waiting for the next navigation rather than treating this as a final state.
            return;
        }

        String cookie = CookieManager.getInstance().getCookie("https://www.reddit.com");
        if (cookie == null || !cookie.contains("reddit_session")) {
            // Still mid-flow (captcha, 2FA, "stay logged in" interstitial, etc.) -- the session
            // cookie isn't set yet, so keep waiting for further navigation.
            return;
        }

        getSharedPreferences(CookieAuthConstants.COOKIE_PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(CookieAuthConstants.COOKIE_PREFS_KEY, cookie)
                .apply();

        CookieRetrofitClient.invalidate();

        finished = true;
        setResult(Activity.RESULT_OK);
        finish();
    }
}
