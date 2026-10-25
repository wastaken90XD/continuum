package ml.docilealligator.infinityforreddit.webclient;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.splashscreen.SplashScreen;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import ml.docilealligator.infinityforreddit.R;

/**
 * Continuum, standalone: the entire app is this one screen -- a WebView showing the real desktop
 * reddit.com. There is no separate native UI, account/database layer, or dependency-injection
 * graph anymore; the browser's own cookie jar (persisted automatically by {@link CookieManager})
 * is the one and only source of truth for whether the user is logged in, exactly like using Chrome
 * or any other browser. Signing in happens through Reddit's own website UI, inside this same
 * WebView -- there is no separate login screen to build or keep in sync.
 *
 * <p>Uses {@link #DESKTOP_USER_AGENT}, not a mobile one: Reddit decides mobile vs. desktop layout
 * server-side from the request's User-Agent, not from viewport width -- confirmed by trying the
 * viewport-only approach first and finding Reddit kept serving its mobile shell regardless. The
 * viewport-forcing script below is kept as a harmless fallback, not the real mechanism.
 *
 * <p>The media-download feature ({@code reddit_web_enhancements.js} + {@link RedditDownloadBridge})
 * is adapted from the public "Zero Trust" design pattern used by open-source Reddit media
 * downloader userscripts (trusted-CDN allowlist, HTTPS-only, sanitized filenames) -- reimplemented
 * against a native Android bridge rather than copied, since those scripts rely on Tampermonkey-only
 * APIs that don't exist in a plain WebView, and running actual third-party script against this
 * WebView's live session would be a real way to leak or hijack it.
 *
 * <p>Known limitation: v.redd.it serves video and audio as separate streams with no combined file
 * exposed anywhere, so downloaded videos have no audio track. Real muxing (Android's MediaMuxer, or
 * an in-WebView ffmpeg.wasm as some reference scripts use) needs on-device verification that could
 * not be done in the environment this was built in.
 *
 * <p>Does <b>not</b> attempt to cap the WebView's render resolution below the device's native one.
 * An earlier version tried this (shrinking the WebView's own layout bounds, then stretching the
 * smaller surface back up with a scale transform) and it caused a "slingshot" scrolling glitch --
 * almost certainly some part of Android's or Chromium's touch/fling handling using raw,
 * untransformed screen coordinates for a threshold or velocity calculation where the rest of the
 * pipeline correctly uses the view's transformed local coordinates, which only applies when a
 * scale transform is in the mix. Reverted rather than patched, since there was no way to verify a
 * fix for it without a real device.
 */
public class RedditWebActivity extends AppCompatActivity {
    private static final String TAG = "RedditWebActivity";
    private static final String REDDIT_HOME_URL = "https://www.reddit.com/";
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    /**
     * Belt-and-suspenders fallback alongside {@link #DESKTOP_USER_AGENT} -- a wide virtual
     * viewport, in case any Reddit-served page still reacts to viewport width.
     */
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

    private WebView webView;
    @Nullable
    private ProgressBar progressBar;
    @Nullable
    private String pendingEnhancementsScript;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reddit_web);

        Toolbar toolbar = findViewById(R.id.toolbar_reddit_web_activity);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }

        progressBar = findViewById(R.id.progress_bar_reddit_web_activity);
        webView = findViewById(R.id.web_view_reddit_web_activity);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setUserAgentString(DESKTOP_USER_AGENT);
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

    @Override
    protected void onPause() {
        super.onPause();
        // CookieManager persists in the background on its own, but an explicit flush here means
        // whatever Reddit has rotated the session cookies to is actually on disk before the
        // process risks being killed in the background.
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
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
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_refresh_reddit_web_activity) {
            webView.reload();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
