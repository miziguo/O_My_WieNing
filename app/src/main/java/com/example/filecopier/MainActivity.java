package com.example.filecopier;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SOURCE = 1001;
    private static final int REQUEST_CODE_TARGET = 1002;

    private TextView tvSourcePath, tvTargetPath, tvLog;
    private Button btnSelectSource, btnSelectTarget, btnStart, btnStop;

    private Uri sourceUri = null;
    private Uri targetUri = null;

    private LogReceiver logReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvSourcePath = findViewById(R.id.tvSourcePath);
        tvTargetPath = findViewById(R.id.tvTargetPath);
        tvLog = findViewById(R.id.tvLog);
        btnSelectSource = findViewById(R.id.btnSelectSource);
        btnSelectTarget = findViewById(R.id.btnSelectTarget);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        btnSelectSource.setOnClickListener(v -> openDirectoryPicker(REQUEST_CODE_SOURCE));
        btnSelectTarget.setOnClickListener(v -> openDirectoryPicker(REQUEST_CODE_TARGET));

        btnStart.setOnClickListener(v -> startServiceFunc());
        btnStop.setOnClickListener(v -> stopServiceFunc());

        // 注册日志接收器 (API 34/35 兼容写法)
        logReceiver = new LogReceiver();
        registerReceiver(logReceiver, new IntentFilter("MonitorServiceLog"), RECEIVER_NOT_EXPORTED);

        checkButtons();
    }

    private void openDirectoryPicker(int requestCode) {
        // 使用 SAF (Storage Access Framework) 选择文件夹
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                // 关键步骤：持久化权限，否则服务重启后失效
                getContentResolver().takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );

                if (requestCode == REQUEST_CODE_SOURCE) {
                    sourceUri = treeUri;
                    tvSourcePath.setText(treeUri.toString());
                } else if (requestCode == REQUEST_CODE_TARGET) {
                    targetUri = treeUri;
                    tvTargetPath.setText(treeUri.toString());
                }

                Toast.makeText(this, "文件夹已选择并授权。", Toast.LENGTH_SHORT).show();
                checkButtons();
            }
        }
    }

    private void checkButtons() {
        boolean ready = (sourceUri != null && targetUri != null);
        btnStart.setEnabled(ready);
    }

    private void startServiceFunc() {
        Intent serviceIntent = new Intent(this, MonitorService.class);
        serviceIntent.putExtra("SOURCE_URI", sourceUri.toString());
        serviceIntent.putExtra("TARGET_URI", targetUri.toString());

        // API 26 (Oreo) 及以上必须使用 startForegroundService
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        btnStart.setEnabled(false);
        btnStop.setEnabled(true);
        appendLog("服务启动指令已发送。");
    }

    private void stopServiceFunc() {
        Intent serviceIntent = new Intent(this, MonitorService.class);
        serviceIntent.setAction("STOP");
        startService(serviceIntent);

        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        appendLog("服务停止指令已发送。");
    }

    public void appendLog(String msg) {
        String time = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString();
        tvLog.append(time + " " + msg + "\n");
        tvLog.post(() -> {
            View parent = (View) tvLog.getParent();
            if(parent != null) parent.scrollTo(0, tvLog.getBottom());
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (logReceiver != null) unregisterReceiver(logReceiver);
    }

    class LogReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String log = intent.getStringExtra("log");
            if (log != null) appendLog(log);
        }
    }
}