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
 * {@code /search.json}, which frequently captchas anonymous/automated-looking traffic. The
 * instance URL is user-configurable (see {@link SearchEngineSettings#getFourGetInstanceUrl}) so
 * this can point at a self-hosted <a href="https://github.com/luuul/4get">luuul/4get</a> Docker
 * instance instead of the public one.
 *
 * <p>4get's {@code web[]} results already carry the real destination URL -- for a
 * {@code site:reddit.com} query that is almost always a direct Reddit permalink. There is no
 * post-ID resolution step needed: {@link SearchEngineRouter#searchRedditPosts} takes that URL
 * and fetches {@code <url>.json} straight through the cookie-session auth path
 * ({@link RedditCookieInterceptor} appends the {@code .json} itself), which is the full post +
 * comments payload, same shape as the app's existing {@code /comments/{id}.json} parsing
 * expects. This sidesteps Reddit's own search endpoint (and its rate limits/captchas) entirely
 * for the discovery step; the per-post {@code .json} fetches afterwards still count against the
 * normal Reddit request budget like any other page view would.
 */
public class FourGetSearchClient implements SearchBackend {
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    @Override
    public void search(Context context, String query, @Nullable String subreddit,
                        @Nullable String user, SearchCallback callback) {
        String scope;
        if (subreddit != null && !subreddit.isEmpty()) {
            scope = "site:reddit.com/r/" + subreddit;
        } else if (user != null && !user.isEmpty()) {
            scope = "site:reddit.com/user/" + user;
        } else {
            scope = "site:reddit.com";
        }

        String encodedQuery = urlEncode(scope + " " + query);
        String instanceUrl = SearchEngineSettings.getFourGetInstanceUrl(context);
        String url = instanceUrl + "/api/v1/web?s=" + encodedQuery + "&lang=en";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", CookieAuthConstants.CHROME_USER_AGENT)
                .build();

        CLIENT.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (r.code() == 403 || r.code() == 429) {
                        callback.onCaptchaRequired(instanceUrl);
                        return;
                    }

                    if (!r.isSuccessful() || r.body() == null) {
                        callback.onError(new IOException("4get HTTP " + r.code()));
                        return;
                    }

                    JSONObject json = new JSONObject(r.body().string());

                    if (json.has("error")) {
                        callback.onError(new IOException("4get error: " + json.optString("error")));
                        return;
                    }

                    JSONArray web = json.optJSONArray("web");
                    if (web == null || web.length() == 0) {
                        callback.onError(new IOException("4get returned no results"));
                        return;
                    }

                    callback.onResults(normalize(web));
                } catch (JSONException | IOException e) {
                    callback.onError(e);
                }
            }
        });
    }

    private static JSONArray normalize(JSONArray web) throws JSONException {
        JSONArray results = new JSONArray();
        for (int i = 0; i < web.length(); i++) {
            JSONObject item = web.getJSONObject(i);
            String url = item.optString("url", null);
            JSONObject normalized = new JSONObject();
            if (url != null) {
                // 4get's scrapers can occasionally surface a near-match outside reddit.com (an
                // archive/cache mirror, for instance) despite the site: filter; only rewrite
                // results that are actually on reddit.com, so anything else is left as-is rather
                // than being silently relabelled as a Reddit URL it isn't.
                Uri uri = Uri.parse(url);
                String host = uri.getHost();
                if (host != null && host.endsWith("reddit.com")) {
                    url = uri.buildUpon().scheme("https").authority("www.reddit.com").build().toString();
                }
            }
            normalized.put("url", url);
            normalized.put("title", item.optString("title", ""));
            normalized.put("description", item.optString("description", ""));
            results.put(normalized);
        }
        return results;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return value;
        }
    }
}
