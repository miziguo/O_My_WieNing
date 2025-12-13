package com.example.filecopier;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
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
    // public static final String KEY_DELETE_MIRROR = "delete_mirror"; // [已删除]

    // --- 新增常量 ---
    public static final String KEY_CONTENT_FILTER_ENABLED = "content_filter_enabled"; // 过滤开关
    public static final String KEY_FILTER_KEYWORDS = "filter_keywords";             // 过滤关键词
    // ----------------

    private SharedPreferences sharedPrefs;
    private Spinner spinnerOverwriteMode;
    // private Switch switchDeleteMirror; // [已删除]

    // --- 新增成员变量 ---
    private Switch switchContentFilter;
    private EditText etFilterKeywords;
    private Button btnAbout;
    // --------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("设置"); // 设置 Toolbar 上的标题
        }

        sharedPrefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        // 初始化旧 UI
        spinnerOverwriteMode = findViewById(R.id.spinnerOverwriteMode);
        // switchDeleteMirror = findViewById(R.id.switchDeleteMirror); // [已删除]

        // --- 初始化新 UI ---
        switchContentFilter = findViewById(R.id.switchContentFilter);
        etFilterKeywords = findViewById(R.id.etFilterKeywords);
        btnAbout = findViewById(R.id.btnAbout);
        // --------------------

        setupOverwriteModeSpinner();
        // setupDeleteMirrorSwitch(); // [已删除]
        setupContentFilter();
        setupAboutButton();
    }

    private void setupOverwriteModeSpinner() {
        // ... (与之前代码保持一致) ...
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                this,
                R.array.overwrite_options,
                android.R.layout.simple_spinner_item
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerOverwriteMode.setAdapter(adapter);

        int savedIndex = sharedPrefs.getInt(KEY_OVERWRITE_MODE_INDEX, 0);
        spinnerOverwriteMode.setSelection(savedIndex);

        spinnerOverwriteMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                sharedPrefs.edit().putInt(KEY_OVERWRITE_MODE_INDEX, position).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // [已删除] setupDeleteMirrorSwitch 方法已移除

    // --- 新增方法：设置文件过滤功能 ---
    private void setupContentFilter() {
        // 1. 初始化开关状态
        boolean isFilterEnabled = sharedPrefs.getBoolean(KEY_CONTENT_FILTER_ENABLED, false);
        switchContentFilter.setChecked(isFilterEnabled);

        // 2. 初始化输入框内容
        String savedKeywords = sharedPrefs.getString(KEY_FILTER_KEYWORDS, "");
        etFilterKeywords.setText(savedKeywords);

        // 3. 监听开关状态，并实时更新输入框的启用状态
        etFilterKeywords.setEnabled(isFilterEnabled);
        switchContentFilter.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPrefs.edit().putBoolean(KEY_CONTENT_FILTER_ENABLED, isChecked).apply();
            etFilterKeywords.setEnabled(isChecked); // 启用/禁用输入框

            String msg = isChecked ? "文件过滤已开启" : "文件过滤已关闭 (复制全部)";
            Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_SHORT).show();
        });

        // 4. 监听输入框文本变化，并实时保存
        etFilterKeywords.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 实时保存输入内容
                sharedPrefs.edit().putString(KEY_FILTER_KEYWORDS, s.toString()).apply();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }
    // ------------------------------------

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
                // 1. 识别网址
                android.text.util.Linkify.addLinks(messageView, android.text.util.Linkify.WEB_URLS);

                // 2. 响应点击
                messageView.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());

                // 3. ★★★ 强制设置链接颜色为蓝色 ★★★
                // 0xFF2196F3 是好看的 Material Blue，也可以用纯蓝 0xFF0000FF
                messageView.setLinkTextColor(0xFF2196F3);
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(0xFF000000);
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
