package ml.docilealligator.infinityforreddit.cookieauth;

import androidx.annotation.Nullable;
import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/**
 * Retrofit interface for the cookie-session auth path, built on {@link CookieRetrofitClient}.
 * Kept separate from the app's existing (OAuth) {@code RedditAPI} interface since every call
 * here is routed through {@link RedditCookieInterceptor} instead of a bearer token.
 */
public interface CookieRedditAPI {
    /**
     * Same response shape as the existing OAuth {@code getSubscribedThing} call (Reddit's
     * subscribed-subreddits listing is identical either way), so the existing
     * SubredditDAO/adapter code that consumes it needs no changes. Paginate by feeding the
     * previous response's {@code data.after} back in as {@code after} until it comes back null.
     */
    @GET("subreddits/mine.json?limit=100")
    Call<String> getSubscribedSubreddits(@Query("after") @Nullable String after);
}
