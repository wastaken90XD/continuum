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
import java.util.concurrent.Executor;
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
 * day/night custom-theme style overlay ({@code Theme.Normal}/{@code Theme.Normal.NormalDark}/
 * {@code Theme.Normal.AmoledDark}) on top of the manifest's base Material3 theme. Skipping it (as
 * an earlier version of this activity did, back when it never showed any view beyond the WebView
 * itself) left {@code Toast.makeText} crashing with an unresolved Material color attribute the
 * moment this activity needed to show one, since {@code AppCompatViewInflater}/
 * {@code MaterialComponentsViewInflater} intercepts even Android's own internal toast layout's
 * {@code TextView} and expects that overlay's attributes to resolve.
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
        if (finished || fetchingAccountInfo || url == null || !url.contains("reddit.com") || url.contains("/login")) {
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

        // Kept for the download/media-WebView helpers that read this standalone slot directly;
        // AccountManagement.switchAccount mirrors it for every later account switch.
        getSharedPreferences(CookieAuthConstants.COOKIE_PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(CookieAuthConstants.COOKIE_PREFS_KEY, cookie)
                .apply();
        CookieRetrofitClient.invalidate();

        fetchingAccountInfo = true;
        FetchMyInfo.fetchAccountInfo(mExecutor, new Handler(getMainLooper()), mOauthRetrofit,
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
