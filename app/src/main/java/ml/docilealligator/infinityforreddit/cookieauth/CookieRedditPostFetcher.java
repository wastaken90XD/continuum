package ml.docilealligator.infinityforreddit.cookieauth;

import android.content.Context;
import java.io.IOException;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Resolves a Reddit post permalink (as returned by any {@link SearchBackend}) into that post's
 * full JSON, through the cookie-session auth path. This is the simplification that makes
 * third-party-indexed search practical: no post-ID lookup step is needed. A search result's URL
 * already <i>is</i> the post's URL -- {@link RedditCookieInterceptor} appends {@code .json} and
 * attaches the session cookie for whatever URL it's given.
 */
public final class CookieRedditPostFetcher {
    private CookieRedditPostFetcher() {
    }

    public interface Callback {
        void onPostJson(String json);

        void onError(Exception e);
    }

    public static void fetchPostJson(Context context, String postUrl, Callback callback) {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new RedditCookieInterceptor(context))
                .build();

        Request request;
        try {
            request = new Request.Builder().url(withScheme(postUrl)).build();
        } catch (IllegalArgumentException e) {
            callback.onError(e);
            return;
        }

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        callback.onError(new IOException("HTTP " + r.code() + " fetching " + postUrl));
                        return;
                    }
                    callback.onPostJson(r.body().string());
                } catch (IOException e) {
                    callback.onError(e);
                }
            }
        });
    }

    private static String withScheme(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return "https://" + url;
        }
        return url;
    }
}
