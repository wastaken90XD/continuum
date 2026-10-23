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
 * login finished. This polls the cookie jar on a short timer in addition to watching page loads,
 * and accepts either {@code token_v2} (the short-lived bearer-style session cookie, ~24h) or
 * {@code reddit_session} (the longer-lived one, ~180 days, not always minted on every login) as
 * proof of a real session -- {@code loid} alone is just an anonymous visitor id present even when
 * logged out.
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
        if (cookie == null || !(cookie.contains("token_v2=") || cookie.contains("reddit_session="))) {
            // loid (anonymous visitor id) is set from the very first page load and proves
            // nothing -- only a real session cookie means the user actually signed in.
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
                        Toast.makeText(getApplicationContext(),
                                parseFailed ? R.string.parse_user_info_error : R.string.cannot_fetch_user_info,
                                Toast.LENGTH_SHORT).show();
                        // The cookie we grabbed didn't actually work (e.g. captured mid-redirect,
                        // before Reddit finished minting the session) -- go back to polling instead
                        // of leaving the user stuck with no path forward.
                        if (pollHandler != null && pollRunnable != null) {
                            pollHandler.postDelayed(pollRunnable, COOKIE_POLL_INTERVAL_MS);
                        }
                    }
                });
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
