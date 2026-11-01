package ml.docilealligator.infinityforreddit.webclient;

import android.app.DownloadManager;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaScannerConnection;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * Native side of {@code reddit_web_enhancements.js}'s download menu. Deliberately re-validates
 * every URL itself rather than trusting the JS side -- the JS layer already checks this, but
 * {@code @JavascriptInterface} methods are reachable from any script that ends up running in this
 * WebView, so the native boundary has to hold even if the page-side check is ever bypassed.
 *
 * <p>Three download paths, matching the three menu shapes the JS side builds:
 * <ul>
 *   <li>{@link #downloadMedia} -- the original single-URL path (plain image, or "video only, no
 *       audio" when the DASH manifest can't be read): one file, enqueued through
 *       {@link DownloadManager} exactly as before.</li>
 *   <li>{@link #downloadVideoMuxed} -- downloads a v.redd.it video-only stream and its matching
 *       audio-only stream (both URLs come from the DASH manifest the JS side already parsed), then
 *       merges them into one playable file using {@link MediaMuxer}/{@link MediaExtractor}. These
 *       are framework classes available since API 16/18, well under this app's minSdk 21, so this
 *       needs no new dependency and no ffmpeg binary. <b>This mux path hasn't been exercised on a
 *       real device from this sandbox</b> (no emulator available here) -- the sample-copy loop
 *       below follows the documented MediaExtractor-&gt;MediaMuxer pattern exactly, and every step
 *       is wrapped so a failure anywhere (bad stream, unexpected codec, truncated download) falls
 *       back to the plain video-only download instead of producing a silently broken file.</li>
 *   <li>{@link #downloadGalleryAll} -- fans out to {@link #downloadMedia} once per image in a
 *       gallery post, for the "download all" menu option.</li>
 * </ul>
 */
public class RedditDownloadBridge {
    private static final String TAG = "RedditDownloadBridge";

    /** Reddit's own media CDNs only -- never an arbitrary third party, regardless of what the page sends. */
    private static final Set<String> ALLOWED_HOSTS = new HashSet<>(Arrays.asList(
            "i.redd.it", "preview.redd.it", "external-preview.redd.it", "v.redd.it"));

    /** Sample buffer for the mux copy loop. Generous relative to v.redd.it's web-tier bitrates, to
     *  avoid truncating a keyframe -- MediaExtractor has no "buffer too small, try again" signal. */
    private static final int MUX_SAMPLE_BUFFER_SIZE = 4 * 1024 * 1024;

    private final Context appContext;
    private final Handler mainHandler;
    private final ExecutorService muxExecutor = Executors.newSingleThreadExecutor();

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
        downloadMediaInternal(url, filename, typeHint, true);
    }

    private void downloadMediaInternal(String url, @Nullable String filename, @Nullable String typeHint,
                                        boolean showOwnToast) {
        if (!isAllowedMediaUrl(url)) {
            Log.w(TAG, "Blocked download of disallowed URL: " + url);
            if (showOwnToast) {
                showToast("Can't download: not a recognized Reddit media URL");
            }
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
                if (showOwnToast) {
                    showToast("Downloads aren't available on this device");
                }
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
            if (showOwnToast) {
                showToast(isVideo ? "Downloading video (without audio)\u2026" : "Downloading image\u2026");
            }
        } catch (Exception e) {
            Log.e(TAG, "Download enqueue failed for " + url, e);
            if (showOwnToast) {
                showToast("Download failed to start");
            }
        }
    }

    /**
     * Downloads a gallery post's images in one go, from the JS side's "download all" menu item.
     *
     * @param urlsJson     JSON array of https media URLs (already filtered by the JS side, but
     *                     re-validated here per {@link #isAllowedMediaUrl})
     * @param baseFilename common filename stem; each image gets a "_1", "_2", ... suffix
     */
    @JavascriptInterface
    public void downloadGalleryAll(String urlsJson, @Nullable String baseFilename) {
        JSONArray urls;
        try {
            urls = new JSONArray(urlsJson);
        } catch (JSONException e) {
            Log.w(TAG, "Malformed gallery URL list", e);
            showToast("Couldn't read this gallery's image list");
            return;
        }

        String base = sanitizeFilename(baseFilename);
        int queued = 0;
        for (int i = 0; i < urls.length(); i++) {
            String url = urls.optString(i, null);
            if (url == null || !isAllowedMediaUrl(url)) {
                continue;
            }
            String extension = extensionFrom(url, ".jpg");
            downloadMediaInternal(url, base + "_" + (i + 1) + extension, "image", false);
            queued++;
        }

        if (queued == 0) {
            showToast("No downloadable images found in this gallery");
        } else {
            showToast("Downloading " + queued + " gallery image" + (queued == 1 ? "" : "s") + "\u2026");
        }
    }

    /**
     * Downloads a v.redd.it video-only stream and its matching audio-only stream, then merges them
     * into a single MP4 with {@link MediaMuxer}. Both URLs are expected to come from the DASH
     * manifest the JS side already parsed (see {@code fetchVideoQualityOptions} in
     * {@code reddit_web_enhancements.js}), but are re-validated here regardless.
     *
     * <p>Falls back to a plain video-only download (same as {@link #downloadMedia}) if anything
     * about the merge fails, so a bad stream or unexpected format degrades to the old v1 behavior
     * instead of leaving the user with nothing, or a silently corrupt file.
     */
    @JavascriptInterface
    public void downloadVideoMuxed(String videoUrl, String audioUrl, @Nullable String filename) {
        if (!isAllowedMediaUrl(videoUrl) || !isAllowedMediaUrl(audioUrl)) {
            Log.w(TAG, "Blocked muxed download: video=" + videoUrl + " audio=" + audioUrl);
            showToast("Can't download: not a recognized Reddit media URL");
            return;
        }

        String safeName = sanitizeFilename(filename);
        if (!safeName.toLowerCase(Locale.US).endsWith(".mp4")) {
            safeName = safeName + ".mp4";
        }
        final String outputName = safeName;

        showToast("Downloading video with audio\u2026");
        muxExecutor.execute(() -> runMuxDownload(videoUrl, audioUrl, outputName));
    }

    /** JS-side logging/diagnostics hook, kept from before the download menu existed. */
    @JavascriptInterface
    public void log(String message) {
        Log.d(TAG, "[page] " + message);
    }

    /** Lets the JS side (e.g. "copy link") show a native toast without a dedicated bridge method per action. */
    @JavascriptInterface
    public void toast(String message) {
        if (message != null && !message.isEmpty() && message.length() < 500) {
            showToast(message);
        }
    }

    private void runMuxDownload(String videoUrl, String audioUrl, String outputName) {
        File videoTemp = null;
        File audioTemp = null;
        File outputFile = null;
        boolean muxed = false;
        try {
            videoTemp = File.createTempFile("continuum_v_", ".mp4", appContext.getCacheDir());
            audioTemp = File.createTempFile("continuum_a_", ".mp4", appContext.getCacheDir());

            downloadToFile(videoUrl, videoTemp);
            downloadToFile(audioUrl, audioTemp);

            outputFile = uniqueFile(new File(resolveMuxOutputDir(), outputName));
            muxTracks(videoTemp, audioTemp, outputFile);
            muxed = true;

            MediaScannerConnection.scanFile(
                    appContext, new String[]{outputFile.getAbsolutePath()}, null, null);
            showToast("Saved with audio: " + outputFile.getName());
        } catch (Exception e) {
            Log.e(TAG, "Muxed download failed for " + videoUrl, e);
            if (outputFile != null && outputFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                outputFile.delete();
            }
            showToast("Couldn't add audio \u2014 downloading video only instead\u2026");
        } finally {
            deleteQuietly(videoTemp);
            deleteQuietly(audioTemp);
        }

        if (!muxed) {
            // Fail safe: fall back to the simpler, already-proven single-stream path rather than
            // leaving the user with nothing.
            downloadMediaInternal(videoUrl, outputName, "video", false);
        }
    }

    private void downloadToFile(String urlString, File dest) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        try {
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Referer", "https://www.reddit.com/");
            String cookie = CookieManager.getInstance().getCookie("https://www.reddit.com");
            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }

            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " downloading " + urlString);
            }

            try (InputStream in = connection.getInputStream();
                 OutputStream out = new FileOutputStream(dest)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Copies the video track from {@code videoFile} and the audio track from {@code audioFile}
     * into one MP4 at {@code outputFile}, using {@link MediaMuxer}. This is a container-level copy
     * (no re-encoding), matching the same "mux, don't transcode" approach every desktop tool in
     * this feature's research (yt-dlp, gallery-dl, manual ffmpeg) uses for v.redd.it.
     */
    private void muxTracks(File videoFile, File audioFile, File outputFile) throws IOException {
        MediaExtractor videoExtractor = new MediaExtractor();
        MediaExtractor audioExtractor = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            videoExtractor.setDataSource(videoFile.getAbsolutePath());
            audioExtractor.setDataSource(audioFile.getAbsolutePath());

            int videoTrackIndex = selectTrack(videoExtractor, "video/");
            if (videoTrackIndex < 0) {
                throw new IOException("No video track in downloaded video stream");
            }
            // Require a real audio track rather than silently falling back to a video-only mux --
            // this method's entire purpose is guaranteeing audio, so a missing track here should
            // surface as a failure (and fall back to the plain video-only download, see the caller)
            // rather than succeed with a file that quietly has no sound.
            int audioTrackIndex = selectTrack(audioExtractor, "audio/");
            if (audioTrackIndex < 0) {
                throw new IOException("No audio track in downloaded audio stream");
            }

            muxer = new MediaMuxer(outputFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            videoExtractor.selectTrack(videoTrackIndex);
            int muxVideoTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoTrackIndex));

            audioExtractor.selectTrack(audioTrackIndex);
            int muxAudioTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioTrackIndex));

            muxer.start();
            started = true;

            copySamples(videoExtractor, muxer, muxVideoTrack);
            copySamples(audioExtractor, muxer, muxAudioTrack);
        } finally {
            videoExtractor.release();
            audioExtractor.release();
            if (muxer != null) {
                if (started) {
                    muxer.stop();
                }
                muxer.release();
            }
        }
    }

    private static int selectTrack(MediaExtractor extractor, String mimePrefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(mimePrefix)) {
                return i;
            }
        }
        return -1;
    }

    private static void copySamples(MediaExtractor extractor, MediaMuxer muxer, int muxTrackIndex) {
        ByteBuffer buffer = ByteBuffer.allocate(MUX_SAMPLE_BUFFER_SIZE);
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while (true) {
            buffer.clear();
            int sampleSize = extractor.readSampleData(buffer, 0);
            if (sampleSize < 0) {
                break;
            }
            bufferInfo.offset = 0;
            bufferInfo.size = sampleSize;
            bufferInfo.presentationTimeUs = extractor.getSampleTime();
            bufferInfo.flags = extractor.getSampleFlags();
            muxer.writeSampleData(muxTrackIndex, buffer, bufferInfo);
            extractor.advance();
        }
    }

    /**
     * Where the muxed MP4 is written. Deliberately the app's own external files directory (never
     * needs a storage permission on any API level, unlike a plain public Downloads path) rather
     * than the public Downloads folder {@link #downloadMediaInternal} uses via
     * {@link DownloadManager} -- DownloadManager writes to the public folder under its own
     * privileges, but a direct {@link java.io.File} write from this app would need the
     * WRITE_EXTERNAL_STORAGE permission actually granted at runtime on API 23-28, which this screen
     * has no request flow for. {@link MediaScannerConnection#scanFile} still indexes the result so
     * gallery/file-manager apps that show per-app folders can find it.
     */
    private File resolveMuxOutputDir() {
        File dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = new File(appContext.getFilesDir(), "Download");
        }
        File subdir = new File(dir, "reddit-posts");
        if (!subdir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            subdir.mkdirs();
        }
        return subdir;
    }

    private static File uniqueFile(File file) {
        if (!file.exists()) {
            return file;
        }
        String name = file.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        File parent = file.getParentFile();
        for (int counter = 1; counter < 1000; counter++) {
            File candidate = new File(parent, base + "_" + counter + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return file;
    }

    private static void deleteQuietly(@Nullable File file) {
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
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

    private static String extensionFrom(String url, String fallback) {
        int queryIndex = url.indexOf('?');
        String path = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
        int dot = path.lastIndexOf('.');
        int slash = path.lastIndexOf('/');
        if (dot > slash) {
            return path.substring(dot);
        }
        return fallback;
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
