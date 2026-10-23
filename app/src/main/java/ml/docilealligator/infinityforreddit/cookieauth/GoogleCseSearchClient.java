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
 * Search via a user-configured Google Programmable Search Engine (CSE) as an alternative to
 * 4get -- Google's own infrastructure, no shared-instance rate limiting or captcha, but requires
 * the user to create their own CSE (restricted to reddit.com, or not) and API key at
 * <a href="https://programmablesearchengine.google.com">programmablesearchengine.google.com</a>,
 * which is free for up to 100 queries/day. See {@link SearchEngineSettings#isGoogleCseConfigured}.
 */
public class GoogleCseSearchClient implements SearchBackend {
    private static final String API_URL = "https://www.googleapis.com/customsearch/v1";

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    @Override
    public void search(Context context, String query, @Nullable String subreddit,
                        @Nullable String user, SearchCallback callback) {
        String apiKey = SearchEngineSettings.getGoogleCseApiKey(context);
        String cx = SearchEngineSettings.getGoogleCseCx(context);
        if (apiKey == null || apiKey.isEmpty() || cx == null || cx.isEmpty()) {
            callback.onError(new IllegalStateException(
                    "Google CSE is not configured -- set an API key and Search Engine ID in Search Engine settings"));
            return;
        }

        String scope;
        if (subreddit != null && !subreddit.isEmpty()) {
            scope = "site:reddit.com/r/" + subreddit;
        } else if (user != null && !user.isEmpty()) {
            scope = "site:reddit.com/user/" + user;
        } else {
            scope = "site:reddit.com";
        }

        String url = API_URL + "?key=" + urlEncode(apiKey)
                + "&cx=" + urlEncode(cx)
                + "&q=" + urlEncode(scope + " " + query);

        Request request = new Request.Builder().url(url).build();

        CLIENT.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onError(e);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try (Response r = response) {
                    if (r.code() == 429) {
                        // Google CSE's free tier is 100 queries/day; this is that cap, not a
                        // captcha -- there is nothing a WebView can solve here.
                        callback.onError(new IOException("Google CSE daily query limit reached"));
                        return;
                    }

                    if (!r.isSuccessful() || r.body() == null) {
                        callback.onError(new IOException("Google CSE HTTP " + r.code()));
                        return;
                    }

                    JSONObject json = new JSONObject(r.body().string());

                    if (json.has("error")) {
                        callback.onError(new IOException("Google CSE error: "
                                + json.getJSONObject("error").optString("message", "unknown")));
                        return;
                    }

                    JSONArray items = json.optJSONArray("items");
                    if (items == null || items.length() == 0) {
                        callback.onError(new IOException("Google CSE returned no results"));
                        return;
                    }

                    callback.onResults(normalize(items));
                } catch (JSONException | IOException e) {
                    callback.onError(e);
                }
            }
        });
    }

    private static JSONArray normalize(JSONArray items) throws JSONException {
        JSONArray results = new JSONArray();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            String link = item.optString("link", null);
            JSONObject normalized = new JSONObject();
            if (link != null) {
                Uri uri = Uri.parse(link);
                String host = uri.getHost();
                if (host != null && host.endsWith("reddit.com")) {
                    link = uri.buildUpon().scheme("https").authority("www.reddit.com").build().toString();
                }
            }
            normalized.put("url", link);
            normalized.put("title", item.optString("title", ""));
            normalized.put("description", item.optString("snippet", ""));
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
