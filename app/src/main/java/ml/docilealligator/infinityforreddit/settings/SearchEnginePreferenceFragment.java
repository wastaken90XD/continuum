package ml.docilealligator.infinityforreddit.settings;

import android.content.Context;
import android.os.Bundle;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.cookieauth.SearchEngineSettings;
import ml.docilealligator.infinityforreddit.customviews.preference.CustomFontPreferenceFragmentCompat;

/**
 * "Query designer" for the cookie-auth search pipeline: which engine searches (4get, Google CSE,
 * or Reddit's own search.json), which one to fall back to, 4get instance URL, Google CSE
 * credentials, and the captcha-solve budget. Backed by its own SharedPreferences file (see
 * {@link SearchEngineSettings}), not the app's main settings, so every {@code android:key} here
 * must match a {@code SearchEngineSettings.KEY_*} constant exactly.
 */
public class SearchEnginePreferenceFragment extends CustomFontPreferenceFragmentCompat {

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        getPreferenceManager().setSharedPreferencesName(SearchEngineSettings.PREFS_NAME);
        getPreferenceManager().setSharedPreferencesMode(Context.MODE_PRIVATE);

        setPreferencesFromResource(R.xml.search_engine_preferences, rootKey);

        Preference captchaSolvedNowPreference = findPreference(SearchEngineSettings.KEY_CAPTCHA_SOLVED_NOW_BUTTON);
        if (captchaSolvedNowPreference != null) {
            captchaSolvedNowPreference.setOnPreferenceClickListener(preference -> {
                SearchEngineSettings.recordCaptchaSolved(mActivity);
                preference.setSummary(getString(R.string.settings_fourget_captcha_solved_now_done_summary,
                        SearchEngineSettings.getCaptchaBudget(mActivity)));
                return true;
            });
        }
    }
}
