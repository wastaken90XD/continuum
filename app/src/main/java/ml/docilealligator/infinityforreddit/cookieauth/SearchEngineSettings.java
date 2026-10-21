package ml.docilealligator.infinityforreddit.cookieauth;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;

/**
 * Settings for the cookie-auth search pipeline: which engine to search with (and which to fall
 * back to), per-engine configuration (4get instance URL, Google CSE credentials), and the
 * captcha-solve budget (how many searches a single solved captcha is trusted for before 4get is
 * challenged -- and trusted -- again).
 *
 * Backed by its own SharedPreferences file, separate from the app's main settings, so this
 * experimental feature doesn't clutter (or get cleared by) anything else.
 */
public final class SearchEngineSettings {
    public static final String PREFS_NAME = "cookie_search_settings";

    public static final String KEY_PRIMARY_ENGINE = "primary_engine";
    public static final String KEY_FALLBACK_ENGINE = "fallback_engine";
    public static final String KEY_FOURGET_INSTANCE_URL = "fourget_instance_url";
    public static final String KEY_GOOGLE_CSE_API_KEY = "google_cse_api_key";
    public static final String KEY_GOOGLE_CSE_CX = "google_cse_cx";
    public static final String KEY_CAPTCHA_BUDGET = "captcha_budget";
    public static final String KEY_CAPTCHA_SEARCHES_REMAINING = "captcha_searches_remaining";
    /** The "I just solved a captcha" button's preference key (not a persisted settings value). */
    public static final String KEY_CAPTCHA_SOLVED_NOW_BUTTON = "captcha_solved_now";

    public static final String DEFAULT_FOURGET_INSTANCE_URL = "https://4get.ca";
    public static final int DEFAULT_CAPTCHA_BUDGET = 100;

    private SearchEngineSettings() {
    }

    /** The search backends a user can pick as primary or fallback. */
    public enum SearchEngine {
        FOURGET,
        GOOGLE_CSE,
        /** The app's existing www.reddit.com/search.json, through the cookie-session path. */
        REDDIT_NATIVE,
        /** Fallback disabled -- a failed primary search just reports an error. */
        NONE
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @NonNull
    public static SearchEngine getPrimaryEngine(Context context) {
        return parseEngine(prefs(context).getString(KEY_PRIMARY_ENGINE, null), SearchEngine.FOURGET);
    }

    public static void setPrimaryEngine(Context context, SearchEngine engine) {
        prefs(context).edit().putString(KEY_PRIMARY_ENGINE, engine.name()).apply();
    }

    @NonNull
    public static SearchEngine getFallbackEngine(Context context) {
        return parseEngine(prefs(context).getString(KEY_FALLBACK_ENGINE, null), SearchEngine.REDDIT_NATIVE);
    }

    public static void setFallbackEngine(Context context, SearchEngine engine) {
        prefs(context).edit().putString(KEY_FALLBACK_ENGINE, engine.name()).apply();
    }

    private static SearchEngine parseEngine(String name, SearchEngine fallback) {
        if (name == null) {
            return fallback;
        }
        try {
            return SearchEngine.valueOf(name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    @NonNull
    public static String getFourGetInstanceUrl(Context context) {
        String url = prefs(context).getString(KEY_FOURGET_INSTANCE_URL, DEFAULT_FOURGET_INSTANCE_URL);
        if (url == null || url.trim().isEmpty()) {
            return DEFAULT_FOURGET_INSTANCE_URL;
        }
        // Tolerate a trailing slash so "https://example.com/" and "https://example.com" both work.
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    public static void setFourGetInstanceUrl(Context context, String url) {
        prefs(context).edit().putString(KEY_FOURGET_INSTANCE_URL, url).apply();
    }

    public static String getGoogleCseApiKey(Context context) {
        return prefs(context).getString(KEY_GOOGLE_CSE_API_KEY, "");
    }

    public static void setGoogleCseApiKey(Context context, String apiKey) {
        prefs(context).edit().putString(KEY_GOOGLE_CSE_API_KEY, apiKey).apply();
    }

    public static String getGoogleCseCx(Context context) {
        return prefs(context).getString(KEY_GOOGLE_CSE_CX, "");
    }

    public static void setGoogleCseCx(Context context, String cx) {
        prefs(context).edit().putString(KEY_GOOGLE_CSE_CX, cx).apply();
    }

    public static boolean isGoogleCseConfigured(Context context) {
        String apiKey = getGoogleCseApiKey(context);
        String cx = getGoogleCseCx(context);
        return apiKey != null && !apiKey.isEmpty() && cx != null && !cx.isEmpty();
    }

    public static int getCaptchaBudget(Context context) {
        // Backed by an EditTextPreference, which always persists as a String regardless of
        // android:inputType -- read it as one and parse, rather than SharedPreferences.getInt,
        // which would ClassCastException against a String-typed value.
        String raw = prefs(context).getString(KEY_CAPTCHA_BUDGET, null);
        if (raw == null) {
            return DEFAULT_CAPTCHA_BUDGET;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            return DEFAULT_CAPTCHA_BUDGET;
        }
    }

    public static void setCaptchaBudget(Context context, int budget) {
        prefs(context).edit().putString(KEY_CAPTCHA_BUDGET, String.valueOf(Math.max(0, budget))).apply();
    }

    /**
     * Call once the user has solved a 4get captcha: resets the remaining-searches counter to the
     * configured budget, so the next {@link #getCaptchaBudget(Context)} searches worth of 4get
     * captcha/block responses are treated as transient and silently retried against the fallback
     * engine instead of re-showing the captcha WebView every time.
     */
    public static void recordCaptchaSolved(Context context) {
        prefs(context).edit().putInt(KEY_CAPTCHA_SEARCHES_REMAINING, getCaptchaBudget(context)).apply();
    }

    public static int getCaptchaSearchesRemaining(Context context) {
        return prefs(context).getInt(KEY_CAPTCHA_SEARCHES_REMAINING, 0);
    }

    /**
     * Call once per search that actually hit 4get. Returns true while the budget from the last
     * solved captcha hasn't run out yet, in which case a renewed captcha challenge should be
     * treated as "4get is just unavailable right now" (fall back silently) rather than prompting
     * the user to solve another one immediately.
     */
    public static boolean consumeCaptchaBudgetSearch(Context context) {
        SharedPreferences p = prefs(context);
        int remaining = p.getInt(KEY_CAPTCHA_SEARCHES_REMAINING, 0);
        if (remaining <= 0) {
            return false;
        }
        p.edit().putInt(KEY_CAPTCHA_SEARCHES_REMAINING, remaining - 1).apply();
        return true;
    }
}
