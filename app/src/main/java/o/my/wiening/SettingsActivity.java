package o.my.wiening;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREF_NAME = "MonitorSettings";
    public static final String KEY_OVERWRITE_MODE_INDEX = "overwrite_mode_index";
    public static final String KEY_DELETE_MIRROR = "delete_mirror";
    public static final String KEY_CONTENT_FILTER_ENABLED = "content_filter_enabled";
    public static final String KEY_FILTER_KEYWORDS = "filter_keywords";
    public static final String KEY_ACTIONBAR_COLOR_INDEX = "actionbar_color_index";
    public static final String KEY_BUTTON_COLOR_INDEX = "button_color_index";
    public static final String KEY_TEXT_COLOR_INDEX = "text_color_index";
    public static final String KEY_BACKGROUND_IMAGE_URI = "background_image_uri";
    public static final String KEY_CARD_ALPHA = "card_alpha";

    public static final String KEY_CUSTOM_ACTIONBAR_COLOR = "custom_actionbar_color";
    public static final String KEY_CUSTOM_BUTTON_COLOR = "custom_button_color";
    public static final String KEY_CUSTOM_TEXT_COLOR = "custom_text_color";

    private static final int SELECT_IMAGE_REQUEST = 1001;

    private SharedPreferences sharedPrefs;
    private Spinner spinnerOverwriteMode, spinnerActionBarColor, spinnerButtonColor, spinnerTextColor;
    private Switch switchDeleteMirror, switchContentFilter;
    private EditText etFilterKeywords;
    private Button btnAbout, btnSelectBackgroundImage, btnRestoreBackground;
    private SeekBar seekBarCardAlpha;
    private View settingsScrollView;
    private CardView cardViewFileOptions, cardViewFilterOptions, cardViewPersonalization;

    private String[] themeColorValues;
    private String[] textColorValues;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sharedPrefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        themeColorValues = getResources().getStringArray(R.array.theme_color_values);
        textColorValues = getResources().getStringArray(R.array.text_color_values);

        // Init Views
        settingsScrollView = findViewById(R.id.settings_scroll_view);
        cardViewFileOptions = findViewById(R.id.card_view_file_options);
        cardViewFilterOptions = findViewById(R.id.card_view_filter_options);
        cardViewPersonalization = findViewById(R.id.card_view_personalization);
        spinnerOverwriteMode = findViewById(R.id.spinnerOverwriteMode);
        switchDeleteMirror = findViewById(R.id.switchDeleteMirror);
        switchContentFilter = findViewById(R.id.switchContentFilter);
        etFilterKeywords = findViewById(R.id.etFilterKeywords);
        btnAbout = findViewById(R.id.btnAbout);
        spinnerActionBarColor = findViewById(R.id.spinnerActionBarColor);
        spinnerButtonColor = findViewById(R.id.spinnerButtonColor);
        spinnerTextColor = findViewById(R.id.spinnerTextColor);
        btnSelectBackgroundImage = findViewById(R.id.btnSelectBackgroundImage);
        btnRestoreBackground = findViewById(R.id.btnRestoreBackground);
        seekBarCardAlpha = findViewById(R.id.seekBarCardAlpha);

        // Setup UI
        setupActionBar();
        setupOverwriteModeSpinner();
        setupDeleteMirrorSwitch();
        setupContentFilter();
        setupAboutButton();
        setupColorSpinners();
        setupBackgroundImageButtons();
        setupAlphaSeekBar();

        // Apply initial settings
        applyColors();
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
            applyBackgroundImage();
        });
    }

    private void setupAlphaSeekBar() {
        int currentAlpha = sharedPrefs.getInt(KEY_CARD_ALPHA, 255);
        seekBarCardAlpha.setProgress(currentAlpha);
        applyCardAlpha(currentAlpha);

        seekBarCardAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    applyCardAlpha(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                sharedPrefs.edit().putInt(KEY_CARD_ALPHA, seekBar.getProgress()).apply();
            }
        });
    }

    private void applyCardAlpha(int alpha) {
        int color = Color.argb(alpha, 255, 255, 255);
        cardViewFileOptions.setCardBackgroundColor(color);
        cardViewFilterOptions.setCardBackgroundColor(color);
        cardViewPersonalization.setCardBackgroundColor(color);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SELECT_IMAGE_REQUEST && resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                Uri imageUri = data.getData();
                final int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(imageUri, takeFlags);
                sharedPrefs.edit().putString(KEY_BACKGROUND_IMAGE_URI, imageUri.toString()).apply();
                applyBackgroundImage();
            }
        }
    }

    private void applyBackgroundImage() {
        String uriString = sharedPrefs.getString(KEY_BACKGROUND_IMAGE_URI, null);
        if (uriString != null) {
            try {
                Uri imageUri = Uri.parse(uriString);
                Drawable background = Drawable.createFromStream(getContentResolver().openInputStream(imageUri), imageUri.toString());
                settingsScrollView.setBackground(background);
            } catch (Exception e) {
                settingsScrollView.setBackgroundColor(Color.WHITE);
                Toast.makeText(this, "加载背景图片失败", Toast.LENGTH_SHORT).show();
            }
        } else {
            settingsScrollView.setBackgroundColor(Color.WHITE);
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
        if (index == themeColorValues.length - 1) {
            String customColor = sharedPrefs.getString(customKey, "#FF000000");
            try { return Color.parseColor(customColor); } catch (Exception e) { return Color.BLACK; }
        } else if (index >= 0 && index < themeColorValues.length) {
            try { return Color.parseColor(themeColorValues[index]); } catch (Exception e) { return Color.BLACK; }
        }
        return Color.BLACK;
    }

    private int getTextColorFromIndex(int index) {
        if (index == textColorValues.length - 1) {
            String customColor = sharedPrefs.getString(KEY_CUSTOM_TEXT_COLOR, "#FFFFFFFF");
            try { return Color.parseColor(customColor); } catch (Exception e) { return Color.WHITE; }
        } else if (index >= 0 && index < textColorValues.length) {
            try { return Color.parseColor(textColorValues[index]); } catch (Exception e) { return Color.WHITE; }
        }
        return Color.WHITE;
    }

    private void setupColorSpinners() {
        ArrayAdapter<CharSequence> themeColorAdapter = ArrayAdapter.createFromResource(this, R.array.theme_color_names, android.R.layout.simple_spinner_item);
        themeColorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        ArrayAdapter<CharSequence> textColorAdapter = ArrayAdapter.createFromResource(this, R.array.text_color_names, android.R.layout.simple_spinner_item);
        textColorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        setupSpinner(spinnerActionBarColor, themeColorAdapter, KEY_ACTIONBAR_COLOR_INDEX, 7, (pos, fromUser) -> {
            if (fromUser && pos == themeColorValues.length - 1) showColorPickerDialog(KEY_CUSTOM_ACTIONBAR_COLOR, KEY_ACTIONBAR_COLOR_INDEX, pos); else applyActionBarColor(pos);
        });
        setupSpinner(spinnerButtonColor, themeColorAdapter, KEY_BUTTON_COLOR_INDEX, 7, (pos, fromUser) -> {
            if (fromUser && pos == themeColorValues.length - 1) showColorPickerDialog(KEY_CUSTOM_BUTTON_COLOR, KEY_BUTTON_COLOR_INDEX, pos); else applyButtonColor(pos);
        });
        setupSpinner(spinnerTextColor, textColorAdapter, KEY_TEXT_COLOR_INDEX, 2, (pos, fromUser) -> {
            if (fromUser && pos == textColorValues.length - 1) showColorPickerDialog(KEY_CUSTOM_TEXT_COLOR, KEY_TEXT_COLOR_INDEX, pos); else applyTextColor(pos);
        });
    }
    private interface OnColorSelected { void onSelect(int position, boolean fromUser); }
    private void setupSpinner(Spinner spinner, ArrayAdapter adapter, String key, int def, OnColorSelected listener) {
        spinner.setAdapter(adapter);
        spinner.setSelection(sharedPrefs.getInt(key, def));
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                 listener.onSelect(position, parent.isPressed());
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void showColorPickerDialog(String prefKey, String indexKey, int spinnerPosition) {
        final EditText input = new EditText(this);
        input.setHint("#RRGGBB");
        input.setText(sharedPrefs.getString(prefKey, ""));

        AlertDialog dialog = new AlertDialog.Builder(this)
            .setTitle("输入颜色 (例如 #FF0000)")
            .setView(input)
            .setPositiveButton("确定", (d, which) -> {
                String colorStr = input.getText().toString().trim();
                try {
                    Color.parseColor(colorStr);
                    sharedPrefs.edit().putString(prefKey, colorStr).apply();
                    sharedPrefs.edit().putInt(indexKey, spinnerPosition).apply();
                    if (prefKey.equals(KEY_CUSTOM_ACTIONBAR_COLOR)) applyActionBarColor(spinnerPosition);
                    else if (prefKey.equals(KEY_CUSTOM_BUTTON_COLOR)) applyButtonColor(spinnerPosition);
                    else if (prefKey.equals(KEY_CUSTOM_TEXT_COLOR)) applyTextColor(spinnerPosition);
                } catch (Exception e) {
                    Toast.makeText(this, "颜色格式错误", Toast.LENGTH_SHORT).show();
                    resetSpinnerSelection(indexKey);
                }
            })
            .setNegativeButton("取消", (d, which) -> resetSpinnerSelection(indexKey))
            .create();
        
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK);
    }
    
    private void resetSpinnerSelection(String key) {
        int defaultIndex = 7; // Black
        if (key.equals(KEY_TEXT_COLOR_INDEX)) defaultIndex = 2; // White
        
        sharedPrefs.edit().remove(key).apply();
        applyColors();
    }

    private void applyColors() {
        applyActionBarColor(sharedPrefs.getInt(KEY_ACTIONBAR_COLOR_INDEX, 7));
        applyButtonColor(sharedPrefs.getInt(KEY_BUTTON_COLOR_INDEX, 7));
        applyTextColor(sharedPrefs.getInt(KEY_TEXT_COLOR_INDEX, 2));
        applyBackgroundImage();
        applyCardAlpha(sharedPrefs.getInt(KEY_CARD_ALPHA, 255));
    }

    private void applyActionBarColor(int index) {
        int color = getThemeColorFromIndex(index, KEY_CUSTOM_ACTIONBAR_COLOR);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setBackgroundDrawable(new ColorDrawable(color));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setStatusBarColor(color);
        }
    }

    private void applyButtonColor(int index) {
        int color = getThemeColorFromIndex(index, KEY_CUSTOM_BUTTON_COLOR);
        btnAbout.setBackgroundTintList(ColorStateList.valueOf(color));
        btnSelectBackgroundImage.setBackgroundTintList(ColorStateList.valueOf(color));
        btnRestoreBackground.setBackgroundTintList(ColorStateList.valueOf(color));
    }

    private void applyTextColor(int index) {
        int color = getTextColorFromIndex(index);
        btnAbout.setTextColor(color);
        btnSelectBackgroundImage.setTextColor(color);
        btnRestoreBackground.setTextColor(color);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            String title = "设置";
            SpannableString text = new SpannableString(title);
            text.setSpan(new ForegroundColorSpan(color), 0, text.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            actionBar.setTitle(text);
        }
    }

    private void setupOverwriteModeSpinner() {
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, R.array.overwrite_options, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerOverwriteMode.setAdapter(adapter);
        spinnerOverwriteMode.setSelection(sharedPrefs.getInt(KEY_OVERWRITE_MODE_INDEX, 0));
        spinnerOverwriteMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                sharedPrefs.edit().putInt(KEY_OVERWRITE_MODE_INDEX, position).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupDeleteMirrorSwitch() {
        switchDeleteMirror.setChecked(sharedPrefs.getBoolean(KEY_DELETE_MIRROR, false));
        switchDeleteMirror.setOnCheckedChangeListener((v, isChecked) -> sharedPrefs.edit().putBoolean(KEY_DELETE_MIRROR, isChecked).apply());
    }

    private void setupContentFilter() {
        switchContentFilter.setChecked(sharedPrefs.getBoolean(KEY_CONTENT_FILTER_ENABLED, false));
        etFilterKeywords.setText(sharedPrefs.getString(KEY_FILTER_KEYWORDS, ""));
        etFilterKeywords.setEnabled(switchContentFilter.isChecked());
        
        switchContentFilter.setOnCheckedChangeListener((v, isChecked) -> {
            sharedPrefs.edit().putBoolean(KEY_CONTENT_FILTER_ENABLED, isChecked).apply();
            etFilterKeywords.setEnabled(isChecked);
        });
        
        etFilterKeywords.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                sharedPrefs.edit().putString(KEY_FILTER_KEYWORDS, s.toString()).apply();
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    private void setupAboutButton() {
        btnAbout.setOnClickListener(v -> {
             new AlertDialog.Builder(this)
                .setTitle("关于")
                .setMessage("你好")
                .setPositiveButton("确定", null)
                .show();
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