package ml.docilealligator.infinityforreddit.cookieauth;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils;
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
 * return HTML-200 instead of JSON), and normalizes GET paths to Reddit's {@code .json} API
 * convention.
 *
 * <p>This is the app's real, primary authentication path (per the Continuum spec, "OAuth
 * replaced entirely by a browser cookie session") -- not an optional add-on. It is installed on
 * the {@code @Named("default")} client in {@code NetworkModule}, which every
 * {@code @Named("oauth")}-suffixed {@link ml.docilealligator.infinityforreddit.apis.RedditAPI}
 * call already routes through, so existing call sites did not need to change: they still build an
 * (now-unused) {@code Authorization} header, which this interceptor strips and replaces with the
 * session cookie.
 *
 * <p>Impersonating a logged-in browser session like this is against Reddit's Terms of Service for
 * anything other than an actual browser -- see the disclosure given to the user before this was
 * made the app's primary auth path.
 */
public class RedditCookieInterceptor implements Interceptor {
    /**
     * Matches a cached {@code "modhash":"..."} field in a listing response body -- Reddit's CSRF
     * token for write requests (vote, save, comment, submit, subscribe). Avoids a full JSON parse
     * on every GET response just to pull one field back out for later POSTs.
     */
    private static final Pattern MODHASH_PATTERN = Pattern.compile("\"modhash\"\\s*:\\s*\"([^\"]*)\"");

    @Nullable
    private final Context appContext;
    @Nullable
    private final SharedPreferences currentAccountSharedPreferences;
    @Nullable
    private final String explicitCookie;

    /**
     * Reads the saved cookie for whichever account is current (via {@code currentAccountSharedPreferences},
     * the same file {@link ml.docilealligator.infinityforreddit.asynctasks.AccountManagement#switchAccount}
     * keeps in sync with the accounts table) every time a request is made, so an account switch is
     * picked up on the very next call without rebuilding any client.
     */
    public RedditCookieInterceptor(Context context, SharedPreferences currentAccountSharedPreferences) {
        this.appContext = context.getApplicationContext();
        this.currentAccountSharedPreferences = currentAccountSharedPreferences;
        this.explicitCookie = null;
    }

    /**
     * Pins the request to one specific account's cookie regardless of which account is current --
     * for the few call sites (commenting/subscribing/submitting as a non-current account in a
     * multi-account flow) that already resolve a specific {@code Account} before building their own
     * one-off client.
     */
    public RedditCookieInterceptor(String explicitCookie) {
        this.appContext = null;
        this.currentAccountSharedPreferences = null;
        this.explicitCookie = explicitCookie;
    }

    @NonNull
    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        Request original = chain.request();
        HttpUrl originalUrl = original.url();
        boolean isGet = "GET".equalsIgnoreCase(original.method());

        String encodedPath = originalUrl.encodedPath();
        if (isGet) {
            // Only GET "page" fetches use the .json content-negotiation suffix. POST /api/*
            // actions (vote, save, comment, submit, subscribe, ...) are RPC-style routes that
            // already return JSON on their own -- appending .json to one of those changes the
            // route and breaks the action instead of just asking for a different format.
            if (encodedPath.endsWith("/")) {
                encodedPath = encodedPath.substring(0, encodedPath.length() - 1);
            }
            if (!encodedPath.endsWith(".json")) {
                encodedPath = encodedPath + ".json";
            }
        }

        HttpUrl newUrl = originalUrl.newBuilder()
                .host("www.reddit.com")
                .encodedPath(encodedPath)
                .addQueryParameter("over18", "1")
                .build();

        Request.Builder requestBuilder = original.newBuilder()
                .url(newUrl)
                // Existing call sites still attach their own OAuth-shaped Authorization header
                // (built before this interceptor ever ran) -- drop it so a stale/meaningless
                // bearer token is never sent alongside the real session cookie.
                .removeHeader("Authorization")
                .header("User-Agent", CookieAuthConstants.CHROME_USER_AGENT);

        String cookie = getCookie();
        if (cookie != null && !cookie.isEmpty()) {
            requestBuilder.header("Cookie", cookie);
        }

        String modhash = ModhashHolder.get();
        if (!isGet && modhash != null && !modhash.isEmpty() && original.header("X-Modhash") == null) {
            requestBuilder.header("X-Modhash", modhash);
        }

        Response response = chain.proceed(requestBuilder.build());

        String contentType = response.header("Content-Type", "");
        if (contentType == null || !contentType.contains("json")) {
            // Reddit served something other than JSON -- almost always an HTML login/interstitial
            // page, meaning the cookie is missing or expired. Synthesize a 401 here so callers get
            // one consistent "needs re-login" signal instead of trying (and failing) to JSON-parse
            // an HTML page further downstream.
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

        if (isGet) {
            response = cacheModhashIfPresent(response);
        }

        return response;
    }

    /**
     * Peeks a GET listing response for {@code data.modhash} and caches it in memory for the next
     * write request, without disturbing the body for the real caller downstream.
     */
    private Response cacheModhashIfPresent(Response response) throws IOException {
        ResponseBody body = response.body();
        if (body == null) {
            return response;
        }
        // peek() avoids consuming the real body stream -- the caller downstream still reads it
        // fresh. request() buffers up to the cap without throwing if the body is smaller than
        // that (unlike BufferedSource#readString(byteCount, charset), which demands exactly that
        // many bytes); it just returns false and leaves whatever it did read in the buffer.
        okio.BufferedSource peekedSource = body.source().peek();
        peekedSource.request(32 * 1024);
        String peeked = peekedSource.buffer().readString(java.nio.charset.StandardCharsets.UTF_8);
        if (peeked.contains("\"modhash\"")) {
            Matcher m = MODHASH_PATTERN.matcher(peeked);
            if (m.find()) {
                String value = m.group(1);
                if (value != null && !value.isEmpty()) {
                    ModhashHolder.set(value);
                }
            }
        }
        return response;
    }

    @Nullable
    private String getCookie() {
        if (explicitCookie != null) {
            return explicitCookie;
        }
        if (currentAccountSharedPreferences != null) {
            String fromAccount = currentAccountSharedPreferences.getString(SharedPreferencesUtils.ACCESS_TOKEN, null);
            if (fromAccount != null && !fromAccount.isEmpty()) {
                return fromAccount;
            }
        }
        if (appContext != null) {
            // Legacy standalone slot, kept for the download/media-WebView helpers that read it
            // directly; AccountManagement.switchAccount keeps it mirrored to the current account.
            SharedPreferences prefs = appContext.getSharedPreferences(
                    CookieAuthConstants.COOKIE_PREFS_NAME, Context.MODE_PRIVATE);
            return prefs.getString(CookieAuthConstants.COOKIE_PREFS_KEY, null);
        }
        return null;
    }
}
