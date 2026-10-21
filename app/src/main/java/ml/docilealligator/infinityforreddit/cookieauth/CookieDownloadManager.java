package ml.docilealligator.infinityforreddit.cookieauth;

import android.app.DownloadManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import androidx.annotation.Nullable;

/**
 * Wraps {@link DownloadManager} so every Reddit-originated download carries the saved session
 * cookie and a Chrome user agent -- a plain {@code DownloadManager.enqueue()} call 403s against
 * Reddit's CDN once a signed URL expects a browser session.
 *
 * <p>Callers must route every URL taken from Reddit's JSON ({@code preview.images[].source.url},
 * {@code media_metadata.*}, {@code secure_media}) through {@link #unescapeAmpersand} first (or
 * just call {@link #enqueue}, which does it already): those fields come back with {@code &}
 * HTML-escaped as {@code &amp;}, and left escaped the CDN signature no longer matches and the
 * request 403s.
 *
 * <p>Deliberately does <b>not</b> handle {@code v.redd.it}: those are DASH manifests, not a
 * single downloadable file, and handing one to {@code DownloadManager} produces a corrupt,
 * unplayable file. Route {@code is_video} / DASH posts to {@link WebViewMediaHandler} for the
 * user to save manually instead.
 *
 * <p>Note: this app's actual existing download pipeline ({@code DownloadMediaService}) does not
 * use {@link DownloadManager} at all -- it performs its own OkHttp-based download. This class
 * exists as the spec calls for it, but is not wired into that existing pipeline; see the
 * disclosure given to the user before this was built.
 */
public final class CookieDownloadManager {
    private CookieDownloadManager() {
    }

    public static long enqueue(Context context, String url, String fileName, @Nullable String description) {
        String cleanUrl = unescapeAmpersand(url);

        SharedPreferences prefs = context.getSharedPreferences(
                CookieAuthConstants.COOKIE_PREFS_NAME, Context.MODE_PRIVATE);
        String cookie = prefs.getString(CookieAuthConstants.COOKIE_PREFS_KEY, null);

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(cleanUrl))
                .setTitle(fileName)
                .setDescription(description == null ? "" : description)
                .addRequestHeader("User-Agent", CookieAuthConstants.CHROME_USER_AGENT)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

        if (cookie != null && !cookie.isEmpty()) {
            request.addRequestHeader("Cookie", cookie);
        }

        DownloadManager downloadManager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager == null) {
            return -1;
        }
        return downloadManager.enqueue(request);
    }

    /**
     * Reddit's JSON fields ({@code preview.images[].source.url}, {@code media_metadata.*},
     * {@code secure_media}) come back with {@code &} HTML-escaped as {@code &amp;}; left as-is,
     * the signed URL's signature no longer matches and the CDN 403s.
     */
    @Nullable
    public static String unescapeAmpersand(@Nullable String url) {
        return url == null ? null : url.replace("&amp;", "&");
    }
}
