package ml.docilealligator.infinityforreddit.cookieauth;

import android.content.Context;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import ml.docilealligator.infinityforreddit.apis.RedditAPI;
import ml.docilealligator.infinityforreddit.post.ParsePost;
import ml.docilealligator.infinityforreddit.post.Post;
import ml.docilealligator.infinityforreddit.postfilter.PostFilter;
import ml.docilealligator.infinityforreddit.readpost.NullReadPostsList;
import org.json.JSONArray;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * "Query designer": picks which {@link SearchBackend} actually runs a search, falling back to a
 * second one on error/captcha/empty results, per {@link SearchEngineSettings}.
 *
 * <p>{@link #searchRedditPosts} is the one-call "search, then turn the matching results into real
 * posts" pipeline: a 4get/Google CSE/Reddit-native search only ever needs to tell us <i>which</i>
 * Reddit posts matched -- it does not need its own post-fetching or JSON-parsing code, because the
 * app already has one: {@link RedditAPI#getInfo}, the same bulk by-fullname lookup
 * {@code HistoryPostPagingSource} uses to turn a bare list of post IDs into real
 * {@link Post} objects via {@link ParsePost#parsePostsSync}. This is that same lookup, run over
 * the cookie-session client instead of OAuth, so a search engine swap never means a second,
 * parallel way of turning a post ID into a {@link Post}.
 */
public final class SearchEngineRouter {
    /**
     * Matches a Reddit post permalink -- these are the only result URLs {@link #searchRedditPosts}
     * can do anything with -- capturing the post's base-36 id (e.g. {@code abc123} out of
     * {@code .../comments/abc123/some_title/}) needed for {@link RedditAPI#getInfo}'s
     * {@code t3_<id>} fullname.
     */
    private static final Pattern POST_PERMALINK = Pattern.compile("reddit\\.com/r/[^/]+/comments/([A-Za-z0-9]+)");

    private SearchEngineRouter() {
    }

    public static boolean isPostPermalink(@Nullable String url) {
        return url != null && POST_PERMALINK.matcher(url).find();
    }

    @Nullable
    private static String postIdFromPermalink(@Nullable String url) {
        if (url == null) {
            return null;
        }
        Matcher m = POST_PERMALINK.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    /** Runs a search with the configured primary engine, falling back once on failure. */
    public static void search(Context context, String query, @Nullable String subreddit,
                               @Nullable String user, SearchCallback callback) {
        searchWithEngine(context, SearchEngineSettings.getPrimaryEngine(context), query, subreddit,
                user, callback, true);
    }

    public interface PostResultsCallback {
        /** Real, fully-parsed posts -- ready for whatever already displays a {@link Post} list. */
        void onPosts(List<Post> posts);

        void onCaptchaRequired(String captchaUrl);

        void onError(Exception e);
    }

    /**
     * The full "search, then hand the matches to the app's existing post-fetching code" pipeline:
     * runs {@link #search}, keeps only results that look like Reddit post permalinks, and resolves
     * all of them in a single {@link RedditAPI#getInfo} call -- exactly the lookup
     * {@code HistoryPostPagingSource} already uses to turn a list of post ids into real posts, just
     * run over the cookie session instead of OAuth/anonymous. {@link PostResultsCallback#onError}
     * fires only if nothing in this search's results was a Reddit post permalink, or the lookup
     * itself failed.
     */
    public static void searchRedditPosts(Context context, String query, @Nullable String subreddit,
                                          @Nullable String user, PostResultsCallback callback) {
        search(context, query, subreddit, user, new SearchCallback() {
            @Override
            public void onResults(JSONArray results) {
                List<String> postIds = new ArrayList<>();
                for (int i = 0; i < results.length(); i++) {
                    String url = results.optJSONObject(i) == null ? null : results.optJSONObject(i).optString("url", null);
                    String id = postIdFromPermalink(url);
                    if (id != null) {
                        postIds.add(id);
                    }
                }

                if (postIds.isEmpty()) {
                    callback.onError(new IOException("No Reddit post results in this search"));
                    return;
                }

                resolvePosts(context, postIds, callback);
            }

            @Override
            public void onCaptchaRequired(String captchaUrl) {
                callback.onCaptchaRequired(captchaUrl);
            }

            @Override
            public void onError(Exception e) {
                callback.onError(e);
            }
        });
    }

    /**
     * Turns matched post ids into real {@link Post} objects via the app's existing bulk by-fullname
     * lookup ({@link RedditAPI#getInfo}, through the cookie-session Retrofit client), parsed with
     * the same {@link ParsePost#parsePostsSync} every other post listing in the app already uses.
     */
    private static void resolvePosts(Context context, List<String> postIds, PostResultsCallback callback) {
        StringBuilder fullnames = new StringBuilder();
        for (String id : postIds) {
            fullnames.append("t3_").append(id).append(',');
        }
        fullnames.setLength(fullnames.length() - 1);

        Call<String> call = CookieRetrofitClient.getInstance(context).create(RedditAPI.class).getInfo(fullnames.toString());
        call.enqueue(new Callback<String>() {
            @Override
            public void onResponse(Call<String> call, Response<String> response) {
                if (!response.isSuccessful()) {
                    callback.onError(new IOException("HTTP " + response.code() + " resolving search results"));
                    return;
                }

                LinkedHashSet<Post> posts = ParsePost.parsePostsSync(response.body(), -1, new PostFilter(),
                        NullReadPostsList.getInstance());
                if (posts == null) {
                    callback.onError(new IOException("Error parsing search results"));
                } else if (posts.isEmpty()) {
                    callback.onError(new IOException("None of the matched posts could be resolved"));
                } else {
                    callback.onPosts(new ArrayList<>(posts));
                }
            }

            @Override
            public void onFailure(Call<String> call, Throwable t) {
                callback.onError(t instanceof Exception ? (Exception) t : new IOException(t));
            }
        });
    }

    private static void searchWithEngine(Context context, SearchEngineSettings.SearchEngine engine,
                                          String query, @Nullable String subreddit, @Nullable String user,
                                          SearchCallback callback, boolean allowFallback) {
        SearchBackend backend = backendFor(engine);
        if (backend == null) {
            callback.onError(new IllegalStateException("No search engine configured"));
            return;
        }

        backend.search(context, query, subreddit, user, new SearchCallback() {
            @Override
            public void onResults(JSONArray results) {
                callback.onResults(results);
            }

            @Override
            public void onCaptchaRequired(String captchaUrl) {
                if (engine == SearchEngineSettings.SearchEngine.FOURGET
                        && SearchEngineSettings.consumeCaptchaBudgetSearch(context)) {
                    // A captcha was solved recently and the budget isn't spent yet -- treat this
                    // challenge as "4get is temporarily unavailable" and retry quietly through the
                    // fallback engine instead of nagging the user to solve another one so soon.
                    fallbackOrGiveUp(new IOException("4get captcha (within solved-captcha budget)"));
                } else {
                    callback.onCaptchaRequired(captchaUrl);
                }
            }

            @Override
            public void onError(Exception e) {
                fallbackOrGiveUp(e);
            }

            private void fallbackOrGiveUp(Exception cause) {
                if (!allowFallback) {
                    callback.onError(cause);
                    return;
                }
                SearchEngineSettings.SearchEngine fallback = SearchEngineSettings.getFallbackEngine(context);
                if (fallback == SearchEngineSettings.SearchEngine.NONE || fallback == engine) {
                    callback.onError(cause);
                    return;
                }
                // Only one hop: the fallback engine's own failure is reported as-is, not chased
                // further, so a misconfigured pair of engines fails fast instead of looping.
                searchWithEngine(context, fallback, query, subreddit, user, callback, false);
            }
        });
    }

    @Nullable
    private static SearchBackend backendFor(SearchEngineSettings.SearchEngine engine) {
        switch (engine) {
            case FOURGET:
                return new FourGetSearchClient();
            case GOOGLE_CSE:
                return new GoogleCseSearchClient();
            case REDDIT_NATIVE:
                return new RedditNativeSearchClient();
            default:
                return null;
        }
    }
}
