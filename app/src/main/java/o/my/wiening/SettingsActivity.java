package o.my.wiening;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREF_NAME = "MonitorSettings";
    public static final String KEY_OVERWRITE_MODE_INDEX = "overwrite_mode_index";
    public static final String KEY_DELETE_MIRROR = "delete_mirror";
    public static final String KEY_CONTENT_FILTER_ENABLED = "content_filter_enabled";
    public static final String KEY_FILTER_KEYWORDS = "filter_keywords";
    public static final String KEY_ACTIONBAR_COLOR_INDEX = "actionbar_color_index";
    public static final String KEY_BUTTON_COLOR_INDEX = "button_color_index";
    public static final String KEY_TEXT_COLOR_INDEX = "text_color_index";
    
    public static final String KEY_CUSTOM_ACTIONBAR_COLOR = "custom_actionbar_color";
    public static final String KEY_CUSTOM_BUTTON_COLOR = "custom_button_color";
    public static final String KEY_CUSTOM_TEXT_COLOR = "custom_text_color";

    private SharedPreferences sharedPrefs;
    private Spinner spinnerOverwriteMode;
    private Switch switchDeleteMirror;
    private Switch switchContentFilter;
    private EditText etFilterKeywords;
    private Button btnAbout;
    private Spinner spinnerActionBarColor;
    private Spinner spinnerButtonColor;
    private Spinner spinnerTextColor;

    private String[] themeColorValues;
    private String[] textColorValues;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        sharedPrefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        themeColorValues = getResources().getStringArray(R.array.theme_color_values);
        textColorValues = getResources().getStringArray(R.array.text_color_values);

        // 初始化视图
        spinnerOverwriteMode = findViewById(R.id.spinnerOverwriteMode);
        switchDeleteMirror = findViewById(R.id.switchDeleteMirror);
        switchContentFilter = findViewById(R.id.switchContentFilter);
        etFilterKeywords = findViewById(R.id.etFilterKeywords);
        btnAbout = findViewById(R.id.btnAbout);
        spinnerActionBarColor = findViewById(R.id.spinnerActionBarColor);
        spinnerButtonColor = findViewById(R.id.spinnerButtonColor);
        spinnerTextColor = findViewById(R.id.spinnerTextColor);

        // 设置 UI
        setupActionBar();
        setupOverwriteModeSpinner();
        setupDeleteMirrorSwitch();
        setupContentFilter();
        setupAboutButton();
        setupColorSpinners();
        
        // 应用初始颜色
        applyColors();
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
            try {
                return Color.parseColor(customColor);
            } catch (Exception e) {
                return Color.BLACK;
            }
        } 
        else if (index >= 0 && index < themeColorValues.length) {
            try {
                return Color.parseColor(themeColorValues[index]);
            } catch (Exception e) {
                return Color.BLACK;
            }
        }
        return Color.BLACK;
    }

    private int getTextColorFromIndex(int index) {
        if (index == textColorValues.length - 1) {
            String customColor = sharedPrefs.getString(KEY_CUSTOM_TEXT_COLOR, "#FFFFFFFF");
            try {
                return Color.parseColor(customColor);
            } catch (Exception e) {
                return Color.WHITE;
            }
        } 
        else if (index >= 0 && index < textColorValues.length) {
            try {
                return Color.parseColor(textColorValues[index]);
            } catch (Exception e) {
                return Color.WHITE;
            }
        }
        return Color.WHITE;
    }

    private void setupColorSpinners() {
        ArrayAdapter<CharSequence> themeColorAdapter = ArrayAdapter.createFromResource(
                this, R.array.theme_color_names, android.R.layout.simple_spinner_item);
        themeColorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        ArrayAdapter<CharSequence> textColorAdapter = ArrayAdapter.createFromResource(
                this, R.array.text_color_names, android.R.layout.simple_spinner_item);
        textColorAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // 1. ActionBar 颜色
        setupSpinner(spinnerActionBarColor, themeColorAdapter, KEY_ACTIONBAR_COLOR_INDEX, 7, 
            (pos) -> {
                if (pos == themeColorValues.length - 1) {
                     // 无论之前有没有值，只要切换到自定义（或者已经在自定义再次点击触发），都强制弹窗
                     // 注意：Spinner 默认如果再次选择相同项不会触发 onItemSelected
                     // 为了支持再次点击自定义弹出，我们在 onItemSelected 中判断，如果值没变且是自定义，则不处理（交给下面的点击处理）
                     // 但 Spinner API 不支持重复点击回调。
                     // 所以这里的逻辑是：切换到自定义 -> 弹窗。
                     showColorPickerDialog(KEY_CUSTOM_ACTIONBAR_COLOR, KEY_ACTIONBAR_COLOR_INDEX, pos);
                } else {
                    applyActionBarColor(pos);
                }
            },
            (pos) -> showColorPickerDialog(KEY_CUSTOM_ACTIONBAR_COLOR, KEY_ACTIONBAR_COLOR_INDEX, pos)
        );

        // 2. 按钮颜色
        setupSpinner(spinnerButtonColor, themeColorAdapter, KEY_BUTTON_COLOR_INDEX, 7,
            (pos) -> {
                if (pos == themeColorValues.length - 1) {
                     showColorPickerDialog(KEY_CUSTOM_BUTTON_COLOR, KEY_BUTTON_COLOR_INDEX, pos);
                } else {
                    applyButtonColor(pos);
                }
            },
            (pos) -> showColorPickerDialog(KEY_CUSTOM_BUTTON_COLOR, KEY_BUTTON_COLOR_INDEX, pos)
        );

        // 3. 文字颜色
        setupSpinner(spinnerTextColor, textColorAdapter, KEY_TEXT_COLOR_INDEX, 2,
            (pos) -> {
                if (pos == textColorValues.length - 1) {
                     showColorPickerDialog(KEY_CUSTOM_TEXT_COLOR, KEY_TEXT_COLOR_INDEX, pos);
                } else {
                    applyTextColor(pos);
                }
            },
            (pos) -> showColorPickerDialog(KEY_CUSTOM_TEXT_COLOR, KEY_TEXT_COLOR_INDEX, pos)
        );
    }

    private interface OnColorSelected { void onSelect(int position); }
    private void setupSpinner(Spinner spinner, ArrayAdapter adapter, String key, int def, OnColorSelected listener, OnColorSelected onCustomReclick) {
        spinner.setAdapter(adapter);
        spinner.setSelection(sharedPrefs.getInt(key, def));
        
        // 关键修复：使用 OnTouchListener 或 post runnable 解决初始化时触发问题，以及允许“重选”
        // 但 Spinner 原生不支持重复选。我们简化逻辑：只要 selection 变了且变为自定义，就触发。
        // 为了支持“我想修改自定义颜色”，只能通过长按，或者先选别的再选自定义。
        // 或者，我们在 showColorPickerDialog 取消时，重置 Spinner 到默认值，强迫用户下次必须重新选自定义触发事件。
        
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean isInitial = true;
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitial) { isInitial = false; return; }
                
                sharedPrefs.edit().putInt(key, position).apply();
                
                // 判断是否是自定义选项
                boolean isCustomOption = false;
                if (key.equals(KEY_TEXT_COLOR_INDEX)) {
                    isCustomOption = (position == textColorValues.length - 1);
                } else {
                    isCustomOption = (position == themeColorValues.length - 1);
                }

                if (isCustomOption) {
                    // 只要选了自定义，就强制回调，弹窗逻辑在 listener 中
                    listener.onSelect(position); 
                } else {
                    listener.onSelect(position);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        spinner.setOnLongClickListener(v -> {
            int pos = spinner.getSelectedItemPosition();
            boolean isCustomOption = false;
            if (key.equals(KEY_TEXT_COLOR_INDEX)) {
                isCustomOption = (pos == textColorValues.length - 1);
            } else {
                isCustomOption = (pos == themeColorValues.length - 1);
            }

            if (isCustomOption) {
                onCustomReclick.onSelect(pos);
                return true;
            }
            return false;
        });
    }

    private void showColorPickerDialog(String prefKey, String indexKey, int spinnerPosition) {
        final EditText input = new EditText(this);
        input.setHint("#RRGGBB");
        input.setText(sharedPrefs.getString(prefKey, ""));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("输入颜色 (例如 #FF0000)")
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("确定", (d, which) -> {
                    String colorStr = input.getText().toString().trim();
                    try {
                        Color.parseColor(colorStr);
                        sharedPrefs.edit().putString(prefKey, colorStr).apply();
                        
                        if (prefKey.equals(KEY_CUSTOM_ACTIONBAR_COLOR)) applyActionBarColor(spinnerPosition);
                        else if (prefKey.equals(KEY_CUSTOM_BUTTON_COLOR)) applyButtonColor(spinnerPosition);
                        else if (prefKey.equals(KEY_CUSTOM_TEXT_COLOR)) applyTextColor(spinnerPosition);
                    } catch (Exception e) {
                        Toast.makeText(this, "颜色格式错误", Toast.LENGTH_SHORT).show();
                        // 格式错误也回滚，强迫重选
                        resetSpinnerSelection(indexKey);
                    }
                })
                .setNegativeButton("取消", (d, which) -> {
                    // 关键修复：取消时回滚选择，确保下次点击“自定义”能再次触发 onItemSelected
                    resetSpinnerSelection(indexKey);
                })
                .create();
        
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(Color.BLACK);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(Color.BLACK);
    }

    // 辅助回滚方法
    private void resetSpinnerSelection(String key) {
        int defaultIndex = 7; // 默认黑色
        if (key.equals(KEY_TEXT_COLOR_INDEX)) defaultIndex = 2; // 文字默认白色
        
        sharedPrefs.edit().putInt(key, defaultIndex).apply();
        
        if (key.equals(KEY_ACTIONBAR_COLOR_INDEX)) {
            spinnerActionBarColor.setSelection(defaultIndex);
            applyActionBarColor(defaultIndex);
        } else if (key.equals(KEY_BUTTON_COLOR_INDEX)) {
            spinnerButtonColor.setSelection(defaultIndex);
            applyButtonColor(defaultIndex);
        } else if (key.equals(KEY_TEXT_COLOR_INDEX)) {
            spinnerTextColor.setSelection(defaultIndex);
            applyTextColor(defaultIndex);
        }
    }

    private void applyColors() {
        applyActionBarColor(sharedPrefs.getInt(KEY_ACTIONBAR_COLOR_INDEX, 7));
        applyButtonColor(sharedPrefs.getInt(KEY_BUTTON_COLOR_INDEX, 7));
        applyTextColor(sharedPrefs.getInt(KEY_TEXT_COLOR_INDEX, 2));
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
    }

    private void applyTextColor(int index) {
        int color = getTextColorFromIndex(index);

        btnAbout.setTextColor(color);
        
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null && actionBar.getTitle() != null) {
            SpannableString text = new SpannableString(actionBar.getTitle());
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
        switchDeleteMirror.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPrefs.edit().putBoolean(KEY_DELETE_MIRROR, isChecked).apply();
            Toast.makeText(SettingsActivity.this, isChecked ? "同步删除已开启" : "同步删除已关闭", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupContentFilter() {
        switchContentFilter.setChecked(sharedPrefs.getBoolean(KEY_CONTENT_FILTER_ENABLED, false));
        etFilterKeywords.setText(sharedPrefs.getString(KEY_FILTER_KEYWORDS, ""));
        etFilterKeywords.setEnabled(switchContentFilter.isChecked());
        
        switchContentFilter.setOnCheckedChangeListener((v, isChecked) -> {
            sharedPrefs.edit().putBoolean(KEY_CONTENT_FILTER_ENABLED, isChecked).apply();
            etFilterKeywords.setEnabled(isChecked);
            String msg = isChecked ? "文件过滤已开启" : "文件过滤已关闭 (复制全部)";
            Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_SHORT).show();
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
            String message = "再次声明\n南娘是我们最好的朋友，请不要对南娘使用本软件。如果对南娘使用本软件被弄死了，软件作者概不负责\n\n本软件是为了防止你偷拍被发现后被强制删除照片导致无法留下没好的回忆的软件\n使用方法：源填入存储相机照片的绝对路径，目标你随便新建一个文件夹(如果你的相册会扫描整个/sdcard，那么请加.隐藏或者加.nomedia)并填入绝对路径，开始监控，软件会自动检测源文件夹里新增的文件并复制到目标文件夹\n设置里有我加的附加功能，应该会很好玩吧\n\n如果出现bug或者有什么新想法，请访问项目地址并提交issues\nhttps://github.com/miziguo/O_My_WieNing/issues\n\n©2023-2025 miziguo Studio\n(其实并没有版权)";

            AlertDialog dialog = new AlertDialog.Builder(this)
                    .setTitle("关于")
                    .setMessage(message)
                    .setPositiveButton("确定", null)
                    .create();

            dialog.show();

            android.widget.TextView messageView = dialog.findViewById(android.R.id.message);
            if (messageView != null) {
                android.text.util.Linkify.addLinks(messageView, android.text.util.Linkify.WEB_URLS);
                messageView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
                messageView.setLinkTextColor(0xFF2196F3);
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF000000);
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish(); return true;
        }
        return super.onOptionsItemSelected(item);
    }
}