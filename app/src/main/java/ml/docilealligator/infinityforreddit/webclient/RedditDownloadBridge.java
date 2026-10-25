package ml.docilealligator.infinityforreddit.webclient;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Native side of {@code reddit_web_enhancements.js}'s download button. Deliberately re-validates
 * every URL itself rather than trusting the JS side -- the JS layer already checks this, but
 * {@code @JavascriptInterface} methods are reachable from any script that ends up running in this
 * WebView, so the native boundary has to hold even if the page-side check is ever bypassed.
 */
public class RedditDownloadBridge {
    private static final String TAG = "RedditDownloadBridge";

    /** Reddit's own media CDNs only -- never an arbitrary third party, regardless of what the page sends. */
    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "i.redd.it", "preview.redd.it", "external-preview.redd.it", "v.redd.it"));

    private final Context appContext;
    private final Handler mainHandler;

    public RedditDownloadBridge(Context context) {
        this.appContext = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * @param url      must be an https URL on one of {@link #ALLOWED_HOSTS}
     * @param filename suggested filename; re-sanitized here regardless of what the page already did
     * @param typeHint "video" or "image", cosmetic only (affects nothing but the toast/subfolder)
     */
    @JavascriptInterface
    public void downloadMedia(String url, @Nullable String filename, @Nullable String typeHint) {
        if (!isAllowedMediaUrl(url)) {
            Log.w(TAG, "Blocked download of disallowed URL: " + url);
            showToast("Can't download: not a recognized Reddit media URL");
            return;
        }

        String safeName = sanitizeFilename(filename);
        boolean isVideo = "video".equals(typeHint);
        if (!safeName.contains(".")) {
            safeName = safeName + (isVideo ? ".mp4" : ".jpg");
        }

        try {
            DownloadManager downloadManager = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (downloadManager == null) {
                showToast("Downloads aren't available on this device");
                return;
            }

            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(safeName);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "reddit-posts/" + safeName);

            // Mirrors the real browser request shape -- preview.redd.it in particular can be picky
            // about missing Referer, and the session cookie matters for any post that isn't purely
            // public.
            String cookie = CookieManager.getInstance().getCookie("https://www.reddit.com");
            if (cookie != null && !cookie.isEmpty()) {
                request.addRequestHeader("Cookie", cookie);
            }
            request.addRequestHeader("Referer", "https://www.reddit.com/");

            downloadManager.enqueue(request);
            showToast(isVideo
                    ? "Downloading video (without audio -- see note in app) \u2026"
                    : "Downloading image\u2026");
        } catch (Exception e) {
            Log.e(TAG, "Download enqueue failed for " + url, e);
            showToast("Download failed to start");
        }
    }

    @JavascriptInterface
    public void log(String message) {
        Log.d(TAG, "[page] " + message);
    }

    private void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show());
    }

    private static boolean isAllowedMediaUrl(@Nullable String value) {
        if (value == null || value.isEmpty() || value.length() > 4096) {
            return false;
        }
        Uri uri = Uri.parse(value);
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        return host != null && ALLOWED_HOSTS.contains(host.toLowerCase());
    }

    @NonNull
    private static String sanitizeFilename(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "reddit-media";
        }
        String result = value
                .replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "")
                .replaceAll("\\s+", "_")
                .replaceAll("__+", "_")
                .trim();
        if (result.length() > 150) {
            result = result.substring(0, 150);
        }
        return result.isEmpty() ? "reddit-media" : result;
    }
}
