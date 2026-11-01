package ml.docilealligator.infinityforreddit.webclient;

import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import ml.docilealligator.infinityforreddit.R;

/**
 * The entire settings surface for this app: which Reddit frontend {@link RedditWebActivity} loads
 * (see {@link RedditSettings} for why Old Reddit is the default), which Redlib instance to use if
 * that's the chosen frontend, and whether to run the Never-Ending Reddit auto-pagination script.
 *
 * <p>A plain Activity with a RadioGroup/Spinner/CheckBox rather than androidx.preference, since
 * three settings don't need a whole preference-screen framework as a dependency.
 */
public class SettingsActivity extends AppCompatActivity {

    /**
     * A curated, short list of public Redlib instances, from the actively-maintained
     * redlib-org/redlib-instances list. Instances come and go constantly -- this is a starting
     * point, not a guarantee any of them are up right now, which is why "Custom instance" always
     * exists alongside it.
     */
    private static final String[] CURATED_REDLIB_INSTANCES = {
            "https://redlib.catsarch.com",
            "https://safereddit.com",
            "https://redlib.ducks.party",
            "https://red.artemislena.eu",
            "https://redlib.privacyredirect.com",
    };

    private RedditSettings settings;
    private RadioGroup frontendRadioGroup;
    private View neverEndingRedditContainer;
    private CheckBox neverEndingRedditCheckBox;
    private View redlibOptionsContainer;
    private Spinner redlibInstanceSpinner;
    private EditText customRedlibInstanceEditText;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settings = new RedditSettings(this);

        Toolbar toolbar = findViewById(R.id.toolbar_settings_activity);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.settings);
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        frontendRadioGroup = findViewById(R.id.radio_group_frontend_settings_activity);
        neverEndingRedditContainer = findViewById(R.id.never_ending_reddit_container_settings_activity);
        neverEndingRedditCheckBox = findViewById(R.id.checkbox_never_ending_reddit_settings_activity);
        redlibOptionsContainer = findViewById(R.id.redlib_options_container_settings_activity);
        redlibInstanceSpinner = findViewById(R.id.spinner_redlib_instance_settings_activity);
        customRedlibInstanceEditText = findViewById(R.id.edit_text_custom_redlib_instance_settings_activity);

        setUpRedlibSpinner();
        loadCurrentSettingsIntoViews();

        frontendRadioGroup.setOnCheckedChangeListener((group, checkedId) -> updateVisibleSections());
        redlibInstanceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                boolean isCustom = position == CURATED_REDLIB_INSTANCES.length;
                customRedlibInstanceEditText.setVisibility(isCustom ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Nothing to do.
            }
        });

        Button saveButton = findViewById(R.id.button_save_settings_activity);
        saveButton.setOnClickListener(v -> saveAndFinish());

        updateVisibleSections();
    }

    private void setUpRedlibSpinner() {
        List<String> items = new ArrayList<>(Arrays.asList(CURATED_REDLIB_INSTANCES));
        items.add(getString(R.string.settings_redlib_custom_instance_option));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        redlibInstanceSpinner.setAdapter(adapter);
    }

    private void loadCurrentSettingsIntoViews() {
        String frontend = settings.getFrontend();
        if (RedditSettings.FRONTEND_NEW.equals(frontend)) {
            ((RadioButton) findViewById(R.id.radio_new_reddit_settings_activity)).setChecked(true);
        } else if (RedditSettings.FRONTEND_REDLIB.equals(frontend)) {
            ((RadioButton) findViewById(R.id.radio_redlib_settings_activity)).setChecked(true);
        } else {
            ((RadioButton) findViewById(R.id.radio_old_reddit_settings_activity)).setChecked(true);
        }

        neverEndingRedditCheckBox.setChecked(settings.isNeverEndingRedditEnabled());

        String storedInstance = settings.getRedlibInstance();
        int curatedIndex = Arrays.asList(CURATED_REDLIB_INSTANCES).indexOf(storedInstance);
        if (curatedIndex >= 0) {
            redlibInstanceSpinner.setSelection(curatedIndex);
        } else {
            redlibInstanceSpinner.setSelection(CURATED_REDLIB_INSTANCES.length);
            customRedlibInstanceEditText.setText(storedInstance);
            customRedlibInstanceEditText.setVisibility(View.VISIBLE);
        }
    }

    private void updateVisibleSections() {
        int checkedId = frontendRadioGroup.getCheckedRadioButtonId();
        boolean isOldReddit = checkedId == R.id.radio_old_reddit_settings_activity;
        boolean isRedlib = checkedId == R.id.radio_redlib_settings_activity;
        neverEndingRedditContainer.setVisibility(isOldReddit ? View.VISIBLE : View.GONE);
        redlibOptionsContainer.setVisibility(isRedlib ? View.VISIBLE : View.GONE);
    }

    private void saveAndFinish() {
        int checkedId = frontendRadioGroup.getCheckedRadioButtonId();
        if (checkedId == R.id.radio_new_reddit_settings_activity) {
            settings.setFrontend(RedditSettings.FRONTEND_NEW);
        } else if (checkedId == R.id.radio_redlib_settings_activity) {
            int selected = redlibInstanceSpinner.getSelectedItemPosition();
            String instanceUrl;
            if (selected == CURATED_REDLIB_INSTANCES.length) {
                instanceUrl = customRedlibInstanceEditText.getText().toString().trim();
                if (!instanceUrl.startsWith("https://") && !instanceUrl.startsWith("http://")) {
                    Toast.makeText(this, R.string.settings_invalid_custom_instance, Toast.LENGTH_LONG).show();
                    return;
                }
            } else {
                instanceUrl = CURATED_REDLIB_INSTANCES[selected];
            }
            settings.setRedlibInstance(instanceUrl);
            settings.setFrontend(RedditSettings.FRONTEND_REDLIB);
        } else {
            settings.setFrontend(RedditSettings.FRONTEND_OLD);
        }

        settings.setNeverEndingRedditEnabled(neverEndingRedditCheckBox.isChecked());

        finish();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}
