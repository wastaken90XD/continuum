package ml.docilealligator.infinityforreddit.cookieauth;

import android.content.Context;
import android.net.Uri;
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
 * Search via the <a href="https://4get.ca">4get.ca</a> meta-search API instead of Reddit's own
 * {@code /search.json}, which frequently captchas anonymous/automated-looking traffic.
 * {@link #BASE_URL} is swappable for a self-hosted <a
 * href="https://github.com/luuul/4get">luuul/4get</a> Docker instance.
 *
 * <p>Falls back to Reddit's own {@code /search.json} (via the cookie-session auth path, see
 * {@link RedditCookieInterceptor}) on a captcha/error response or an empty result set, and
 * reports {@link Callback#onError} if that fails too. Results from either path are normalized to
 * the same {@code {"url": ..., "title": ...}} shape so callers don't need to branch on which path
 * answered.
 */
public final class FourGetSearchClient {
    /** Swappable for a self-hosted instance. */
    public static String BASE_URL = "https://4get.ca";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private FourGetSearchClient() {
    }

    public interface Callback {
        void onResults(JSONArray results);

        /** A captcha challenge was hit; {@code captchaUrl} is where the user can solve it. */
        void onCaptchaRequired(String captchaUrl);

        void onError(Exception e);
    }

    public static void search(Context context, String query, @Nullable String subreddit,
                               @Nullable String user, Callback callback) {
        String scope;
        if (subreddit != null && !subreddit.isEmpty()) {
            scope = "site:reddit.com/r/" + subreddit;
        } else if (user != null && !user.isEmpty()) {
            scope = "site:reddit.com/user/" + user;
        } else {
            scope = "site:reddit.com";
        }

        String encodedQuery = urlEncode(scope + " " + query);
        String url = BASE_URL + "/api/v1/web?s=" + encodedQuery + "&lang=en";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", CookieAuthConstants.CHROME_USER_AGENT)
                .build();

        CLIENT.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                fallbackToRedditSearch(context, query, subreddit, callback);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (r.code() == 403 || r.code() == 429) {
                        callback.onCaptchaRequired(BASE_URL);
                        return;
                    }

                    if (!r.isSuccessful() || r.body() == null) {
                        fallbackToRedditSearch(context, query, subreddit, callback);
                        return;
                    }

                    JSONObject json = new JSONObject(r.body().string());

                    if (json.has("error")) {
                        fallbackToRedditSearch(context, query, subreddit, callback);
                        return;
                    }

                    JSONArray web = json.optJSONArray("web");
                    if (web == null || web.length() == 0) {
                        fallbackToRedditSearch(context, query, subreddit, callback);
                        return;
                    }

                    callback.onResults(rewriteToReddit(web));
                } catch (JSONException | IOException e) {
                    fallbackToRedditSearch(context, query, subreddit, callback);
                }
            }
        });
    }

    private static JSONArray rewriteToReddit(JSONArray web) throws JSONException {
        JSONArray rewritten = new JSONArray();
        for (int i = 0; i < web.length(); i++) {
            JSONObject item = web.getJSONObject(i);
            String url = item.optString("url", null);
            if (url != null) {
                Uri rewrittenUri = Uri.parse(url).buildUpon().authority("www.reddit.com").build();
                item.put("url", rewrittenUri.toString());
            }
            rewritten.put(item);
        }
        return rewritten;
    }

    /** Falls back to Reddit's own search, through the cookie-session auth path. */
    private static void fallbackToRedditSearch(Context context, String query,
                                                @Nullable String subreddit, Callback callback) {
        String path = (subreddit != null && !subreddit.isEmpty())
                ? "r/" + subreddit + "/search.json?q=" + urlEncode(query) + "&restrict_sr=1"
                : "search.json?q=" + urlEncode(query);

        OkHttpClient fallbackClient = new OkHttpClient.Builder()
                .addInterceptor(new RedditCookieInterceptor(context))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();

        Request request = new Request.Builder()
                .url("https://www.reddit.com/" + path)
                .build();

        fallbackClient.newCall(request).enqueue(new okhttp3.Callback() {
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
