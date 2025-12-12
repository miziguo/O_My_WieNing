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
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    // =================================================================
    // 【核心常量】所有跨文件共享的设置键
    // =================================================================
    public static final String PREF_NAME = "FileCopierPrefs";
    public static final String KEY_SOURCE_PATH = "sourcePath";
    public static final String KEY_TARGET_PATH = "targetPath";
    public static final String KEY_OVERWRITE_MODE_INDEX = "overwrite_mode_index"; // 覆盖模式：0-跳过, 1-覆盖
    public static final String KEY_DELETE_MIRROR = "delete_mirror"; // 同步删除
    public static final String KEY_CONTENT_FILTER_ENABLED = "content_filter_enabled"; // 内容过滤开关
    public static final String KEY_FILTER_KEYWORDS = "filter_keywords"; // 过滤关键词
    // =================================================================

    private SharedPreferences sharedPrefs;
    private Spinner spinnerOverwriteMode;
    private Switch switchDeleteMirror;
    private Switch switchContentFilter;
    private EditText etFilterKeywords;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 假设您的布局文件名为 activity_settings.xml
        setContentView(R.layout.activity_settings);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setTitle("监控设置");
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        sharedPrefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        // 初始化 UI 元素
        spinnerOverwriteMode = findViewById(R.id.spinnerOverwriteMode); // 假设ID
        switchDeleteMirror = findViewById(R.id.switchDeleteMirror); // 假设ID
        switchContentFilter = findViewById(R.id.switchContentFilter); // 假设ID
        etFilterKeywords = findViewById(R.id.etFilterKeywords); // 假设ID

        setupOverwriteModeSpinner();
        setupDeleteMirrorSwitch();
        setupContentFilter();
    }

    private void setupOverwriteModeSpinner() {
        // 假设 R.array.overwrite_options 存在，包含 ["跳过", "覆盖"]
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
        switchDeleteMirror.setChecked(sharedPrefs.getBoolean(KEY_DELETE_MIRROR, false));

        switchDeleteMirror.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPrefs.edit().putBoolean(KEY_DELETE_MIRROR, isChecked).apply();
            Toast.makeText(SettingsActivity.this, isChecked ? "同步删除已开启" : "同步删除已关闭", Toast.LENGTH_SHORT).show();
        });
    }

    private void setupContentFilter() {
        boolean isFilterEnabled = sharedPrefs.getBoolean(KEY_CONTENT_FILTER_ENABLED, false);
        switchContentFilter.setChecked(isFilterEnabled);

        String savedKeywords = sharedPrefs.getString(KEY_FILTER_KEYWORDS, "");
        etFilterKeywords.setText(savedKeywords);

        etFilterKeywords.setEnabled(isFilterEnabled);
        switchContentFilter.setOnCheckedChangeListener((buttonView, isChecked) -> {
            sharedPrefs.edit().putBoolean(KEY_CONTENT_FILTER_ENABLED, isChecked).apply();
            etFilterKeywords.setEnabled(isChecked);

            String msg = isChecked ? "文件过滤已开启" : "文件过滤已关闭 (复制全部)";
            Toast.makeText(SettingsActivity.this, msg, Toast.LENGTH_SHORT).show();
        });

        etFilterKeywords.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 实时保存关键词
                sharedPrefs.edit().putString(KEY_FILTER_KEYWORDS, s.toString()).apply();
            }

            @Override
            public void afterTextChanged(Editable s) {}
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