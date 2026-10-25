package ml.docilealligator.infinityforreddit.webclient;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.OneShotPreDrawListener;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import javax.inject.Inject;
import javax.inject.Named;
import ml.docilealligator.infinityforreddit.Infinity;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.activities.BaseActivity;
import ml.docilealligator.infinityforreddit.activities.MainActivity;
import ml.docilealligator.infinityforreddit.cookieauth.CookieAuthConstants;
import ml.docilealligator.infinityforreddit.cookieauth.CookieLoginActivity;
import ml.docilealligator.infinityforreddit.customtheme.CustomThemeWrapper;

/**
 * Continuum's main screen (the Continuum Technical Specification Document pivot, "full_replace"
 * option): a WebView showing the real reddit.com, rather than a native client built on
 * {@code RedditAPI}. The native screens ({@link MainActivity} and everything under it) are left in
 * place, unchanged and still fully functional, but are no longer the app's primary entry point --
 * reachable only from this screen's "Classic view" menu item while this pivot is being proven out.
 *
 * <p>Deliberately does <b>not</b> use a desktop-browser User-Agent string, even though the user
 * asked for "the desktop site": {@link CookieAuthConstants#CHROME_USER_AGENT} is shared verbatim
 * across {@link CookieLoginActivity} (where the session cookie was minted), this screen, and
 * every other cookie-session consumer specifically so Reddit never sees the UA change mid-session
 * -- see that constant's javadoc for why a mismatch there reads as session hijacking. The desktop
 * *layout* is instead forced the way a browser's own "Desktop site" toggle does it: a wide virtual
 * viewport, which is what Reddit's responsive layout actually keys off of. If Reddit's frontend
 * ever turns out to gate the desktop template on UA sniffing rather than viewport width, this will
 * need revisiting -- there was no way to confirm which one on a live site from this sandbox.
 *
 * <p>The media-download feature ({@code reddit_web_enhancements.js} + {@link RedditDownloadBridge})
 * is adapted from the public "Zero Trust" design pattern used by open-source Reddit media
 * downloader userscripts (trusted-CDN allowlist, HTTPS-only, sanitized filenames) -- reimplemented
 * here against a native Android bridge rather than copied, since the reference scripts rely on
 * Tampermonkey-only APIs (GM_download) that don't exist in a plain WebView, and since running
 * actual third-party script verbatim inside a WebView holding the user's live session would be a
 * real way to leak or hijack it.
 *
 * <p>Known v1 limitation: v.redd.it serves video and audio as separate streams with no combined
 * file exposed anywhere. This ships video-only downloads (no audio) rather than a muxed file,
 * since real audio/video muxing (Android's MediaMuxer, or an in-WebView ffmpeg.wasm like some
 * reference scripts use) needs on-device verification this sandbox cannot do, and a silently
 * broken "muxed" file would be worse than an honestly-labeled video-only one.
 *
 * <p>The WebView's actual rendering surface is capped below 720p regardless of the device's real
 * screen size -- see {@link #capWebViewRenderResolution} -- trading rendering sharpness for
 * compositing/GPU cost, on the assumption that a desktop-width page rendered inside a mobile
 * WebView is heavier to keep smooth than the same screen's native UI ever was.
 */
public class RedditWebActivity extends BaseActivity {
    private static final String TAG = "RedditWebActivity";
    private static final String REDDIT_HOME_URL = "https://www.reddit.com/";
    /**
     * Render-resolution cap (see {@link #capWebViewRenderResolution}): the WebView's own actual
     * pixel bounds, i.e. what the browser engine rasterizes into, are kept at or under this on its
     * short side -- comfortably under 720p's 720, not just technically under it.
     */
    private static final int MAX_RENDER_SHORT_SIDE_PX = 700;
    /** Same idea as {@link #MAX_RENDER_SHORT_SIDE_PX}, for the long side (720p's 1280, same ratio). */
    private static final int MAX_RENDER_LONG_SIDE_PX = 1245;
    /** Forces Reddit's responsive layout to its desktop breakpoint; see class javadoc. */
    private static final String FORCE_DESKTOP_VIEWPORT_JS =
            "(function(){"
                    + "function apply(){"
                    + "var m=document.querySelector('meta[name=\"viewport\"]');"
                    + "if(!m){m=document.createElement('meta');m.name='viewport';"
                    + "(document.head||document.documentElement).appendChild(m);}"
                    + "m.setAttribute('content','width=1280');"
                    + "}"
                    + "apply();"
                    + "document.addEventListener('DOMContentLoaded',apply);"
                    + "new MutationObserver(apply).observe(document.documentElement||document,"
                    + "{childList:true,subtree:false});"
                    + "})()";

    @Inject
    @Named("current_account")
    SharedPreferences mCurrentAccountSharedPreferences;
    @Inject
    @Named("default")
    SharedPreferences mSharedPreferences;
    @Inject
    CustomThemeWrapper mCustomThemeWrapper;

    private WebView webView;
    @Nullable
    private ProgressBar progressBar;
    @Nullable
    private String pendingEnhancementsScript;

    private final ActivityResultLauncher<Intent> loginLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                // Whatever the outcome, re-sync whatever cookie now exists and reload -- a
                // cancelled login still leaves the user wherever they were in the WebView itself.
                syncStoredCookieIntoWebView();
                if (webView != null) {
                    webView.reload();
                }
            });

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Mirrors MainActivity: this screen now carries AppTheme.Launcher (the manifest's
        // launcher theme, including the API31+ Theme.SplashScreen parent), so it needs the same
        // compat call to get a normal splash/exit transition instead of whatever the OS does by
        // default when a Theme.SplashScreen-derived theme's owning activity never calls this.
        androidx.core.splashscreen.SplashScreen.installSplashScreen(this);

        ((Infinity) getApplication()).getAppComponent().inject(this);

        setImmersiveModeNotApplicableBelowAndroid16();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_reddit_web);

        Toolbar toolbar = findViewById(R.id.toolbar_reddit_web_activity);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.reddit_web_activity_label);
        }

        progressBar = findViewById(R.id.progress_bar_reddit_web_activity);
        webView = findViewById(R.id.web_view_reddit_web_activity);
        View webViewContainer = findViewById(R.id.web_view_container_reddit_web_activity);

        applyCustomTheme();

        // One-shot: the container's bounds are only needed once (what the WebView should visually
        // fill), and this activity isn't declared to handle orientation changes itself, so a
        // rotation recreates it and runs this again from scratch rather than needing a listener
        // that keeps re-measuring.
        OneShotPreDrawListener.add(webViewContainer, () -> capWebViewRenderResolution(webViewContainer));

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
        syncStoredCookieIntoWebView();

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(CookieAuthConstants.CHROME_USER_AGENT);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setSupportZoom(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);

        webView.addJavascriptInterface(new RedditDownloadBridge(this), "ContinuumDownloadBridge");

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                    webView, FORCE_DESKTOP_VIEWPORT_JS, Collections.singleton("https://*.reddit.com"));
        }

        pendingEnhancementsScript = readAssetAsString("reddit_web_enhancements.js");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, @Nullable Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                if (progressBar != null) {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(0);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (progressBar != null) {
                    progressBar.setVisibility(View.GONE);
                }
                if (pendingEnhancementsScript != null) {
                    view.evaluateJavascript(pendingEnhancementsScript, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                if (host != null && (host.endsWith("reddit.com") || host.equals("redd.it"))) {
                    // Stay inside this WebView for anything Reddit-hosted, including its own
                    // media CDNs (v.redd.it, i.redd.it, preview.redd.it, ...).
                    return false;
                }
                // An outbound link (an article, an external image host, etc.) -- hand it to the
                // user's real browser rather than turning this screen into a general-purpose one.
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception e) {
                    Log.w(TAG, "No activity to handle outbound link: " + uri, e);
                }
                return true;
            }
        });

        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                super.onProgressChanged(view, newProgress);
                if (progressBar != null) {
                    progressBar.setProgress(newProgress);
                    progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                }
            }
        });

        webView.loadUrl(REDDIT_HOME_URL);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    private void syncStoredCookieIntoWebView() {
        SharedPreferences prefs = getSharedPreferences(
                CookieAuthConstants.COOKIE_PREFS_NAME, MODE_PRIVATE);
        String cookie = prefs.getString(CookieAuthConstants.COOKIE_PREFS_KEY, null);
        if (cookie == null || cookie.isEmpty()) {
            return;
        }
        CookieManager cookieManager = CookieManager.getInstance();
        for (String part : cookie.split(";")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                cookieManager.setCookie("https://www.reddit.com", trimmed);
            }
        }
        cookieManager.flush();
    }

    /**
     * Keeps the WebView's actual rendering surface at or under 720p, regardless of how large the
     * screen it's shown on actually is.
     *
     * <p>Does this the way a low-power video player downscales decode resolution and lets the
     * display upscale it, rather than anything WebView-specific: shrink the View's own layout
     * bounds (which is what the browser engine's compositor actually rasterizes into) to a smaller
     * pixel size, then stretch that smaller surface back up with a plain {@link View#setScaleX}/
     * {@link View#setScaleY} transform so it still visually fills {@code container}. Touch input
     * still lands correctly -- Android's hit-testing and motion-event coordinate mapping already
     * account for a view's scale transform, the same as it does for pinch-zoom or any other
     * transformed view -- so this costs rendering sharpness (a 1280-CSS-px-wide desktop layout
     * downsampled this way will look softer, especially on a high-density display), not
     * interactivity, in exchange for rendering and compositing a meaningfully smaller number of
     * pixels on every frame.
     *
     * <p>Composes with the desktop-layout viewport forcing in {@link #FORCE_DESKTOP_VIEWPORT_JS}
     * rather than fighting it: that script tells the page to lay itself out assuming 1280 CSS
     * pixels of width regardless of the device, and the browser engine already has to scale that
     * layout to fit whatever space it is actually given -- shrinking the space it is given just
     * means that scaling-to-fit happens against a smaller raster target.
     *
     * <p>A no-op if {@code container} is already at or under the cap (e.g. a small/low-res
     * device), so this never *upscales* anything.
     */
    private void capWebViewRenderResolution(@NonNull View container) {
        int actualWidth = container.getWidth();
        int actualHeight = container.getHeight();
        if (actualWidth <= 0 || actualHeight <= 0 || webView == null) {
            return;
        }

        boolean widerThanTall = actualWidth >= actualHeight;
        int capWidth = widerThanTall ? MAX_RENDER_LONG_SIDE_PX : MAX_RENDER_SHORT_SIDE_PX;
        int capHeight = widerThanTall ? MAX_RENDER_SHORT_SIDE_PX : MAX_RENDER_LONG_SIDE_PX;

        float scale = Math.min(1f, Math.min((float) capWidth / actualWidth, (float) capHeight / actualHeight));
        if (scale >= 1f) {
            return;
        }

        int renderWidth = Math.max(1, Math.round(actualWidth * scale));
        int renderHeight = Math.max(1, Math.round(actualHeight * scale));

        ViewGroup.LayoutParams params = webView.getLayoutParams();
        params.width = renderWidth;
        params.height = renderHeight;
        webView.setLayoutParams(params);

        webView.setPivotX(0f);
        webView.setPivotY(0f);
        webView.setScaleX((float) actualWidth / renderWidth);
        webView.setScaleY((float) actualHeight / renderHeight);

        Log.d(TAG, "Capped WebView render surface to " + renderWidth + "x" + renderHeight
                + " (visually filling " + actualWidth + "x" + actualHeight + ")");
    }

    @Nullable
    private String readAssetAsString(String assetPath) {
        try (InputStream inputStream = getAssets().open(assetPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } catch (IOException e) {
            Log.e(TAG, "Failed to read asset " + assetPath, e);
            return null;
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.reddit_web_activity, menu);
        applyMenuItemTheme(menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_login_reddit_web_activity) {
            loginLauncher.launch(CookieLoginActivity.createIntent(this));
            return true;
        } else if (id == R.id.action_refresh_reddit_web_activity) {
            webView.reload();
            return true;
        } else if (id == R.id.action_classic_view_reddit_web_activity) {
            startActivity(new Intent(this, MainActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
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
    public void saveResumeState(@NonNull Bundle out) {
        // The WebView keeps its own navigation history; nothing extra to snapshot here.
    }

    @Override
    public void restoreResumeState(@NonNull Bundle state) {
        // Nothing saved above, so nothing to restore.
    }
}
