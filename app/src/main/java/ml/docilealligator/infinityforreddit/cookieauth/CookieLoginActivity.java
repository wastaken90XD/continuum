package ml.docilealligator.infinityforreddit.cookieauth;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.webkit.CookieManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Named;
import ml.docilealligator.infinityforreddit.Infinity;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.RedditDataRoomDatabase;
import ml.docilealligator.infinityforreddit.account.FetchMyInfo;
import ml.docilealligator.infinityforreddit.activities.BaseActivity;
import ml.docilealligator.infinityforreddit.asynctasks.ParseAndInsertNewAccount;
import ml.docilealligator.infinityforreddit.customtheme.CustomThemeWrapper;
import ml.docilealligator.infinityforreddit.events.NewUserLoggedInEvent;
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils;
import okhttp3.OkHttpClient;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONObject;
import retrofit2.Retrofit;

/**
 * One-time WebView login screen for the cookie-session auth path (see
 * {@link RedditCookieInterceptor}), and Continuum's real, primary login screen -- the OAuth
 * {@code LoginActivity} is no longer reachable from the main UI. Loads Reddit's real login page
 * and lets the user sign in normally -- nothing here is scripted or automated, including any
 * 2FA/captcha Reddit presents -- then harvests the resulting session cookie, fetches the account's
 * profile the same way {@code LoginActivity} always has ({@link FetchMyInfo}, now running over the
 * cookie session instead of an OAuth token), and inserts/updates the account exactly like a normal
 * login so every existing account-aware feature (switching, multi-account, the nav drawer) keeps
 * working unchanged.
 *
 * <p>Extends {@link BaseActivity} (not a plain {@code AppCompatActivity}) for the same reason
 * {@code LoginActivity} always has: {@code BaseActivity.onCreate} is what actually applies the
 * day/night custom-theme style overlay on top of the manifest's base Material3 theme -- skipping
 * it left {@code Toast.makeText} crashing with an unresolved Material color attribute.
 *
 * <p>Reddit's modern reddit.com login page is a client-rendered single-page app: once the user
 * authenticates, it typically updates in place via {@code history.pushState} rather than a full
 * page navigation, so {@link WebViewClient#onPageFinished} alone is not a reliable signal that
 * login finished. This polls the cookie jar on a short timer in addition to watching page loads.
 *
 * <p>Telling "logged in" from "not logged in yet" from cookies alone is subtler than just
 * checking which cookies exist: {@code token_v2} (a JWT) is set from the very first page load,
 * logged in or not -- Reddit mints one for anonymous visitors too, with its {@code sub} claim set
 * to {@code "loid"} (the anonymous visitor id) instead of {@code "user"}. Treating its mere
 * presence as "logged in" (an earlier version of this check did) fires {@link FetchMyInfo} against
 * an anonymous session the instant the login page loads, which always fails and surfaces a
 * confusing "cannot fetch user info" error before the user has even typed anything. So this reads
 * the token's payload and only proceeds once {@code sub} says {@code "user"} -- or, as a fallback
 * signal, once a {@code reddit_session} cookie shows up, which (unlike token_v2) is never minted
 * for a purely anonymous visit.
 *
 * <p>This is the one piece of the cookie-auth path that unavoidably impersonates a browser rather
 * than using Reddit's API: there is no official API for interactively signing a user in and
 * getting back a session cookie. Doing this is against Reddit's Terms of Service for anything
 * other than an actual browser session and risks the account being flagged -- see the disclosure
 * given to the user before this was made the app's primary auth path.
 */
public class CookieLoginActivity extends BaseActivity {
    public static final int REQUEST_CODE = 9041;
    private static final String LOGIN_URL = "https://www.reddit.com/login";
    private static final long COOKIE_POLL_INTERVAL_MS = 750L;
    /** Don't retry the exact same (failed) cookie more often than this -- avoids toast spam. */
    private static final long FAILED_COOKIE_RETRY_COOLDOWN_MS = 5000L;

    @Inject
    @Named("oauth")
    Retrofit mOauthRetrofit;
    @Inject
    RedditDataRoomDatabase mRedditDataRoomDatabase;
    @Inject
    @Named("current_account")
    SharedPreferences mCurrentAccountSharedPreferences;
    @Inject
    @Named("default")
    SharedPreferences mSharedPreferences;
    @Inject
    CustomThemeWrapper mCustomThemeWrapper;
    @Inject
    Executor mExecutor;

    private WebView webView;
    private Handler pollHandler;
    private Runnable pollRunnable;
    private boolean finished;
    private boolean fetchingAccountInfo;
    @Nullable
    private String lastFailedCookie;
    private long lastFailedAtElapsedMs;

    public static Intent createIntent(Context context) {
        return new Intent(context, CookieLoginActivity.class);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ((Infinity) getApplication()).getAppComponent().inject(this);

        setImmersiveModeNotApplicableBelowAndroid16();

        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);
        applyCustomTheme();

        // Without this, a cookie jar still holding a previous account's valid session would make
        // reddit.com redirect straight past the login form to the already-logged-in home feed --
        // silently "re-adding" the current account instead of letting the user sign in as a
        // different one. LoginActivity's OAuth flow does the equivalent by always forcing a fresh
        // consent screen; this is the cookie-session analogue of that.
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(CookieAuthConstants.CHROME_USER_AGENT);

        // Hide Reddit's cookie-consent wrapper before any page script runs -- it can appear over
        // the login form and steal focus/taps from the input fields. Same technique LoginActivity
        // uses for its OAuth WebView.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    "(function(){"
                            + "var ID='data-protection-consent-wrapper';"
                            + "var addStyle=function(){"
                            + "if(document.getElementById('_dpcStyle'))return;"
                            + "var s=document.createElement('style');"
                            + "s.id='_dpcStyle';"
                            + "s.textContent='#'+ID+'{display:none!important}';"
                            + "(document.head||document.documentElement||document)"
                            + ".appendChild(s);"
                            + "};"
                            + "var kill=function(){"
                            + "var el=document.getElementById(ID);"
                            + "if(el)el.remove();"
                            + "};"
                            + "addStyle();kill();"
                            + "new MutationObserver(function(){addStyle();kill();})"
                            + ".observe(document.documentElement||document,"
                            + "{childList:true,subtree:true});"
                            + "})()",
                    Collections.singleton("https://*.reddit.com"));
        }

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                checkForSessionCookie();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                super.doUpdateVisitedHistory(view, url, isReload);
                // Fires on client-side (history.pushState/replaceState) navigation too, unlike
                // onPageFinished -- reddit.com's post-login routing is exactly that kind of
                // same-document navigation on a single-page app, so this is the real signal.
                checkForSessionCookie();
            }
        });

        webView.loadUrl(LOGIN_URL);

        // Belt-and-suspenders: polls regardless of any WebView navigation callback, since a
        // single-page app can update its logged-in state without any navigation event firing at
        // all (e.g. an XHR-driven login that only swaps a cookie and some in-page state).
        pollHandler = new Handler(getMainLooper());
        pollRunnable = () -> {
            checkForSessionCookie();
            if (!finished && !fetchingAccountInfo) {
                pollHandler.postDelayed(pollRunnable, COOKIE_POLL_INTERVAL_MS);
            }
        };
        pollHandler.postDelayed(pollRunnable, COOKIE_POLL_INTERVAL_MS);
    }

    @Override
    protected void onDestroy() {
        if (pollHandler != null && pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
        }
        super.onDestroy();
    }

    private void checkForSessionCookie() {
        if (finished || fetchingAccountInfo) {
            return;
        }

        String cookie = CookieManager.getInstance().getCookie("https://www.reddit.com");
        if (cookie == null || !looksLikeRealSession(cookie)) {
            return;
        }

        if (cookie.equals(lastFailedCookie)
                && android.os.SystemClock.elapsedRealtime() - lastFailedAtElapsedMs < FAILED_COOKIE_RETRY_COOLDOWN_MS) {
            // This exact cookie already failed a fetch moments ago and nothing has changed --
            // retrying it immediately would just fail again and spam the same error toast every
            // poll tick. Wait it out; the next poll after the cooldown (or a cookie change,
            // whichever comes first) will try again.
            return;
        }

        // Kept for the download/media-WebView helpers that read this standalone slot directly;
        // AccountManagement.switchAccount mirrors it for every later account switch.
        getSharedPreferences(CookieAuthConstants.COOKIE_PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(CookieAuthConstants.COOKIE_PREFS_KEY, cookie)
                .apply();
        CookieRetrofitClient.invalidate();

        fetchingAccountInfo = true;

        // Built against this exact just-captured cookie rather than mOauthRetrofit (whose
        // RedditCookieInterceptor reads whatever account is currently "current"): when adding a
        // second/third account, the current account is still the *old* one at this point, and
        // fetching "my info" through it would look up the wrong account entirely.
        OkHttpClient explicitClient = new OkHttpClient.Builder()
                .addInterceptor(new RedditCookieInterceptor(cookie))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        Retrofit explicitRetrofit = mOauthRetrofit.newBuilder().client(explicitClient).build();

        FetchMyInfo.fetchAccountInfo(mExecutor, new Handler(getMainLooper()), explicitRetrofit,
                mRedditDataRoomDatabase, cookie, new FetchMyInfo.FetchMyInfoListener() {
                    @Override
                    public void onFetchMyInfoSuccess(String name, String profileImageUrl,
                                                      @Nullable String bannerImageUrl, int karma, boolean isMod) {
                        mCurrentAccountSharedPreferences.edit()
                                .putString(SharedPreferencesUtils.ACCESS_TOKEN, cookie)
                                .putString(SharedPreferencesUtils.ACCOUNT_NAME, name)
                                .putString(SharedPreferencesUtils.ACCOUNT_IMAGE_URL, profileImageUrl)
                                .apply();
                        mCurrentAccountSharedPreferences.edit()
                                .remove(SharedPreferencesUtils.SUBSCRIBED_THINGS_SYNC_TIME)
                                .apply();
                        // No OAuth refresh token or auth code exists for a cookie-session
                        // account -- the session cookie itself (stored as "accessToken") is the
                        // whole credential, refreshed by logging in again when it expires.
                        ParseAndInsertNewAccount.parseAndInsertNewAccount(mExecutor, new Handler(getMainLooper()),
                                name, cookie, "", profileImageUrl, bannerImageUrl, karma, isMod, "",
                                mRedditDataRoomDatabase.accountDao(), () -> {
                                    EventBus.getDefault().post(new NewUserLoggedInEvent());
                                    finishSuccessfully();
                                });
                    }

                    @Override
                    public void onFetchMyInfoFailed(boolean parseFailed) {
                        fetchingAccountInfo = false;
                        lastFailedCookie = cookie;
                        lastFailedAtElapsedMs = android.os.SystemClock.elapsedRealtime();
                        Toast.makeText(getApplicationContext(),
                                parseFailed ? R.string.parse_user_info_error : R.string.cannot_fetch_user_info,
                                Toast.LENGTH_SHORT).show();
                        // The cookie we grabbed didn't actually work (e.g. captured mid-redirect,
                        // before Reddit finished minting the session) -- go back to polling instead
                        // of leaving the user stuck with no path forward. checkForSessionCookie's
                        // cooldown check keeps this from immediately refiring on the same cookie.
                        if (pollHandler != null && pollRunnable != null) {
                            pollHandler.postDelayed(pollRunnable, COOKIE_POLL_INTERVAL_MS);
                        }
                    }
                });
    }

    /**
     * Whether {@code cookieHeader} (the full {@code "name1=value1; name2=value2; ..."} string from
     * the cookie jar) shows real, logged-in evidence rather than just an anonymous visit.
     */
    private boolean looksLikeRealSession(String cookieHeader) {
        if (cookieHeader.contains("reddit_session=")) {
            // Never minted for a purely anonymous visit, unlike token_v2 -- sufficient on its own.
            return true;
        }
        String tokenV2 = extractCookieValue(cookieHeader, "token_v2");
        return tokenV2 != null && !isAnonymousJwt(tokenV2);
    }

    @Nullable
    private static String extractCookieValue(String cookieHeader, String name) {
        String prefix = name + "=";
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(prefix)) {
                return trimmed.substring(prefix.length());
            }
        }
        return null;
    }

    /**
     * Reddit's {@code token_v2} cookie is a JWT with a {@code sub} claim of either {@code "loid"}
     * (anonymous visitor id -- minted even when logged out) or {@code "user"} (a real signed-in
     * session). If the token can't be decoded at all, errs toward "not confirmed yet" (false isn't
     * returned here) rather than treating an unreadable token as proof of login.
     */
    private static boolean isAnonymousJwt(String jwt) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) {
                return true;
            }
            String payload = parts[1];
            int remainder = payload.length() % 4;
            if (remainder != 0) {
                payload = payload + "====".substring(remainder);
            }
            byte[] decoded = android.util.Base64.decode(payload,
                    android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
            JSONObject json = new JSONObject(new String(decoded, StandardCharsets.UTF_8));
            return "loid".equals(json.optString("sub", "loid"));
        } catch (Exception e) {
            // Unreadable/unexpected token shape -- treat as anonymous (i.e. keep waiting) rather
            // than risk firing FetchMyInfo against a session that was never actually established.
            return true;
        }
    }

    private void finishSuccessfully() {
        finished = true;
        setResult(Activity.RESULT_OK);
        finish();
    }

    @Override
    public SharedPreferences getDefaultSharedPreferences() {
        return mSharedPreferences;
    }

    @Override
    public SharedPreferences getCurrentAccountSharedPreferences() {
        return mCurrentAccountSharedPreferences;
    }

    @Override
    public CustomThemeWrapper getCustomThemeWrapper() {
        return mCustomThemeWrapper;
    }

    @Override
    protected void applyCustomTheme() {
        if (webView != null) {
            webView.setBackgroundColor(mCustomThemeWrapper.getBackgroundColor());
        }
    }

    @Override
    public void setCustomFont(@Nullable Typeface typeface, @Nullable Typeface titleTypeface, @Nullable Typeface contentTypeface) {
        // Nothing here but the WebView itself -- no app-drawn text to re-font.
    }

    @Override
    public void saveResumeState(@androidx.annotation.NonNull Bundle out) {
        // A one-shot login screen has nothing worth resuming back onto.
    }

    @Override
    public void restoreResumeState(@androidx.annotation.NonNull Bundle state) {
        // Nothing saved above, so nothing to restore.
    }
}
