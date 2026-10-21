package ml.docilealligator.infinityforreddit.cookieauth;

import android.content.Context;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Search via Reddit's own {@code /search.json}, through the cookie-session auth path. This is
 * the one engine of the three that is Reddit's native search (relevance ranking, flair/time
 * filters honored, etc.) rather than a third-party web index filtered to reddit.com -- at the
 * cost of being the one most likely to hit a captcha or rate limit, which is the whole reason the
 * other two backends exist.
 */
public class RedditNativeSearchClient implements SearchBackend {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    @Override
    public void search(Context context, String query, @Nullable String subreddit,
                        @Nullable String user, SearchCallback callback) {
        String path = (subreddit != null && !subreddit.isEmpty())
                ? "r/" + subreddit + "/search.json?q=" + urlEncode(query) + "&restrict_sr=1"
                : "search.json?q=" + urlEncode(query);

        OkHttpClient client = CLIENT.newBuilder()
                .addInterceptor(new RedditCookieInterceptor(context))
                .build();

        Request request = new Request.Builder().url("https://www.reddit.com/" + path).build();

        client.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful() || r.body() == null) {
                        callback.onError(new IOException("Reddit search.json HTTP " + r.code()));
                        return;
                    }

                    JSONObject json = new JSONObject(r.body().string());
                    JSONArray children = json.getJSONObject("data").getJSONArray("children");

                    JSONArray results = new JSONArray();
                    for (int i = 0; i < children.length(); i++) {
                        JSONObject postData = children.getJSONObject(i).getJSONObject("data");
                        JSONObject normalized = new JSONObject();
                        normalized.put("url", "https://www.reddit.com" + postData.optString("permalink", ""));
                        normalized.put("title", postData.optString("title", ""));
                        normalized.put("description", postData.optString("selftext", ""));
                        results.put(normalized);
                    }

                    callback.onResults(results);
                } catch (JSONException | IOException e) {
                    callback.onError(e);
                }
            }
        });
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }
}
