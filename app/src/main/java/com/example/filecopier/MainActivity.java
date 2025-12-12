package com.example.filecopier;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.util.Calendar;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SOURCE = 1001;
    private static final int REQUEST_CODE_TARGET = 1002;
    private static final int REQUEST_PERMISSION_NOTIFY = 2001;

    private TextView tvSourcePath, tvTargetPath, tvEasterEgg;
    private Button btnSelectSource, btnSelectTarget, btnStart, btnStop;
    private Uri sourceUri = null;
    private Uri targetUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 初始化UI
        tvSourcePath = findViewById(R.id.tvSourcePath);
        tvTargetPath = findViewById(R.id.tvTargetPath);
        tvEasterEgg = findViewById(R.id.tvEasterEgg);
        btnSelectSource = findViewById(R.id.btnSelectSource);
        btnSelectTarget = findViewById(R.id.btnSelectTarget);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        // 请求通知权限
        requestNotificationPermission();

        // 2. 绑定点击事件
        btnSelectSource.setOnClickListener(v -> openDirectoryPicker(REQUEST_CODE_SOURCE));
        btnSelectTarget.setOnClickListener(v -> openDirectoryPicker(REQUEST_CODE_TARGET));
        btnStart.setOnClickListener(v -> startServiceFunc());
        btnStop.setOnClickListener(v -> stopServiceFunc());

        checkButtons();
        checkDateEasterEgg();
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_PERMISSION_NOTIFY);
            }
        }
    }

    private void openDirectoryPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                // 持久化权限
                getContentResolver().takePersistableUriPermission(treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                // 获取易读的文件夹名称
                String folderName = getFolderName(treeUri);

                // --- 修复点：在这里更新 UI 文字 ---
                if (requestCode == REQUEST_CODE_SOURCE) {
                    sourceUri = treeUri;
                    tvSourcePath.setText("已监控: " + folderName);
                } else if (requestCode == REQUEST_CODE_TARGET) {
                    targetUri = treeUri;
                    tvTargetPath.setText("复制到: " + folderName);
                }
                // ------------------------------

                Toast.makeText(this, "文件夹授权成功", Toast.LENGTH_SHORT).show();
                checkButtons();
            }
        }
    }

    // 辅助方法：把复杂的 URI 变成简单的文件夹名字
    private String getFolderName(Uri uri) {
        DocumentFile root = DocumentFile.fromTreeUri(this, uri);
        if (root != null && root.getName() != null) {
            return root.getName();
        }
        return "未知路径";
    }

    private void checkButtons() {
        btnStart.setEnabled(sourceUri != null && targetUri != null);
    }

    private void startServiceFunc() {
        Intent serviceIntent = new Intent(this, MonitorService.class);
        serviceIntent.putExtra("SOURCE_URI", sourceUri.toString());
        serviceIntent.putExtra("TARGET_URI", targetUri.toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
    }

    private void stopServiceFunc() {
        stopService(new Intent(this, MonitorService.class));
        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
    }

    private void checkDateEasterEgg() {
        Calendar c = Calendar.getInstance();
        if (c.get(Calendar.MONTH) == Calendar.JUNE && c.get(Calendar.DAY_OF_MONTH) == 4) {
            if (tvEasterEgg != null) tvEasterEgg.setVisibility(View.VISIBLE);
        }
    }
}