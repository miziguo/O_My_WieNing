package com.example.filecopier;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import com.example.filecopier.BuildConfig;

import java.io.File;
import java.util.Calendar;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final int REQUEST_PERMISSION_NOTIFY = 2001;
    private static final int REQUEST_OVERLAY_PERMISSION = 3001;
    private static final int REQUEST_MANAGE_EXTERNAL_STORAGE = 4001;

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_SOURCE_PATH = "sourcePath";
    private static final String KEY_TARGET_PATH = "targetPath";

    private TextView tvSourcePath, tvTargetPath, tvEasterEgg, tvServiceStatus;
    private Button btnSelectSource, btnSelectTarget, btnStart, btnStop, btnBatteryOptimization, btnStoragePermission;
    private CardView cvPermissions;

    private String sourcePath = null;
    private String targetPath = null;

    private ServiceStatusReceiver statusReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }

        // 1. 初始化UI (确保与 activity_main.xml 匹配)
        tvSourcePath = findViewById(R.id.tvSourcePath);
        tvTargetPath = findViewById(R.id.tvTargetPath);
        tvEasterEgg = findViewById(R.id.tvEasterEgg);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        btnSelectSource = findViewById(R.id.btnSelectSource);
        btnSelectTarget = findViewById(R.id.btnSelectTarget);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        // 确保使用正确的 ID
        btnStoragePermission = findViewById(R.id.btnOverlayPermission);
        cvPermissions = findViewById(R.id.cvPermissions);

        requestNotificationPermission();
        loadPersistedPaths();

        // 2. 绑定点击事件：现在弹出输入框
        btnSelectSource.setOnClickListener(v -> showPathInputDialog(KEY_SOURCE_PATH, "源文件夹路径"));
        btnSelectTarget.setOnClickListener(v -> showPathInputDialog(KEY_TARGET_PATH, "目标文件夹路径"));

        btnStart.setOnClickListener(v -> startServiceFunc());
        btnStop.setOnClickListener(v -> stopServiceFunc());
        btnBatteryOptimization.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        btnStoragePermission.setOnClickListener(v -> requestExternalStoragePermissionGuide());

        checkButtons();
        checkDateEasterEgg();

        statusReceiver = new ServiceStatusReceiver();
        TextView versionView = findViewById(R.id.version);
        versionView.setText("当前版本: " + BuildConfig.VERSION_NAME);
    }

    @Override
    protected void onResume() {
        super.onResume();

        IntentFilter filter = new IntentFilter(MonitorService.ACTION_SERVICE_STATUS);
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter);

        checkBatteryOptimizationStatus();
        checkStoragePermissionStatus();
        checkAllPermissions();
        updateServiceStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
    }

    /**
     * 弹出对话框让用户输入文件夹绝对路径
     */
    private void showPathInputDialog(String key, String title) {
        final EditText input = new EditText(this);
        String currentPath = key.equals(KEY_SOURCE_PATH) ? sourcePath : targetPath;
        if (currentPath != null) {
            input.setText(currentPath);
        } else {
            input.setText(Environment.getExternalStorageDirectory().getAbsolutePath() + "/");
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("请输入绝对路径，例如: /storage/emulated/0/MyFolder")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String path = input.getText().toString().trim();
                    if (path.isEmpty()) {
                        Toast.makeText(this, "路径不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 确保路径以斜杠开头
                    if (!path.startsWith("/")) {
                        path = "/" + path;
                    }

                    File dir = new File(path);
                    if (!dir.exists()) {
                        Toast.makeText(this, "警告：路径不存在，请确认路径是否正确且具有访问权限。", Toast.LENGTH_LONG).show();
                    }

                    savePath(key, path);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void savePath(String key, String path) {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(key, path);
        editor.apply();

        String folderName = getFolderName(path);

        if (key.equals(KEY_SOURCE_PATH)) {
            sourcePath = path;
            tvSourcePath.setText("源: " + folderName);
        } else if (key.equals(KEY_TARGET_PATH)) {
            targetPath = path;
            tvTargetPath.setText("目标: " + folderName);
        }

        Toast.makeText(this, folderName + " 路径设置成功", Toast.LENGTH_SHORT).show();
        checkButtons();
    }

    // --- 权限/稳定相关逻辑 ---

    private boolean isOverlayPermissionGranted() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this);
    }

    private boolean isManageExternalStorageGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void checkStoragePermissionStatus() {
        if (!isManageExternalStorageGranted()) {
            btnStoragePermission.setVisibility(View.VISIBLE);
            btnStoragePermission.setText("启用全部文件访问权限（核心功能）");
            btnStoragePermission.setOnClickListener(v -> requestExternalStoragePermissionGuide());
        } else if (!isOverlayPermissionGranted()){
            btnStoragePermission.setVisibility(View.VISIBLE);
            btnStoragePermission.setText("启用悬浮窗（稳定后台必备）");
            btnStoragePermission.setOnClickListener(v -> requestOverlayPermissionGuide());
        } else {
            btnStoragePermission.setVisibility(View.GONE);
        }
    }

    private void checkAllPermissions() {
        boolean allGranted = true;
        if (!isManageExternalStorageGranted()) allGranted = false;
        if (!isOverlayPermissionGranted()) allGranted = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                allGranted = false;
            }
        }
        
        if (allGranted) {
            cvPermissions.setVisibility(View.GONE);
        } else {
            cvPermissions.setVisibility(View.VISIBLE);
        }
    }

    private void requestExternalStoragePermissionGuide() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!isManageExternalStorageGranted()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE);
                } catch (Exception e) {
                    Toast.makeText(this, "无法打开权限设置，请手动前往设置中开启。", Toast.LENGTH_LONG).show();
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_MANAGE_EXTERNAL_STORAGE);
            }
        }
    }

    private void requestOverlayPermissionGuide() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isOverlayPermissionGranted()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开权限设置，请手动开启悬浮窗权限。", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void checkBatteryOptimizationStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            // 修改检查逻辑：只有在不在白名单中时才显示
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                btnBatteryOptimization.setVisibility(View.VISIBLE);
                btnBatteryOptimization.setText("修复后台运行问题 (点击手动设置“无限制”)");
            } else {
                btnBatteryOptimization.setVisibility(View.GONE);
            }
        } else {
            btnBatteryOptimization.setVisibility(View.GONE);
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // 直接跳转到应用详情页，引导用户手动设置，因为在部分机型上标准弹窗无法修改厂商的“智能后台”设置
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
                Toast.makeText(this, "请在“电池”或“省电策略”中手动选择“无限制”", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                // 如果跳转详情页失败，尝试使用标准的忽略电池优化申请
                Intent requestIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                requestIntent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(requestIntent);
                } catch (Exception ex) {
                    Toast.makeText(this, "无法打开设置页面，请手动前往系统设置。", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    // --- 服务启动/停止 ---

    private void updateServiceStatus() {
        if (MonitorService.isRunning()) {
            tvServiceStatus.setText("状态: 服务正在运行");
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
        } else {
            tvServiceStatus.setText("状态: 服务未运行");
            checkButtons();
            btnStop.setEnabled(false);
        }
    }

    private void startServiceFunc() {
        if (sourcePath == null || targetPath == null) {
            Toast.makeText(this, "请先选择源文件夹和目标文件夹。", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isManageExternalStorageGranted()) {
            Toast.makeText(this, "请先授予'所有文件访问权限'。", Toast.LENGTH_SHORT).show();
            requestExternalStoragePermissionGuide();
            return;
        }

        btnStart.setEnabled(false);

        if (!isOverlayPermissionGranted()) {
            Toast.makeText(this, "悬浮窗权限未授予，将无法显示实时状态。", Toast.LENGTH_LONG).show();
        }

        Intent serviceIntent = new Intent(this, MonitorService.class);
        serviceIntent.putExtra("SOURCE_PATH", sourcePath);
        serviceIntent.putExtra("TARGET_PATH", targetPath);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void stopServiceFunc() {
        btnStop.setEnabled(false);

        stopService(new Intent(this, MonitorService.class));

        Toast.makeText(this, "正在停止监控服务...", Toast.LENGTH_SHORT).show();
    }

    // --- 广播接收器 ---

    private class ServiceStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MonitorService.ACTION_SERVICE_STATUS.equals(intent.getAction())) {
                updateServiceStatus();
                // 可选：显示服务返回的消息
                String message = intent.getStringExtra(MonitorService.EXTRA_MESSAGE);
                if (message != null && !message.isEmpty() && !MonitorService.isRunning()) {
                    Toast.makeText(context, "服务停止原因: " + message, Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    // --- 辅助方法 (路径处理) ---

    private void loadPersistedPaths() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sourcePath = prefs.getString(KEY_SOURCE_PATH, null);
        targetPath = prefs.getString(KEY_TARGET_PATH, null);

        if (sourcePath != null) {
            tvSourcePath.setText("源: " + getFolderName(sourcePath));
        }
        if (targetPath != null) {
            tvTargetPath.setText("目标: " + getFolderName(targetPath));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // 如果是电池优化的请求返回，我们也检查一下
        // 虽然 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 无法通过 startActivityForResult 获得可靠结果
        // 但我们仍然可以在这里刷新 UI
        if (requestCode == REQUEST_OVERLAY_PERMISSION || requestCode == REQUEST_MANAGE_EXTERNAL_STORAGE) {
            checkStoragePermissionStatus();
            checkAllPermissions();
        }
    }

    private String getFolderName(String path) {
        if (path == null) return "未选择";
        try {
            File file = new File(path);
            return file.getName().isEmpty() ? path : file.getName();
        } catch (Exception e) {
            Log.e(TAG, "Error getting folder name from path: " + e.getMessage());
        }
        return "未知路径";
    }

    private void checkButtons() {
        btnStart.setEnabled(sourcePath != null && targetPath != null && !MonitorService.isRunning());
    }

    // --- 菜单和设置跳转 (已恢复) ---
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_PERMISSION_NOTIFY);
            }
        }
    }
    private void checkDateEasterEgg() {
        Calendar c = Calendar.getInstance();
        if (c.get(Calendar.MONTH) == Calendar.JUNE && c.get(Calendar.DAY_OF_MONTH) == 4) {
            if (tvEasterEgg != null) {
                tvEasterEgg.setText("铭记历史，勿忘六四");
                tvEasterEgg.setVisibility(View.VISIBLE);
            }
        }
    }
}