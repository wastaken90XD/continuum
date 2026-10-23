package ml.docilealligator.infinityforreddit.settings;

import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.ListPreference;
import androidx.preference.PreferenceManager;
import androidx.preference.SwitchPreference;
import javax.inject.Inject;
import javax.inject.Named;
import ml.docilealligator.infinityforreddit.Infinity;
import ml.docilealligator.infinityforreddit.R;
import ml.docilealligator.infinityforreddit.customviews.preference.CustomFontPreferenceFragmentCompat;
import ml.docilealligator.infinityforreddit.events.ChangeAppLockEvent;
import ml.docilealligator.infinityforreddit.events.ChangeRequireAuthToAccountSectionEvent;
import ml.docilealligator.infinityforreddit.events.ToggleSecureModeEvent;
import ml.docilealligator.infinityforreddit.utils.SharedPreferencesUtils;
import org.greenrobot.eventbus.EventBus;

public class SecurityPreferenceFragment extends CustomFontPreferenceFragmentCompat {

    @Inject
    @Named("default")
    SharedPreferences sharedPreferences;

    @Nullable
    String rootKey;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        this.rootKey = rootKey;
    }

    private void createPreferences() {
        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setSharedPreferencesName(SharedPreferencesUtils.SECURITY_SHARED_PREFERENCES_FILE);
        setPreferencesFromResource(R.xml.security_preferences, rootKey);

        ((Infinity) mActivity.getApplication()).getAppComponent().inject(this);

        SwitchPreference requireAuthToAccountSectionSwitch = findPreference(SharedPreferencesUtils.REQUIRE_AUTHENTICATION_TO_GO_TO_ACCOUNT_SECTION_IN_NAVIGATION_DRAWER);
        SwitchPreference secureModeSwitch = findPreference(SharedPreferencesUtils.SECURE_MODE);
        SwitchPreference appLockSwitch = findPreference(SharedPreferencesUtils.APP_LOCK);
        ListPreference appLockTimeoutListPreference = findPreference(SharedPreferencesUtils.APP_LOCK_TIMEOUT);

        if (requireAuthToAccountSectionSwitch != null) {
            requireAuthToAccountSectionSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                EventBus.getDefault().post(new ChangeRequireAuthToAccountSectionEvent((Boolean) newValue));
                return true;
            });
        }

        if (secureModeSwitch != null) {
            secureModeSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                EventBus.getDefault().post(new ToggleSecureModeEvent((Boolean) newValue));
                // This screen too, not only the ones resumed after it.
                ((Infinity) mActivity.getApplication()).applySecureMode(mActivity);
                return true;
            });
        }

        if (appLockSwitch != null && appLockTimeoutListPreference != null) {
            appLockSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
                EventBus.getDefault().post(new ChangeAppLockEvent((Boolean) newValue, Long.parseLong(appLockTimeoutListPreference.getValue())));
                return true;
            });

            appLockTimeoutListPreference.setOnPreferenceChangeListener((preference, newValue) -> {
                EventBus.getDefault().post(new ChangeAppLockEvent(appLockSwitch.isChecked(), Long.parseLong((String) newValue)));
                return true;
            });
        }

        applyStyle();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mActivity.isDeviceCredentialAvailable()) {
            mActivity.confirmDeviceCredential(mActivity.getString(R.string.unlock), null,
                    this::createPreferences, mActivity::triggerBackPress);
        } else {
            mActivity.triggerBackPress();
        }
    }
}