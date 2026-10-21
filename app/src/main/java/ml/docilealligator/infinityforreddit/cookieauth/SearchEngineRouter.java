package ml.docilealligator.infinityforreddit.cookieauth;

import android.content.Context;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.json.JSONArray;

/**
 * "Query designer": picks which {@link SearchBackend} actually runs a search, falling back to a
 * second one on error/captcha/empty results, per {@link SearchEngineSettings}. Also the one-call
 * "search, then fetch full post JSON for every result" pipeline described in
 * {@link FourGetSearchClient}'s class doc -- no post-ID resolution step, just
 * {@code <result url>.json} through the cookie-session auth path.
 */
public final class SearchEngineRouter {
    /** Matches a Reddit post permalink -- the shape {@code .json} can be appended to directly. */
    private static final Pattern POST_PERMALINK = Pattern.compile("reddit\\.com/r/[^/]+/comments/[^/]+");

    private SearchEngineRouter() {
    }

    public static boolean isPostPermalink(@Nullable String url) {
        return url != null && POST_PERMALINK.matcher(url).find();
    }

    /** Runs a search with the configured primary engine, falling back once on failure. */
    public static void search(Context context, String query, @Nullable String subreddit,
                               @Nullable String user, SearchCallback callback) {
        searchWithEngine(context, SearchEngineSettings.getPrimaryEngine(context), query, subreddit,
                user, callback, true);
    }

    public interface PostResultsCallback {
        /** Raw per-post JSON (the {@code <permalink>.json} body), one entry per resolved post. */
        void onPosts(JSONArray postJsonArray);

        void onCaptchaRequired(String captchaUrl);

        void onError(Exception e);
    }

    /**
     * The full "search, then just fetch {@code .json} for every result" pipeline: runs
     * {@link #search}, keeps only results that look like Reddit post permalinks, and fetches
     * each one's full post + comments JSON via {@link CookieRedditPostFetcher}. A result that
     * fails to resolve is dropped rather than failing the whole search; {@link PostResultsCallback#onError}
     * only fires if every result failed (or there were no post-shaped results to begin with).
     */
    public static void searchRedditPosts(Context context, String query, @Nullable String subreddit,
                                          @Nullable String user, PostResultsCallback callback) {
        search(context, query, subreddit, user, new SearchCallback() {
            @Override
            public void onResults(JSONArray results) {
                List<String> postUrls = new ArrayList<>();
                for (int i = 0; i < results.length(); i++) {
                    String url = results.optJSONObject(i) == null ? null : results.optJSONObject(i).optString("url", null);
                    if (isPostPermalink(url)) {
                        postUrls.add(url);
                    }
                }

                if (postUrls.isEmpty()) {
                    callback.onError(new IOException("No Reddit post results in this search"));
                    return;
                }

                resolveAll(context, postUrls, callback);
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

    private static void resolveAll(Context context, List<String> urls, PostResultsCallback callback) {
        JSONArray postJsonArray = new JSONArray();
        Object lock = new Object();
        AtomicInteger remaining = new AtomicInteger(urls.size());

        for (String url : urls) {
            CookieRedditPostFetcher.fetchPostJson(context, url, new CookieRedditPostFetcher.Callback() {
                @Override
                public void onPostJson(String json) {
                    synchronized (lock) {
                        postJsonArray.put(json);
                    }
                    finishOne();
                }

                @Override
                public void onError(Exception e) {
                    // One bad post shouldn't sink the whole search -- just drop it.
                    finishOne();
                }

                private void finishOne() {
                    if (remaining.decrementAndGet() == 0) {
                        synchronized (lock) {
                            if (postJsonArray.length() == 0) {
                                callback.onError(new IOException("Every matched post failed to resolve"));
                            } else {
                                callback.onPosts(postJsonArray);
                            }
                        }
                    }
                }
            });
        }
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
