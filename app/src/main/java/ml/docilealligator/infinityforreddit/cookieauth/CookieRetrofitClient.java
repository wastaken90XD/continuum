package ml.docilealligator.infinityforreddit.cookieauth;

import android.content.Context;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.scalars.ScalarsConverterFactory;

/**
 * Retrofit singleton for the cookie-session Reddit auth path. Returns raw JSON strings
 * ({@code ScalarsConverterFactory}, same as the app's existing {@code RedditAPI}) rather than
 * mapped POJOs, so the existing hand-written JSON parsing elsewhere in the app can be reused
 * unchanged.
 */
public final class CookieRetrofitClient {
    private static final String BASE_URL = "https://www.reddit.com/";

    private static volatile Retrofit instance;

    private CookieRetrofitClient() {
    }

    public static Retrofit getInstance(Context context) {
        Retrofit result = instance;
        if (result == null) {
            synchronized (CookieRetrofitClient.class) {
                result = instance;
                if (result == null) {
                    instance = result = build(context);
                }
            }
        }
        return result;
    }

    /**
     * Drops the cached client so the next {@link #getInstance} call rebuilds it -- needed after
     * {@link CookieLoginActivity} saves a fresh cookie, since {@link RedditCookieInterceptor}
     * itself re-reads the cookie per-request but a stale 401 shouldn't linger as "the" answer
     * for a request already in flight when the user finishes logging back in.
     */
    public static synchronized void invalidate() {
        instance = null;
    }

    private static Retrofit build(Context context) {
        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(new RedditCookieInterceptor(context))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();

        return new Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(ScalarsConverterFactory.create())
                .build();
    }
}
