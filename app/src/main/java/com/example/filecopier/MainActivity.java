package com.example.filecopier;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.documentfile.provider.DocumentFile;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View; // 导入 View 类
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_SOURCE = 1001;
    private static final int REQUEST_CODE_TARGET = 1002;

    private TextView tvSourcePath, tvTargetPath;
    private TextView tvEasterEgg; // 声明彩蛋 TextView
    private Button btnSelectSource, btnSelectTarget, btnStart, btnStop;

    private Uri sourceUri = null;
    private Uri targetUri = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvSourcePath = findViewById(R.id.tvSourcePath);
        tvTargetPath = findViewById(R.id.tvTargetPath);
        tvEasterEgg = findViewById(R.id.tvEasterEgg); // 找到彩蛋 TextView

        btnSelectSource = findViewById(R.id.btnSelectSource);
        btnSelectTarget = findViewById(R.id.btnSelectTarget);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnSelectTarget); // 修正：这里可能有一个复制错误，应该是 btnStop

        // 修正：确保 btnStop 正确初始化
        btnStop = findViewById(R.id.btnStop);


        btnSelectSource.setOnClickListener(v -> openDirectoryPicker(REQUEST_CODE_SOURCE));
        btnSelectTarget.setOnClickListener(v -> openDirectoryPicker(REQUEST_CODE_TARGET));

        btnStart.setOnClickListener(v -> startServiceFunc());
        btnStop.setOnClickListener(v -> stopServiceFunc());

        checkButtons();

        // --- ⚙️ 彩蛋功能调用 ---
        checkDateEasterEgg();
    }

    /**
     * 检测系统日期是否为 6 月 4 日，并显示彩蛋信息
     */
    private void checkDateEasterEgg() {
        Calendar calendar = Calendar.getInstance();
        int month = calendar.get(Calendar.MONTH);
        int day = calendar.get(Calendar.DAY_OF_MONTH);

        if (month == Calendar.JUNE && day == 4) {
            // 关键修改：将 TextView 的可见性设置为可见 (VISIBLE)
            if (tvEasterEgg != null) {
                tvEasterEgg.setVisibility(View.VISIBLE);
            }
        }
        // 如果日期不符合，它将保持默认的 "gone" 状态。
    }


    private void openDirectoryPicker(int requestCode) {
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
                getContentResolver().takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                );

                String pathName = getPathSegment(treeUri);

                if (requestCode == REQUEST_CODE_SOURCE) {
                    sourceUri = treeUri;
                    tvSourcePath.setText("已选择: " + pathName);
                } else if (requestCode == REQUEST_CODE_TARGET) {
                    targetUri = treeUri;
                    tvTargetPath.setText("已选择: " + pathName);
                }

                Toast.makeText(this, "文件夹已选择并授权。", Toast.LENGTH_SHORT).show();
                checkButtons();
            }
        }
    }

    private String getPathSegment(Uri uri) {
        DocumentFile doc = DocumentFile.fromTreeUri(this, uri);
        if (doc != null && doc.getName() != null) {
            return doc.getName();
        }

        String path = uri.getPath();
        if (path != null) {
            int index = path.lastIndexOf(':');
            if (index != -1 && index < path.length() - 1) {
                return path.substring(index + 1);
            }
        }
        return "未知路径";
    }


    private void checkButtons() {
        boolean ready = (sourceUri != null && targetUri != null);
        btnStart.setEnabled(ready);
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
        Toast.makeText(this, "监控服务已启动！", Toast.LENGTH_SHORT).show();
    }

    private void stopServiceFunc() {
        Intent serviceIntent = new Intent(this, MonitorService.class);
        serviceIntent.setAction("STOP");
        startService(serviceIntent);

        btnStart.setEnabled(true);
        btnStop.setEnabled(false);
        Toast.makeText(this, "监控服务已停止。", Toast.LENGTH_SHORT).show();
    }
}