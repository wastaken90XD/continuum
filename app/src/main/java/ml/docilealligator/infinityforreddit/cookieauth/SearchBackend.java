package ml.docilealligator.infinityforreddit.cookieauth;

import android.content.Context;
import androidx.annotation.Nullable;

/**
 * One pluggable search engine for the "query designer" (see {@link SearchEngineSettings} and
 * {@link SearchEngineRouter}): 4get, Google Programmable Search (CSE), or Reddit's own
 * {@code /search.json}. All three return the same normalized result shape so the router -- and
 * anything consuming its results -- doesn't need to care which one actually answered.
 */
public interface SearchBackend {
    void search(Context context, String query, @Nullable String subreddit, @Nullable String user,
                SearchCallback callback);
}
