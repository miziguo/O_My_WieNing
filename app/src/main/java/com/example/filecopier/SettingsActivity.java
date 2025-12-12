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
import android.widget.EditText; // 导入 EditText
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREF_NAME = "MonitorSettings";
    public static final String KEY_OVERWRITE_MODE_INDEX = "overwrite_mode_index";
    public static final String KEY_DELETE_MIRROR = "delete_mirror";

    // --- 新增常量 ---
    public static final String KEY_CONTENT_FILTER_ENABLED = "content_filter_enabled"; // 过滤开关
    public static final String KEY_FILTER_KEYWORDS = "filter_keywords";             // 过滤关键词
    // ----------------

    private SharedPreferences sharedPrefs;
    private Spinner spinnerOverwriteMode;
    private Switch switchDeleteMirror;

    // --- 新增成员变量 ---
    private Switch switchContentFilter;
    private EditText etFilterKeywords;
    // --------------------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle("监控设置");
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        sharedPrefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        // 初始化旧 UI
        spinnerOverwriteMode = findViewById(R.id.spinnerOverwriteMode);
        switchDeleteMirror = findViewById(R.id.switchDeleteMirror);

        // --- 初始化新 UI ---
        switchContentFilter = findViewById(R.id.switchContentFilter);
        etFilterKeywords = findViewById(R.id.etFilterKeywords);
        // --------------------

        setupOverwriteModeSpinner();
        setupDeleteMirrorSwitch();
        setupContentFilter(); // 调用新的设置方法
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

    private void setupDeleteMirrorSwitch() {
        // ... (与之前代码保持一致) ...
        switchDeleteMirror.setChecked(sharedPrefs.getBoolean(KEY_DELETE_MIRROR, false));

        switchDeleteMirror.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPrefs.edit().putBoolean(KEY_DELETE_MIRROR, isChecked).apply();
            Toast.makeText(SettingsActivity.this, isChecked ? "同步删除已开启" : "同步删除已关闭", Toast.LENGTH_SHORT).show();
        });
    }

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


    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}