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
import android.text.util.Linkify;
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
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREF_NAME = "MonitorSettings";
    public static final String KEY_OVERWRITE_MODE_INDEX = "overwrite_mode_index";
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

    private static final int SELECT_IMAGE_REQUEST = 1001;

    // --- UI Elements ---
    private SharedPreferences sharedPrefs;
    private Spinner spinnerOverwriteMode, spinnerActionBarColor, spinnerButtonColor;
    private Spinner spinnerButtonTextColor, spinnerGeneralTextColor;
    private Switch switchContentFilter;
    private EditText etFilterKeywords;
    private Button btnAbout, btnSelectBackgroundImage, btnRestoreBackground;
    private SeekBar seekBarCardAlpha;
    private View settingsScrollView;
    private CardView cardViewFileOptions, cardViewFilterOptions, cardViewPersonalization;
    private TextView tvFileOptionsTitle, tvFilterOptionsTitle, tvPersonalizationTitle;
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
        setupOverwriteModeSpinner();
        setupContentFilter();
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
        cardViewFileOptions = findViewById(R.id.card_view_file_options);
        cardViewFilterOptions = findViewById(R.id.card_view_filter_options);
        cardViewPersonalization = findViewById(R.id.card_view_personalization);
        spinnerOverwriteMode = findViewById(R.id.spinnerOverwriteMode);
        switchContentFilter = findViewById(R.id.switchContentFilter);
        etFilterKeywords = findViewById(R.id.etFilterKeywords);
        btnAbout = findViewById(R.id.btnAbout);
        spinnerActionBarColor = findViewById(R.id.spinnerActionBarColor);
        spinnerButtonColor = findViewById(R.id.spinnerButtonColor);
        btnSelectBackgroundImage = findViewById(R.id.btnSelectBackgroundImage);
        btnRestoreBackground = findViewById(R.id.btnRestoreBackground);
        seekBarCardAlpha = findViewById(R.id.seekBarCardAlpha);
        tvFileOptionsTitle = findViewById(R.id.tv_file_options_title);
        tvFilterOptionsTitle = findViewById(R.id.tv_filter_options_title);
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
        if (cardViewFileOptions != null) {
            cardViewFileOptions.setCardBackgroundColor(color);
            cardViewFileOptions.setCardElevation(elevation);
        }
        if (cardViewFilterOptions != null) {
            cardViewFilterOptions.setCardBackgroundColor(color);
            cardViewFilterOptions.setCardElevation(elevation);
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
        final EditText input = new EditText(this);
        input.setHint("#AARRGGBB");
        input.setText(sharedPrefs.getString(prefKey, ""));
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("输入颜色 (例如 #FF0000)")
                .setView(input)
                .setPositiveButton("确定", (d, which) -> {
                    String colorStr = input.getText().toString().trim();
                    try {
                        Color.parseColor(colorStr);
                        sharedPrefs.edit().putString(prefKey, colorStr).putInt(indexKey, spinnerPosition).apply();
                        updateAllUI();
                    } catch (Exception e) {
                        Toast.makeText(this, "颜色格式错误", Toast.LENGTH_SHORT).show();
                        resetSpinnerSelection(indexKey);
                    }
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
        if (seekBarCardAlpha != null) {
            seekBarCardAlpha.setThumbTintList(buttonTint);
            seekBarCardAlpha.setProgressTintList(buttonTint);
            seekBarCardAlpha.setProgressBackgroundTintList(ColorStateList.valueOf(Color.LTGRAY));
        }

        // Apply Text Colors
        btnAbout.setTextColor(buttonTextColor);
        btnSelectBackgroundImage.setTextColor(buttonTextColor);
        btnRestoreBackground.setTextColor(buttonTextColor);
        if(tvFileOptionsTitle != null) tvFileOptionsTitle.setTextColor(generalTextColor);
        if(tvFilterOptionsTitle != null) tvFilterOptionsTitle.setTextColor(generalTextColor);
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

    private void setupAboutButton() {
        btnAbout.setOnClickListener(v -> {
            String message = "再次声明\n南娘是我们最好的朋友，请不要对南娘使用本软件。如果对南娘使用本软件被弄死了，软件作者概不负责\n本软件是为了防止你偷拍被发现而存不下照片的软件\n使用方法：源填入存储相机照片的绝对路径，目标你随便新建一个文件夹(如果你的相册会扫描整个/sdcard，那么请加.隐藏或者加.nomedia)并填入绝对路径，开始监控，软件会自动检测源文件夹里新增的文件并复制到目标文件夹\n设置里有我加的附加功能，应该会很好玩吧\n\n如果出现bug或者有什么新想法，请访问https://github.com/miziguo/O_My_WieNing/issues提交issues \n\n ©2025 MUW Group Studio\nデモクラシーは勝利を収めて帰還する！\n凌晨2：49了，这两个bug（文件编号和自定义颜色）是不可能修的";
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
