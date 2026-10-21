package ml.docilealligator.infinityforreddit.cookieauth;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.IOException;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * OkHttp interceptor for the cookie-session Reddit auth path: attaches the saved browser session
 * cookie (captured by {@link CookieLoginActivity}) and a Chrome user agent to every request,
 * forces the request onto {@code www.reddit.com} (old.reddit.com can silently drop cookies or
 * return HTML-200 instead of JSON), and normalizes the path to Reddit's {@code .json} API
 * convention.
 *
 * <p>Unlike the app's existing {@code AnonymousAccessTokenInterceptor} (a legitimate,
 * ToS-compliant app-only OAuth token), this impersonates a logged-in browser session. It is kept
 * as an opt-in, additive path rather than the default -- see the disclosure given to the user
 * before this was built.
 */
public class RedditCookieInterceptor implements Interceptor {
    private final Context appContext;

    public RedditCookieInterceptor(Context context) {
        this.appContext = context.getApplicationContext();
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request original = chain.request();
        HttpUrl originalUrl = original.url();

        String encodedPath = originalUrl.encodedPath();
        if (encodedPath.endsWith("/")) {
            encodedPath = encodedPath.substring(0, encodedPath.length() - 1);
        }
        if (!encodedPath.endsWith(".json")) {
            encodedPath = encodedPath + ".json";
        }

        HttpUrl newUrl = originalUrl.newBuilder()
                .host("www.reddit.com")
                .encodedPath(encodedPath)
                .addQueryParameter("over18", "1")
                .build();

        Request.Builder requestBuilder = original.newBuilder()
                .url(newUrl)
                .header("User-Agent", CookieAuthConstants.CHROME_USER_AGENT);

        String cookie = getSavedCookie();
        if (cookie != null && !cookie.isEmpty()) {
            requestBuilder.header("Cookie", cookie);
        }

        Response response = chain.proceed(requestBuilder.build());

        String contentType = response.header("Content-Type");
        if (contentType != null && !contentType.contains("json")) {
            // Reddit served something other than JSON -- almost always an HTML login/interstitial
            // page, meaning the saved cookie is missing or expired. Synthesize a 401 here so
            // callers get one consistent "needs re-login" signal instead of trying (and failing)
            // to JSON-parse an HTML page further downstream.
            ResponseBody body = response.body();
            if (body != null) {
                body.close();
            }
            return response.newBuilder()
                    .code(401)
                    .message("Unauthorized (non-JSON response; cookie likely missing or expired)")
                    .body(ResponseBody.create("", MediaType.parse("application/json")))
                    .build();
        }

        return response;
    }

    @Nullable
    private String getSavedCookie() {
        SharedPreferences prefs = appContext.getSharedPreferences(
                CookieAuthConstants.COOKIE_PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(CookieAuthConstants.COOKIE_PREFS_KEY, null);
    }
}
