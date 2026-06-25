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

import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;

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

    // FTP upload keys
    public static final String KEY_FTP_ENABLED = "ftp_enabled";
    public static final String KEY_FTP_HOST = "ftp_host";
    public static final String KEY_FTP_PORT = "ftp_port";
    public static final String KEY_FTP_USERNAME = "ftp_username";
    public static final String KEY_FTP_PASSWORD = "ftp_password";
    public static final String KEY_FTP_REMOTE_PATH = "ftp_remote_path";

    // Dedup filename on conflict
    public static final String KEY_DEDUP_FILENAME = "dedup_filename";

    private static final int SELECT_IMAGE_REQUEST = 1001;

    // --- UI Elements ---
    private SharedPreferences sharedPrefs;
    private Spinner spinnerActionBarColor, spinnerButtonColor;
    private Spinner spinnerButtonTextColor, spinnerGeneralTextColor;
    private Switch switchContentFilter;
    private Switch switchFtpEnabled;
    private Switch switchDedupFilename;
    private EditText etFilterKeywords;
    private EditText etFtpHost, etFtpPort, etFtpUsername, etFtpPassword, etFtpRemotePath;
    private Button btnAbout, btnSelectBackgroundImage, btnRestoreBackground, btnFtpTest;
    private SeekBar seekBarCardAlpha;
    private View settingsScrollView;
    private CardView cardViewFilterOptions, cardViewFtpOptions, cardViewPersonalization;
    private TextView tvFilterOptionsTitle, tvFtpOptionsTitle, tvPersonalizationTitle;
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
        setupFtpSettings();
        setupFtpTestButton();
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
        cardViewFtpOptions = findViewById(R.id.card_view_ftp_options);
        cardViewPersonalization = findViewById(R.id.card_view_personalization);
        switchContentFilter = findViewById(R.id.switchContentFilter);
        switchFtpEnabled = findViewById(R.id.switchFtpEnabled);
        switchDedupFilename = findViewById(R.id.switchDedupFilename);
        etFilterKeywords = findViewById(R.id.etFilterKeywords);
        etFtpHost = findViewById(R.id.etFtpHost);
        etFtpPort = findViewById(R.id.etFtpPort);
        etFtpUsername = findViewById(R.id.etFtpUsername);
        etFtpPassword = findViewById(R.id.etFtpPassword);
        etFtpRemotePath = findViewById(R.id.etFtpRemotePath);
        btnFtpTest = findViewById(R.id.btnFtpTest);
        btnAbout = findViewById(R.id.btnAbout);
        spinnerActionBarColor = findViewById(R.id.spinnerActionBarColor);
        spinnerButtonColor = findViewById(R.id.spinnerButtonColor);
        btnSelectBackgroundImage = findViewById(R.id.btnSelectBackgroundImage);
        btnRestoreBackground = findViewById(R.id.btnRestoreBackground);
        seekBarCardAlpha = findViewById(R.id.seekBarCardAlpha);
        tvFilterOptionsTitle = findViewById(R.id.tv_filter_options_title);
        tvFtpOptionsTitle = findViewById(R.id.tv_ftp_options_title);
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
        if (cardViewFtpOptions != null) {
            cardViewFtpOptions.setCardBackgroundColor(color);
            cardViewFtpOptions.setCardElevation(elevation);
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
        btnFtpTest.setBackgroundTintList(buttonTint);
        if (seekBarCardAlpha != null) {
            seekBarCardAlpha.setThumbTintList(buttonTint);
            seekBarCardAlpha.setProgressTintList(buttonTint);
            seekBarCardAlpha.setProgressBackgroundTintList(ColorStateList.valueOf(Color.LTGRAY));
        }

        // Apply Text Colors
        btnAbout.setTextColor(buttonTextColor);
        btnSelectBackgroundImage.setTextColor(buttonTextColor);
        btnRestoreBackground.setTextColor(buttonTextColor);
        btnFtpTest.setTextColor(buttonTextColor);
        if(tvFilterOptionsTitle != null) tvFilterOptionsTitle.setTextColor(generalTextColor);
        if(tvFtpOptionsTitle != null) tvFtpOptionsTitle.setTextColor(generalTextColor);
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

    private void setupFtpSettings() {
        boolean ftpEnabled = sharedPrefs.getBoolean(KEY_FTP_ENABLED, false);
        switchFtpEnabled.setChecked(ftpEnabled);

        etFtpHost.setText(sharedPrefs.getString(KEY_FTP_HOST, ""));
        etFtpPort.setText(String.valueOf(sharedPrefs.getInt(KEY_FTP_PORT, 21)));
        etFtpUsername.setText(sharedPrefs.getString(KEY_FTP_USERNAME, ""));
        etFtpPassword.setText(sharedPrefs.getString(KEY_FTP_PASSWORD, ""));
        etFtpRemotePath.setText(sharedPrefs.getString(KEY_FTP_REMOTE_PATH, "/"));

        setFtpFieldsEnabled(ftpEnabled);

        switchFtpEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPrefs.edit().putBoolean(KEY_FTP_ENABLED, isChecked).apply();
            setFtpFieldsEnabled(isChecked);
        });

        TextWatcher ftpSaver = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {}
        };

        etFtpHost.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                sharedPrefs.edit().putString(KEY_FTP_HOST, s.toString()).apply();
            }
        });

        etFtpPort.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                try {
                    sharedPrefs.edit().putInt(KEY_FTP_PORT, Integer.parseInt(s.toString())).apply();
                } catch (NumberFormatException ignored) {}
            }
        });

        etFtpUsername.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                sharedPrefs.edit().putString(KEY_FTP_USERNAME, s.toString()).apply();
            }
        });

        etFtpPassword.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                sharedPrefs.edit().putString(KEY_FTP_PASSWORD, s.toString()).apply();
            }
        });

        etFtpRemotePath.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                sharedPrefs.edit().putString(KEY_FTP_REMOTE_PATH, s.toString()).apply();
            }
        });
    }

    private void setFtpFieldsEnabled(boolean enabled) {
        etFtpHost.setEnabled(enabled);
        etFtpPort.setEnabled(enabled);
        etFtpUsername.setEnabled(enabled);
        etFtpPassword.setEnabled(enabled);
        etFtpRemotePath.setEnabled(enabled);
    }

    private void setupFtpTestButton() {
        btnFtpTest.setOnClickListener(v -> {
            final String host = etFtpHost.getText().toString().trim();
            int tmpPort;
            try {
                tmpPort = Integer.parseInt(etFtpPort.getText().toString().trim());
            } catch (NumberFormatException e) {
                tmpPort = 21;
            }
            final int port = tmpPort;
            final String username = etFtpUsername.getText().toString().trim();
            final String password = etFtpPassword.getText().toString();
            final String remotePath = etFtpRemotePath.getText().toString().trim();

            if (host.isEmpty()) {
                Toast.makeText(this, "请先填写主机地址", Toast.LENGTH_SHORT).show();
                return;
            }

            btnFtpTest.setEnabled(false);
            btnFtpTest.setText("连接中…");

            ExecutorService executor = Executors.newSingleThreadExecutor();
            executor.submit(() -> {
                String result = testFtpConnection(host, port, username, password, remotePath);
                runOnUiThread(() -> {
                    btnFtpTest.setEnabled(true);
                    btnFtpTest.setText("测试连接");
                    AlertDialog.Builder builder = new AlertDialog.Builder(this)
                            .setTitle("FTP 连接测试")
                            .setMessage(result);
                    if (result != null && result.startsWith("✅")) {
                        builder.setPositiveButton("确定", null);
                        builder.setNeutralButton("浏览目录", (d, w) ->
                                showFtpDirectoryBrowser(host, port, username, password, remotePath));
                    } else {
                        builder.setPositiveButton("确定", null);
                    }
                    builder.create().show();
                });
                executor.shutdown();
            });
        });
    }

    private String testFtpConnection(String host, int port, String username, String password, String remotePath) {
        FTPClient ftp = new FTPClient();
        long startTime = System.currentTimeMillis();
        try {
            ftp.setConnectTimeout(10000);
            ftp.setDataTimeout(10000);
            ftp.connect(host, port);
            int reply = ftp.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                ftp.disconnect();
                return "❌ 连接失败\n服务器返回码: " + reply;
            }

            ftp.setControlEncoding("UTF-8");
            if (!ftp.login(username, password)) {
                ftp.logout();
                return "❌ 登录失败\n用户名或密码错误";
            }

            ftp.enterLocalPassiveMode();
            long elapsed = System.currentTimeMillis() - startTime;

            StringBuilder sb = new StringBuilder();
            sb.append("✅ 连接成功！\n");
            sb.append("延迟: ").append(elapsed).append("ms\n");
            sb.append("服务器: ").append(host).append(":").append(port).append("\n");

            // 列出根目录
            String[] names = ftp.listNames("/");
            if (names != null) {
                sb.append("根目录文件数: ").append(names.length);
            }

            if (!remotePath.isEmpty() && !remotePath.equals("/")) {
                sb.append("\n远程路径: ").append(remotePath);
                boolean dirExists = ftp.changeWorkingDirectory(remotePath);
                sb.append(dirExists ? " ✅ 可访问" : " ⚠️ 不存在（上传时自动创建）");
            }

            ftp.logout();
            return sb.toString();

        } catch (Exception e) {
            return "❌ 连接失败\n" + e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            try {
                if (ftp.isConnected()) ftp.disconnect();
            } catch (Exception ignored) {}
        }
    }

    private void showFtpDirectoryBrowser(final String host, final int port,
                                          final String username, final String password,
                                          String startPath) {
        final String[] currentPath = {startPath.isEmpty() ? "/" : startPath};

        final TextView tvPath = new TextView(this);
        tvPath.setPadding(24, 12, 24, 4);
        tvPath.setTextSize(14);
        tvPath.setTextColor(Color.DKGRAY);

        final ListView listView = new ListView(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(0, 8, 0, 0);
        layout.addView(tvPath);
        layout.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (300 * getResources().getDisplayMetrics().density)));

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("浏览远程目录")
                .setView(layout)
                .setPositiveButton("选择此目录", (d, w) -> {
                    String path = currentPath[0];
                    if (!path.endsWith("/")) path += "/";
                    etFtpRemotePath.setText(path);
                })
                .setNegativeButton("取消", null)
                .create();

        final ExecutorService executor = Executors.newSingleThreadExecutor();

        final Runnable loadDirs = () -> {
            runOnUiThread(() -> {
                tvPath.setText("当前路径: " + currentPath[0]);
                listView.setAdapter(new ArrayAdapter<>(this,
                        android.R.layout.simple_list_item_1,
                        new String[]{"加载中…"}));
            });

            executor.submit(() -> {
                java.util.List<String> dirs = fetchFtpDirectories(host, port, username, password, currentPath[0]);
                runOnUiThread(() -> listView.setAdapter(new ArrayAdapter<>(this,
                        android.R.layout.simple_list_item_1, dirs)));
            });
        };

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String item = (String) parent.getItemAtPosition(position);
            if (item == null) return;
            if (item.startsWith("📁 ..")) {
                String path = currentPath[0];
                if ("/".equals(path)) return;
                int lastSlash = path.lastIndexOf('/');
                currentPath[0] = lastSlash <= 0 ? "/" : path.substring(0, lastSlash);
            } else if (item.startsWith("📁 ")) {
                String dirName = item.substring(2).trim();
                currentPath[0] = currentPath[0].endsWith("/") ?
                        currentPath[0] + dirName : currentPath[0] + "/" + dirName;
            }
            loadDirs.run();
        });

        dialog.setOnDismissListener(d -> executor.shutdown());
        dialog.show();
        loadDirs.run();
    }

    private java.util.List<String> fetchFtpDirectories(String host, int port, String username,
                                                        String password, String path) {
        java.util.List<String> dirs = new java.util.ArrayList<>();
        FTPClient ftp = new FTPClient();
        try {
            ftp.setConnectTimeout(10000);
            ftp.setDataTimeout(10000);
            ftp.connect(host, port);
            if (!FTPReply.isPositiveCompletion(ftp.getReplyCode())) {
                dirs.add("⚠️ 服务器拒绝连接");
                return dirs;
            }
            ftp.setControlEncoding("UTF-8");
            if (!ftp.login(username, password)) {
                dirs.add("⚠️ 登录失败");
                return dirs;
            }
            ftp.enterLocalPassiveMode();

            if (!"/".equals(path)) {
                dirs.add("📁 ..");
            }

            FTPFile[] files = ftp.listFiles(path);
            if (files != null) {
                for (FTPFile file : files) {
                    if (file.isDirectory()) {
                        dirs.add("📁 " + file.getName());
                    }
                }
            }

            if (dirs.isEmpty() || (dirs.size() == 1 && dirs.get(0).startsWith("📁 .."))) {
                dirs.add("（此目录为空）");
            }

            ftp.logout();
        } catch (java.io.IOException e) {
            dirs.add("⚠️ 读取失败: " + e.getMessage());
        } finally {
            try { if (ftp.isConnected()) ftp.disconnect(); } catch (java.io.IOException ignored) {}
        }
        return dirs;
    }

    private void setupAboutButton() {
        btnAbout.setOnClickListener(v -> {
            String message = "再次声明\n南娘是我们最好的朋友，请不要对南娘使用本软件。如果对南娘使用本软件被弄死了，软件作者概不负责\n本软件是为了防止你偷拍被发现而存不下照片的软件\n使用方法：源填入存储相机照片的绝对路径，目标你随便新建一个文件夹(如果你的相册会扫描整个/sdcard，那么请加.隐藏或者加.nomedia)并填入绝对路径，开始监控，软件会自动检测源文件夹里新增的文件并复制到目标文件夹\n设置里有我加的附加功能，应该会很好玩吧\n\n如果出现bug或者有什么新想法，请访问https://github.com/miziguo/O_My_WieNing/issues提交issues \n\n ©2026 IRCP Studio\nデモクラシーは勝利を収めて帰還する！\n凌晨2：49改了，这两个bug（文件编号和自定义颜色）已修";
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
