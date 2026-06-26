package o.my.wiening;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.util.Linkify;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREF_NAME = "MonitorSettings";
    public static final String KEY_CONTENT_FILTER_ENABLED = "content_filter_enabled";
    public static final String KEY_FILTER_KEYWORDS = "filter_keywords";

    // Theme Keys
    public static final String KEY_ACTIONBAR_COLOR_INDEX = "actionbar_color_index";
    public static final String KEY_BUTTON_COLOR_INDEX = "button_color_index";
    public static final String KEY_BACKGROUND_IMAGE_URI = "background_image_uri";
    public static final String KEY_CARD_ALPHA = "card_alpha";
    public static final String KEY_CUSTOM_ACTIONBAR_COLOR = "custom_actionbar_color";
    public static final String KEY_CUSTOM_BUTTON_COLOR = "custom_button_color";

    // New, separate keys for text colors
    public static final String KEY_BUTTON_TEXT_COLOR_INDEX = "button_text_color_index";
    public static final String KEY_GENERAL_TEXT_COLOR_INDEX = "general_text_color_index";
    public static final String KEY_CUSTOM_BUTTON_TEXT_COLOR = "custom_button_text_color";
    public static final String KEY_CUSTOM_GENERAL_TEXT_COLOR = "custom_general_text_color";

    // WebDAV upload keys
    public static final String KEY_WEBDAV_ENABLED = "webdav_enabled";
    public static final String KEY_WEBDAV_URL = "webdav_url";
    public static final String KEY_WEBDAV_USERNAME = "webdav_username";
    public static final String KEY_WEBDAV_PASSWORD = "webdav_password";
    public static final String KEY_WEBDAV_REMOTE_PATH = "webdav_remote_path";

    // Dedup filename on conflict
    public static final String KEY_DEDUP_FILENAME = "dedup_filename";

    private static final int SELECT_IMAGE_REQUEST = 1001;

    // --- UI Elements ---
    private SharedPreferences sharedPrefs;
    private Spinner spinnerActionBarColor, spinnerButtonColor;
    private Spinner spinnerButtonTextColor, spinnerGeneralTextColor;
    private Switch switchContentFilter;
    private Switch switchWebdavEnabled;
    private Switch switchDedupFilename;
    private EditText etFilterKeywords;
    private EditText etWebdavUrl, etWebdavUsername, etWebdavPassword, etWebdavRemotePath;
    private Button btnAbout, btnSelectBackgroundImage, btnRestoreBackground, btnWebdavTest, btnWebdavBrowse;
    private SeekBar seekBarCardAlpha;
    private View settingsScrollView;
    private CardView cardViewFilterOptions, cardViewWebdavOptions, cardViewPersonalization;
    private TextView tvFilterOptionsTitle, tvWebdavOptionsTitle, tvPersonalizationTitle;
    private TextView tvLabelActionBar, tvLabelButton, tvLabelCardAlpha;
    private TextView tvLabelButtonTextColor, tvLabelGeneralTextColor;

    // --- Data ---
    private String[] themeColorNames, themeColorValues;
    private String[] textColorNames, textColorValues;
    private boolean isSpinnerInitialising = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sharedPrefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        themeColorNames = getResources().getStringArray(R.array.theme_color_names);
        themeColorValues = getResources().getStringArray(R.array.theme_color_values);
        textColorNames = getResources().getStringArray(R.array.text_color_names);
        textColorValues = getResources().getStringArray(R.array.text_color_values);

        initViews();
        setupActionBar();
        setupContentFilter();
        setupDedupFilename();
        setupWebdavSettings();
        setupWebdavTestButton();
        setupWebdavBrowseButton();
        setupAboutButton();
        setupBackgroundImageButtons();
        setupAlphaSeekBar();
        setupColorSpinners();

        updateAllUI();
        isSpinnerInitialising = false;
    }

    @Override
    protected void onResume() {
        super.onResume();
        isSpinnerInitialising = true;
        // Re-set spinner selections without triggering listener
        spinnerActionBarColor.setSelection(sharedPrefs.getInt(KEY_ACTIONBAR_COLOR_INDEX, 7), false);
        spinnerButtonColor.setSelection(sharedPrefs.getInt(KEY_BUTTON_COLOR_INDEX, 7), false);
        spinnerButtonTextColor.setSelection(sharedPrefs.getInt(KEY_BUTTON_TEXT_COLOR_INDEX, 2), false); // Default white
        spinnerGeneralTextColor.setSelection(sharedPrefs.getInt(KEY_GENERAL_TEXT_COLOR_INDEX, 0), false); // Default black
        updateAllUI();
        isSpinnerInitialising = false;
    }

    private void initViews() {
        settingsScrollView = findViewById(R.id.settings_scroll_view);
        cardViewFilterOptions = findViewById(R.id.card_view_filter_options);
        cardViewWebdavOptions = findViewById(R.id.card_view_webdav_options);
        cardViewPersonalization = findViewById(R.id.card_view_personalization);
        switchContentFilter = findViewById(R.id.switchContentFilter);
        switchWebdavEnabled = findViewById(R.id.switchWebdavEnabled);
        switchDedupFilename = findViewById(R.id.switchDedupFilename);
        etFilterKeywords = findViewById(R.id.etFilterKeywords);
        etWebdavUrl = findViewById(R.id.etWebdavUrl);
        etWebdavUsername = findViewById(R.id.etWebdavUsername);
        etWebdavPassword = findViewById(R.id.etWebdavPassword);
        etWebdavRemotePath = findViewById(R.id.etWebdavRemotePath);
        btnWebdavTest = findViewById(R.id.btnWebdavTest);
        btnWebdavBrowse = findViewById(R.id.btnWebdavBrowse);
        btnAbout = findViewById(R.id.btnAbout);
        spinnerActionBarColor = findViewById(R.id.spinnerActionBarColor);
        spinnerButtonColor = findViewById(R.id.spinnerButtonColor);
        btnSelectBackgroundImage = findViewById(R.id.btnSelectBackgroundImage);
        btnRestoreBackground = findViewById(R.id.btnRestoreBackground);
        seekBarCardAlpha = findViewById(R.id.seekBarCardAlpha);
        tvFilterOptionsTitle = findViewById(R.id.tv_filter_options_title);
        tvWebdavOptionsTitle = findViewById(R.id.tv_webdav_options_title);
        tvPersonalizationTitle = findViewById(R.id.tv_personalization_title);
        tvLabelActionBar = findViewById(R.id.tv_label_actionbar_color);
        tvLabelButton = findViewById(R.id.tv_label_button_color);
        tvLabelCardAlpha = findViewById(R.id.tv_label_card_alpha);
        spinnerButtonTextColor = findViewById(R.id.spinnerButtonTextColor);
        spinnerGeneralTextColor = findViewById(R.id.spinnerGeneralTextColor);
        tvLabelButtonTextColor = findViewById(R.id.tv_label_button_text_color);
        tvLabelGeneralTextColor = findViewById(R.id.tv_label_general_text_color);
    }

    private void setupBackgroundImageButtons() {
        btnSelectBackgroundImage.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(intent, SELECT_IMAGE_REQUEST);
        });
        btnRestoreBackground.setOnClickListener(v -> {
            sharedPrefs.edit().remove(KEY_BACKGROUND_IMAGE_URI).apply();
            updateAllUI();
        });
    }

    private void setupAlphaSeekBar() {
        seekBarCardAlpha.setProgress(sharedPrefs.getInt(KEY_CARD_ALPHA, 255));
        seekBarCardAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) updateCardAlpha(progress);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                sharedPrefs.edit().putInt(KEY_CARD_ALPHA, seekBar.getProgress()).apply();
            }
        });
    }

    private void updateCardAlpha(int alpha) {
        int color = Color.argb(alpha, 255, 255, 255);
        float elevation = (alpha == 255) ? getResources().getDisplayMetrics().density * 2 : 0f;
        if (cardViewFilterOptions != null) {
            cardViewFilterOptions.setCardBackgroundColor(color);
            cardViewFilterOptions.setCardElevation(elevation);
        }
        if (cardViewWebdavOptions != null) {
            cardViewWebdavOptions.setCardBackgroundColor(color);
            cardViewWebdavOptions.setCardElevation(elevation);
        }
        if (cardViewPersonalization != null) {
            cardViewPersonalization.setCardBackgroundColor(color);
            cardViewPersonalization.setCardElevation(elevation);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SELECT_IMAGE_REQUEST && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri imageUri = data.getData();
                try {
                    final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(imageUri, takeFlags);
                    sharedPrefs.edit().putString(KEY_BACKGROUND_IMAGE_URI, imageUri.toString()).apply();
                    updateAllUI();
                } catch (SecurityException e) {
                    Toast.makeText(this, "无法获取图片权限", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    private void setupActionBar() {
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("设置");
        }
    }

    private int getThemeColorFromIndex(int index, String customKey) {
        if (index == themeColorValues.length) {
            String customColor = sharedPrefs.getString(customKey, "#FF000000");
            try { return Color.parseColor(customColor); } catch (Exception e) { return Color.BLACK; }
        } else if (index >= 0 && index < themeColorValues.length) {
            try { return Color.parseColor(themeColorValues[index]); } catch (Exception e) { return Color.BLACK; }
        }
        return Color.BLACK;
    }

    private int getTextColorFromIndex(int index, String customColorKey, String defaultColor) {
        if (index == textColorValues.length) {
            String customColor = sharedPrefs.getString(customColorKey, defaultColor);
            try { return Color.parseColor(customColor); } catch (Exception e) { return Color.parseColor(defaultColor); }
        } else if (index >= 0 && index < textColorValues.length) {
            try { return Color.parseColor(textColorValues[index]); } catch (Exception e) { return Color.parseColor(defaultColor); }
        }
        return Color.parseColor(defaultColor);
    }

    // ★★★ CORE FIX: Replaced lambda with explicit anonymous class for reliability ★★★
    private void setupColorSpinners() {
        ArrayAdapter<CharSequence> themeColorAdapter = ArrayAdapter.createFromResource(this, R.array.theme_color_names, android.R.layout.simple_spinner_item);
        themeColorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        ArrayAdapter<CharSequence> textColorAdapter = ArrayAdapter.createFromResource(this, R.array.text_color_names, android.R.layout.simple_spinner_item);
        textColorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // Setup ActionBar Color Spinner
        spinnerActionBarColor.setAdapter(themeColorAdapter);
        spinnerActionBarColor.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isSpinnerInitialising) return;
                sharedPrefs.edit().putInt(KEY_ACTIONBAR_COLOR_INDEX, position).apply();
                if ("自定义".equals(parent.getItemAtPosition(position).toString())) {
                    showColorPickerDialog(KEY_CUSTOM_ACTIONBAR_COLOR, KEY_ACTIONBAR_COLOR_INDEX, position);
                } else {
                    updateAllUI();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Setup Button Color Spinner
        spinnerButtonColor.setAdapter(themeColorAdapter);
        spinnerButtonColor.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isSpinnerInitialising) return;
                sharedPrefs.edit().putInt(KEY_BUTTON_COLOR_INDEX, position).apply();
                if ("自定义".equals(parent.getItemAtPosition(position).toString())) {
                    showColorPickerDialog(KEY_CUSTOM_BUTTON_COLOR, KEY_BUTTON_COLOR_INDEX, position);
                } else {
                    updateAllUI();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Setup Button Text Color Spinner
        spinnerButtonTextColor.setAdapter(textColorAdapter);
        spinnerButtonTextColor.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isSpinnerInitialising) return;
                sharedPrefs.edit().putInt(KEY_BUTTON_TEXT_COLOR_INDEX, position).apply();
                if ("自定义".equals(parent.getItemAtPosition(position).toString())) {
                    showColorPickerDialog(KEY_CUSTOM_BUTTON_TEXT_COLOR, KEY_BUTTON_TEXT_COLOR_INDEX, position);
                } else {
                    updateAllUI();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // Setup General Text Color Spinner
        spinnerGeneralTextColor.setAdapter(textColorAdapter);
        spinnerGeneralTextColor.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isSpinnerInitialising) return;
                sharedPrefs.edit().putInt(KEY_GENERAL_TEXT_COLOR_INDEX, position).apply();
                if ("自定义".equals(parent.getItemAtPosition(position).toString())) {
                    showColorPickerDialog(KEY_CUSTOM_GENERAL_TEXT_COLOR, KEY_GENERAL_TEXT_COLOR_INDEX, position);
                } else {
                    updateAllUI();
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void showColorPickerDialog(String prefKey, String indexKey, int spinnerPosition) {
        // Parse existing custom color (or start with black)
        String saved = sharedPrefs.getString(prefKey, "");
        int initR = 0, initG = 0, initB = 0;
        try {
            int c = Color.parseColor(saved);
            initR = Color.red(c);
            initG = Color.green(c);
            initB = Color.blue(c);
        } catch (Exception ignored) {}

        float density = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding((int)(16*density), (int)(8*density), (int)(16*density), (int)(8*density));

        // Color preview box
        final View preview = new View(this);
        preview.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int)(60*density)));
        GradientDrawable prevBg = new GradientDrawable();
        prevBg.setShape(GradientDrawable.RECTANGLE);
        prevBg.setCornerRadius(8*density);
        prevBg.setColor(Color.rgb(initR, initG, initB));
        preview.setBackground(prevBg);
        root.addView(preview);

        // Shared RGB state (used by both TextWatcher and SeekBar listener)
        final int[] rgb = {initR, initG, initB};

        // --- R --- (declare before hexInput so TextWatcher can reference)
        final TextView labelR = new TextView(this);
        labelR.setText("R: " + initR);
        labelR.setTextSize(13);
        root.addView(labelR);
        final SeekBar sbR = new SeekBar(this);
        sbR.setMax(255);
        sbR.setProgress(initR);
        root.addView(sbR);

        // --- G ---
        final TextView labelG = new TextView(this);
        labelG.setText("G: " + initG);
        labelG.setTextSize(13);
        root.addView(labelG);
        final SeekBar sbG = new SeekBar(this);
        sbG.setMax(255);
        sbG.setProgress(initG);
        root.addView(sbG);

        // --- B ---
        final TextView labelB = new TextView(this);
        labelB.setText("B: " + initB);
        labelB.setTextSize(13);
        root.addView(labelB);
        final SeekBar sbB = new SeekBar(this);
        sbB.setMax(255);
        sbB.setProgress(initB);
        root.addView(sbB);

        // Hex input (editable, syncs with sliders)
        final boolean[] textChangingBySeekBar = {false};
        final EditText hexInput = new EditText(this);
        hexInput.setGravity(android.view.Gravity.CENTER);
        hexInput.setTextSize(14);
        hexInput.setPadding((int)(12*density), (int)(8*density), (int)(12*density), (int)(8*density));
        hexInput.setHint("#RRGGBB");
        hexInput.setMaxLines(1);
        hexInput.setSingleLine(true);
        hexInput.setInputType(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        hexInput.setText(String.format("#%02X%02X%02X", initR, initG, initB));
        hexInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (textChangingBySeekBar[0]) return;
                String hex = s.toString().trim();
                if (hex.startsWith("#")) hex = hex.substring(1);
                if (hex.length() != 6) return;
                try {
                    int r = Integer.parseInt(hex.substring(0, 2), 16);
                    int g = Integer.parseInt(hex.substring(2, 4), 16);
                    int b = Integer.parseInt(hex.substring(4, 6), 16);
                    rgb[0] = r; rgb[1] = g; rgb[2] = b;
                    labelR.setText("R: " + r);
                    labelG.setText("G: " + g);
                    labelB.setText("B: " + b);
                    sbR.setProgress(r);
                    sbG.setProgress(g);
                    sbB.setProgress(b);
                    int color = Color.rgb(r, g, b);
                    if (preview.getBackground() instanceof GradientDrawable) {
                        ((GradientDrawable) preview.getBackground()).setColor(color);
                    }
                } catch (NumberFormatException ignored) {}
            }
        });
        root.addView(hexInput);

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int val, boolean fromUser) {
                if (sb == sbR) { rgb[0] = val; labelR.setText("R: " + val); }
                else if (sb == sbG) { rgb[1] = val; labelG.setText("G: " + val); }
                else { rgb[2] = val; labelB.setText("B: " + val); }
                int color = Color.rgb(rgb[0], rgb[1], rgb[2]);
                if (preview.getBackground() instanceof GradientDrawable) {
                    ((GradientDrawable) preview.getBackground()).setColor(color);
                }
                textChangingBySeekBar[0] = true;
                hexInput.setText(String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]));
                textChangingBySeekBar[0] = false;
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        };
        sbR.setOnSeekBarChangeListener(listener);
        sbG.setOnSeekBarChangeListener(listener);
        sbB.setOnSeekBarChangeListener(listener);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("自定义颜色")
                .setView(root)
                .setPositiveButton("确定", (d, which) -> {
                    String hex = String.format("#%02X%02X%02X", rgb[0], rgb[1], rgb[2]);
                    sharedPrefs.edit().putString(prefKey, hex).putInt(indexKey, spinnerPosition).apply();
                    updateAllUI();
                })
                .setNegativeButton("取消", (d, which) -> resetSpinnerSelection(indexKey))
                .setOnCancelListener(d -> resetSpinnerSelection(indexKey))
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK);
    }

    private void resetSpinnerSelection(String key) {
        int defaultSelection = 7;
        if (key.equals(KEY_BUTTON_TEXT_COLOR_INDEX)) defaultSelection = 2;
        if (key.equals(KEY_GENERAL_TEXT_COLOR_INDEX)) defaultSelection = 0;
        sharedPrefs.edit().putInt(key, defaultSelection).apply();
        updateAllUI();
    }

    private void updateAllUI() {
        int actionBarColor = getThemeColorFromIndex(sharedPrefs.getInt(KEY_ACTIONBAR_COLOR_INDEX, 7), KEY_CUSTOM_ACTIONBAR_COLOR);
        int buttonColor = getThemeColorFromIndex(sharedPrefs.getInt(KEY_BUTTON_COLOR_INDEX, 7), KEY_CUSTOM_BUTTON_COLOR);
        int buttonTextColor = getTextColorFromIndex(sharedPrefs.getInt(KEY_BUTTON_TEXT_COLOR_INDEX, 2), KEY_CUSTOM_BUTTON_TEXT_COLOR, "#FFFFFF");
        int generalTextColor = getTextColorFromIndex(sharedPrefs.getInt(KEY_GENERAL_TEXT_COLOR_INDEX, 0), KEY_CUSTOM_GENERAL_TEXT_COLOR, "#000000");
        int cardAlpha = sharedPrefs.getInt(KEY_CARD_ALPHA, 255);

        // Apply ActionBar Color & Title Color
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setBackgroundDrawable(new ColorDrawable(actionBarColor));
            SpannableString title = new SpannableString(actionBar.getTitle() != null ? actionBar.getTitle() : "设置");
            title.setSpan(new ForegroundColorSpan(buttonTextColor), 0, title.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            actionBar.setTitle(title);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(actionBarColor);
        }

        // Apply Button and SeekBar Color
        ColorStateList buttonTint = ColorStateList.valueOf(buttonColor);
        btnAbout.setBackgroundTintList(buttonTint);
        btnSelectBackgroundImage.setBackgroundTintList(buttonTint);
        btnRestoreBackground.setBackgroundTintList(buttonTint);
        btnWebdavTest.setBackgroundTintList(buttonTint);
        btnWebdavBrowse.setBackgroundTintList(buttonTint);
        if (seekBarCardAlpha != null) {
            seekBarCardAlpha.setThumbTintList(buttonTint);
            seekBarCardAlpha.setProgressTintList(buttonTint);
            seekBarCardAlpha.setProgressBackgroundTintList(ColorStateList.valueOf(Color.LTGRAY));
        }

        // Apply Text Colors
        btnAbout.setTextColor(buttonTextColor);
        btnSelectBackgroundImage.setTextColor(buttonTextColor);
        btnRestoreBackground.setTextColor(buttonTextColor);
        btnWebdavTest.setTextColor(buttonTextColor);
        btnWebdavBrowse.setTextColor(buttonTextColor);
        if(tvFilterOptionsTitle != null) tvFilterOptionsTitle.setTextColor(generalTextColor);
        if(tvWebdavOptionsTitle != null) tvWebdavOptionsTitle.setTextColor(generalTextColor);
        if(switchContentFilter != null) switchContentFilter.setTextColor(generalTextColor);
        if(tvPersonalizationTitle != null) tvPersonalizationTitle.setTextColor(generalTextColor);
        if(tvLabelActionBar != null) tvLabelActionBar.setTextColor(generalTextColor);
        if(tvLabelButton != null) tvLabelButton.setTextColor(generalTextColor);
        if(tvLabelButtonTextColor != null) tvLabelButtonTextColor.setTextColor(generalTextColor);
        if(tvLabelGeneralTextColor != null) tvLabelGeneralTextColor.setTextColor(generalTextColor);
        if(tvLabelCardAlpha != null) tvLabelCardAlpha.setTextColor(generalTextColor);

        // Apply Background
        updateBackgroundImage();

        // Apply Card Alpha
        updateCardAlpha(cardAlpha);
    }

    private void updateBackgroundImage() {
        String uriString = sharedPrefs.getString(KEY_BACKGROUND_IMAGE_URI, null);
        if (uriString != null) {
            try {
                Uri imageUri = Uri.parse(uriString);
                Drawable background = Drawable.createFromStream(getContentResolver().openInputStream(imageUri), uriString);
                settingsScrollView.setBackground(background);
            } catch (Exception e) {
                Toast.makeText(this, "加载背景图片失败", Toast.LENGTH_SHORT).show();
                settingsScrollView.setBackgroundColor(Color.WHITE);
            }
        } else {
            settingsScrollView.setBackgroundColor(Color.WHITE);
        }
    }

    private void setupContentFilter() {
        boolean isFilterEnabled = sharedPrefs.getBoolean(KEY_CONTENT_FILTER_ENABLED, false);
        switchContentFilter.setChecked(isFilterEnabled);
        etFilterKeywords.setText(sharedPrefs.getString(KEY_FILTER_KEYWORDS, ""));
        etFilterKeywords.setEnabled(isFilterEnabled);
        switchContentFilter.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPrefs.edit().putBoolean(KEY_CONTENT_FILTER_ENABLED, isChecked).apply();
            etFilterKeywords.setEnabled(isChecked);
        });
        etFilterKeywords.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                sharedPrefs.edit().putString(KEY_FILTER_KEYWORDS, s.toString()).apply();
            }
        });
    }

    private void setupDedupFilename() {
        boolean dedup = sharedPrefs.getBoolean(KEY_DEDUP_FILENAME, false);
        switchDedupFilename.setChecked(dedup);
        switchDedupFilename.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPrefs.edit().putBoolean(KEY_DEDUP_FILENAME, isChecked).apply();
        });
    }

    private void setupWebdavSettings() {
        boolean enabled = sharedPrefs.getBoolean(KEY_WEBDAV_ENABLED, false);
        switchWebdavEnabled.setChecked(enabled);

        etWebdavUrl.setText(sharedPrefs.getString(KEY_WEBDAV_URL, ""));
        etWebdavUsername.setText(sharedPrefs.getString(KEY_WEBDAV_USERNAME, ""));
        etWebdavPassword.setText(sharedPrefs.getString(KEY_WEBDAV_PASSWORD, ""));
        etWebdavRemotePath.setText(sharedPrefs.getString(KEY_WEBDAV_REMOTE_PATH, ""));

        setWebdavFieldsEnabled(enabled);

        switchWebdavEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPrefs.edit().putBoolean(KEY_WEBDAV_ENABLED, isChecked).apply();
            setWebdavFieldsEnabled(isChecked);
        });

        etWebdavUrl.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                sharedPrefs.edit().putString(KEY_WEBDAV_URL, s.toString()).apply();
            }
        });

        etWebdavUsername.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                sharedPrefs.edit().putString(KEY_WEBDAV_USERNAME, s.toString()).apply();
            }
        });

        etWebdavPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                sharedPrefs.edit().putString(KEY_WEBDAV_PASSWORD, s.toString()).apply();
            }
        });

        etWebdavRemotePath.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                sharedPrefs.edit().putString(KEY_WEBDAV_REMOTE_PATH, s.toString()).apply();
            }
        });
    }

    private void setWebdavFieldsEnabled(boolean enabled) {
        etWebdavUrl.setEnabled(enabled);
        etWebdavUsername.setEnabled(enabled);
        etWebdavPassword.setEnabled(enabled);
        etWebdavRemotePath.setEnabled(enabled);
        btnWebdavBrowse.setEnabled(enabled);
    }

    private void setupWebdavTestButton() {
        btnWebdavTest.setOnClickListener(v -> {
            final String url = etWebdavUrl.getText().toString().trim();
            final String username = etWebdavUsername.getText().toString().trim();
            final String password = etWebdavPassword.getText().toString();

            if (url.isEmpty()) {
                Toast.makeText(this, "请先填写 WebDAV 地址", Toast.LENGTH_SHORT).show();
                return;
            }

            btnWebdavTest.setEnabled(false);
            btnWebdavTest.setText("连接中…");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> {
                String result = testWebdavConnection(url, username, password);
                runOnUiThread(() -> {
                    btnWebdavTest.setEnabled(true);
                    btnWebdavTest.setText("测试连接");
                    new AlertDialog.Builder(this)
                            .setTitle("WebDAV 连接测试")
                            .setMessage(result)
                            .setPositiveButton("确定", null)
                            .create().show();
                });
                executor.shutdown();
            });
        });
    }

    private static final okhttp3.MediaType XML_MEDIA_TYPE = okhttp3.MediaType.parse("application/xml; charset=utf-8");
    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n" +
            "<D:propfind xmlns:D=\"DAV:\">\n" +
            "  <D:prop>\n" +
            "    <D:displayname/>\n" +
            "    <D:resourcetype/>\n" +
            "  </D:prop>\n" +
            "</D:propfind>";

    private String testWebdavConnection(String baseUrl, String username, String password) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        // 确保 URL 以 / 结尾
        String url = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";

        okhttp3.Request.Builder reqBuilder = new okhttp3.Request.Builder()
                .url(url)
                .method("PROPFIND", okhttp3.RequestBody.create(PROPFIND_BODY, XML_MEDIA_TYPE))
                .header("Depth", "0");

        if (!username.isEmpty()) {
            String credential = okhttp3.Credentials.basic(username, password);
            reqBuilder.header("Authorization", credential);
        }

        long startTime = System.currentTimeMillis();
        try (okhttp3.Response response = client.newCall(reqBuilder.build()).execute()) {
            long elapsed = System.currentTimeMillis() - startTime;
            int code = response.code();
            if (code >= 200 && code < 300) {
                return "✅ 连接成功！\n延迟: " + elapsed + "ms\n服务器: " + url +
                        "\n响应码: " + code + " " + response.message();
            } else if (code == 401) {
                return "❌ 认证失败\n用户名或密码错误";
            } else if (code == 405) {
                return "❌ 服务器不支持 WebDAV\n该地址未启用 WebDAV 协议（405 Method Not Allowed）\n\n请确认 NAS 已开启 WebDAV 服务，且路径正确。";
            } else {
                return "❌ 连接失败\n响应码: " + code + " " + response.message();
            }
        } catch (javax.net.ssl.SSLHandshakeException e) {
            return "❌ SSL 证书错误\n" + e.getMessage() + "\n\n提示：若使用自签名证书，请先通过浏览器信任该证书。";
        } catch (Exception e) {
            return "❌ 连接失败\n" + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private void setupWebdavBrowseButton() {
        btnWebdavBrowse.setOnClickListener(v -> {
            final String baseUrl = etWebdavUrl.getText().toString().trim();
            final String username = etWebdavUsername.getText().toString().trim();
            final String password = etWebdavPassword.getText().toString();

            if (baseUrl.isEmpty()) {
                Toast.makeText(this, "请先填写 WebDAV 地址", Toast.LENGTH_SHORT).show();
                return;
            }

            String startPath = etWebdavRemotePath.getText().toString().trim();
            if (startPath.isEmpty()) startPath = "/";

            showWebdavBrowserDialog(baseUrl, startPath, username, password);
        });
    }

    private void showWebdavBrowserDialog(String baseUrl, String currentPath,
                                          String username, String password) {
        // 先获取当前路径下的目录列表
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(() -> {
            java.util.List<String[]> dirs = fetchWebdavDirectories(baseUrl, currentPath, username, password);
            runOnUiThread(() -> {
                executor.shutdown();
                if (dirs == null) {
                    Toast.makeText(this, "读取目录失败，请检查连接和权限", Toast.LENGTH_SHORT).show();
                    return;
                }

                // 构建显示文本
                String[] itemNames = new String[dirs.size() + 1];
                itemNames[0] = "[选择当前目录] " + currentPath;
                for (int i = 0; i < dirs.size(); i++) {
                    String icon = dirs.get(i)[0]; // "📁" 或 "📄"
                    String name = dirs.get(i)[1];
                    itemNames[i + 1] = icon + " " + name;
                }

                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle("浏览: " + currentPath);

                if (dirs.isEmpty()) {
                    builder.setMessage("该目录为空\n\n选择当前路径作为远程目录？");
                    builder.setPositiveButton("选择此路径", (d, w) -> {
                        etWebdavRemotePath.setText(currentPath);
                    });
                    builder.setNegativeButton("返回", null);
                } else {
                    builder.setItems(itemNames, (dialog, which) -> {
                        if (which == 0) {
                            // 选择当前目录
                            etWebdavRemotePath.setText(currentPath);
                            Toast.makeText(this, "已选择: " + currentPath, Toast.LENGTH_SHORT).show();
                        } else {
                            int idx = which - 1;
                            String name = dirs.get(idx)[1];
                            String newPath = currentPath.endsWith("/")
                                    ? currentPath + name + "/"
                                    : currentPath + "/" + name + "/";
                            showWebdavBrowserDialog(baseUrl, newPath, username, password);
                        }
                    });
                }
                builder.setNegativeButton("返回上级", (d, w) -> {
                    if ("/".equals(currentPath)) return;
                    String parent = currentPath.substring(0, currentPath.lastIndexOf('/'));
                    if (parent.isEmpty()) parent = "/";
                    parent = parent.endsWith("/") ? parent : parent + "/";
                    showWebdavBrowserDialog(baseUrl, parent, username, password);
                });

                AlertDialog dialog = builder.create();
                dialog.show();
            });
        });
    }

    /** 返回 List<String[]>，每个 String[] = {图标, 名称}；null 表示失败 */
    private java.util.List<String[]> fetchWebdavDirectories(String baseUrl, String path,
                                                             String username, String password) {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        // 去掉开头的 / 避免双斜杠
        String relativePath = path.startsWith("/") ? path.substring(1) : path;
        String url = relativePath.isEmpty() ? base : base + relativePath;
        if (!url.endsWith("/")) url += "/";

        okhttp3.Request.Builder reqBuilder = new okhttp3.Request.Builder()
                .url(url)
                .method("PROPFIND", okhttp3.RequestBody.create(PROPFIND_BODY, XML_MEDIA_TYPE))
                .header("Depth", "1");
        if (!username.isEmpty()) {
            reqBuilder.header("Authorization", okhttp3.Credentials.basic(username, password));
        }

        try (okhttp3.Response response = client.newCall(reqBuilder.build()).execute()) {
            if (response.code() < 200 || response.code() >= 300) return null;
            String body = response.body() != null ? response.body().string() : "";
            return parseWebdavListing(body, url);
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 PROPFIND Depth:1 的 XML 响应，提取目录/文件列表 */
    private java.util.List<String[]> parseWebdavListing(String xml, String baseUrl) {
        java.util.List<String[]> result = new java.util.ArrayList<>();
        try {
            javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            org.w3c.dom.Document doc = factory.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));

            org.w3c.dom.NodeList responses = doc.getElementsByTagNameNS("DAV:", "response");
            for (int i = 0; i < responses.getLength(); i++) {
                org.w3c.dom.Element resp = (org.w3c.dom.Element) responses.item(i);

                // 获取 href
                org.w3c.dom.NodeList hrefs = resp.getElementsByTagNameNS("DAV:", "href");
                String href = hrefs.getLength() > 0 ? hrefs.item(0).getTextContent() : "";
                // 跳过自身和父级
                if (href.endsWith("/") && (href.equals(baseUrl) || href.endsWith(baseUrl.substring(0, baseUrl.length() - 1)))) continue;

                // 判断是目录还是文件
                org.w3c.dom.NodeList resTypes = resp.getElementsByTagNameNS("DAV:", "resourcetype");
                boolean isCollection = false;
                if (resTypes.getLength() > 0) {
                    org.w3c.dom.NodeList children = resTypes.item(0).getChildNodes();
                    for (int j = 0; j < children.getLength(); j++) {
                        if ("collection".equals(children.item(j).getLocalName())) {
                            isCollection = true;
                            break;
                        }
                    }
                }

                if (!isCollection) continue; // 只显示目录

                // 提取名称
                String decodedHref = java.net.URLDecoder.decode(href, "UTF-8");
                // 去掉尾部斜杠和前面的路径部分
                String basePart = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
                String relative = decodedHref;
                if (relative.startsWith(basePart)) relative = relative.substring(basePart.length());
                if (relative.startsWith("/")) relative = relative.substring(1);
                if (relative.endsWith("/")) relative = relative.substring(0, relative.length() - 1);
                if (relative.isEmpty() || relative.contains("/")) continue; // 跳过非直系子目录

                result.add(new String[]{"📁", relative});
            }
        } catch (Exception e) {
            // 解析失败，返回空列表
        }
        return result;
    }

    private void setupAboutButton() {
        btnAbout.setOnClickListener(v -> {
            String message = "再次声明\n南娘是我们最好的朋友，请不要对南娘使用本软件。如果对南娘使用本软件被弄死了，软件作者概不负责\n本软件是为了防止你偷拍被发现而存不下照片的软件\n使用方法：源填入存储相机照片的绝对路径，目标你随便新建一个文件夹(如果你的相册会扫描整个/sdcard，那么请加.隐藏或者加.nomedia)并填入绝对路径，开始监控，软件会自动检测源文件夹里新增的文件并复制到目标文件夹\n设置里有我加的附加功能，应该会很好玩吧\n\n如果出现bug或者有什么新想法，请访问https://github.com/miziguo/O_My_WieNing/issues提交issues \n\n ©2026 IRCP Studio\nデモクラシーは勝利を収めて帰還する！";
            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("关于")
                    .setMessage(message)
                    .setPositiveButton("确定", null)
                    .create();
            dialog.show();
            TextView messageView = dialog.findViewById(android.R.id.message);
            if (messageView != null) {
                Linkify.addLinks(messageView, Linkify.WEB_URLS);
                messageView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                messageView.setLinkTextColor(Color.BLUE);
                messageView.setTextIsSelectable(true);
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK);
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

}
