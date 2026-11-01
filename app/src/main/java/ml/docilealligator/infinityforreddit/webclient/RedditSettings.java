package ml.docilealligator.infinityforreddit.webclient;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;

/**
 * Tiny SharedPreferences wrapper for the one screen's settings: which Reddit frontend to browse
 * (reported "laggy as hell" on the default www.reddit.com -- that's the heavy React redesign;
 * old.reddit.com is the same classic, server-rendered page Reddit Enhancement Suite was built
 * against, and is dramatically lighter), which Redlib instance to use if the user picks that
 * option instead, and whether to run the Never-Ending Reddit-style auto-pagination script on Old
 * Reddit. No Room database for this -- it's three small values, SharedPreferences is the right
 * tool and keeps this app dependency-free.
 */
public class RedditSettings {
    private static final String PREFS_NAME = "continuum_settings";

    private static final String KEY_FRONTEND = "reddit_frontend";
    private static final String KEY_REDLIB_INSTANCE = "redlib_instance_url";
    private static final String KEY_NEVER_ENDING_REDDIT = "never_ending_reddit";

    public static final String FRONTEND_OLD = "old";
    public static final String FRONTEND_NEW = "new";
    public static final String FRONTEND_REDLIB = "redlib";

    /** Falls back here if the user picks Redlib but hasn't actually chosen an instance yet. */
    public static final String DEFAULT_REDLIB_INSTANCE = "https://redlib.catsarch.com";

    private final SharedPreferences prefs;

    public RedditSettings(@NonNull Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Old Reddit is the default: it's the fix for the lag complaint that motivated this screen. */
    @NonNull
    public String getFrontend() {
        String value = prefs.getString(KEY_FRONTEND, FRONTEND_OLD);
        return value == null ? FRONTEND_OLD : value;
    }

    public void setFrontend(@NonNull String frontend) {
        prefs.edit().putString(KEY_FRONTEND, frontend).apply();
    }

    @NonNull
    public String getRedlibInstance() {
        String stored = prefs.getString(KEY_REDLIB_INSTANCE, DEFAULT_REDLIB_INSTANCE);
        return stored == null || stored.isEmpty() ? DEFAULT_REDLIB_INSTANCE : stored;
    }

    public void setRedlibInstance(@NonNull String url) {
        prefs.edit().putString(KEY_REDLIB_INSTANCE, url).apply();
    }

    /** Only has an effect on Old Reddit -- see old_reddit_never_ending.js. */
    public boolean isNeverEndingRedditEnabled() {
        return prefs.getBoolean(KEY_NEVER_ENDING_REDDIT, true);
    }

    public void setNeverEndingRedditEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_NEVER_ENDING_REDDIT, enabled).apply();
    }

    /** The URL RedditWebActivity should load for the currently selected frontend. */
    @NonNull
    public String getHomeUrl() {
        switch (getFrontend()) {
            case FRONTEND_NEW:
                return "https://www.reddit.com/";
            case FRONTEND_REDLIB:
                String instance = getRedlibInstance();
                return instance.endsWith("/") ? instance : instance + "/";
            case FRONTEND_OLD:
            default:
                return "https://old.reddit.com/";
        }
    }

    /**
     * The host RedditWebActivity's shouldOverrideUrlLoading should keep navigation inside the
     * WebView for, beyond the always-allowed reddit.com/redd.it. Redlib instances are arbitrary
     * third-party domains, so unlike reddit.com this has to be resolved per-selection rather than
     * hardcoded.
     */
    @NonNull
    public String getPrimaryHost() {
        if (FRONTEND_REDLIB.equals(getFrontend())) {
            String instance = getRedlibInstance()
                    .replaceFirst("^https?://", "")
                    .replaceFirst("/.*$", "");
            return instance;
        }
        return "reddit.com";
    }
}
