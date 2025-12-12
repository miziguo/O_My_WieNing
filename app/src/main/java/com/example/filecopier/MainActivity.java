package com.example.filecopier;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.Calendar;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final int REQUEST_CODE_SOURCE = 1001;
    private static final int REQUEST_CODE_TARGET = 1002;
    private static final int REQUEST_PERMISSION_NOTIFY = 2001;
    private static final int REQUEST_OVERLAY_PERMISSION = 3001;

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_SOURCE_URI = "sourceUri";
    private static final String KEY_TARGET_URI = "targetUri";

    private TextView tvSourcePath, tvTargetPath, tvEasterEgg, tvServiceStatus;
    private Button btnSelectSource, btnSelectTarget, btnStart, btnStop, btnBatteryOptimization, btnOverlayPermission;
    private Uri sourceUri = null;
    private Uri targetUri = null;

    // --- 悬浮窗相关成员变量 ---
    private WindowManager windowManager;
    private View floatingView;

    // --- 广播接收器 ---
    private ServiceStatusReceiver statusReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 初始化UI
        tvSourcePath = findViewById(R.id.tvSourcePath);
        tvTargetPath = findViewById(R.id.tvTargetPath);
        tvEasterEgg = findViewById(R.id.tvEasterEgg);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        btnSelectSource = findViewById(R.id.btnSelectSource);
        btnSelectTarget = findViewById(R.id.btnSelectTarget);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        btnOverlayPermission = findViewById(R.id.btnOverlayPermission);

        requestNotificationPermission();
        loadPersistedUris();

        // 2. 绑定点击事件
        btnSelectSource.setOnClickListener(v -> openDirectoryPicker(REQUEST_CODE_SOURCE));
        btnSelectTarget.setOnClickListener(v -> openDirectoryPicker(REQUEST_CODE_TARGET));
        btnStart.setOnClickListener(v -> startServiceFunc());
        btnStop.setOnClickListener(v -> stopServiceFunc());
        btnBatteryOptimization.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        btnOverlayPermission.setOnClickListener(v -> requestOverlayPermissionGuide());

        checkButtons();
        checkDateEasterEgg();

        statusReceiver = new ServiceStatusReceiver(); // 初始化接收器

        // 如果服务已经在后台运行，尝试重新显示悬浮窗
        if (MonitorService.isRunning() && isOverlayPermissionGranted()) {
            showFloatingWindow("监控正在运行...", 0x80000000);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // 注册广播接收器
        IntentFilter filter = new IntentFilter(MonitorService.ACTION_SERVICE_STATUS);
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter);

        checkBatteryOptimizationStatus();
        checkOverlayPermissionStatus();
        updateServiceStatus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 取消注册广播接收器
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
    }

    // --- 悬浮窗管理 ---

    private boolean isOverlayPermissionGranted() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this);
    }

    private void showFloatingWindow(String message, int bgColor) {
        if (!isOverlayPermissionGranted()) {
            Log.w(TAG, "Cannot show floating window: Permission denied.");
            return;
        }

        if (floatingView == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            TextView textView = new TextView(this);
            textView.setText(message);
            textView.setBackgroundColor(bgColor);
            textView.setTextColor(0xFFFFFFFF);
            textView.setPadding(10, 5, 10, 5);
            textView.setTextSize(12);
            textView.setGravity(Gravity.CENTER);
            floatingView = textView;

            int layoutType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                layoutType = WindowManager.LayoutParams.TYPE_PHONE;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    android.graphics.PixelFormat.TRANSLUCENT);

            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = 50;
            params.y = 100;

            try {
                windowManager.addView(floatingView, params);
            } catch (Exception e) {
                Log.e(TAG, "Error adding floating window: ", e);
            }
        } else {
            if (floatingView instanceof TextView) {
                ((TextView) floatingView).setText(message);
                floatingView.setBackgroundColor(bgColor);
            }
        }
    }

    private void hideFloatingWindow() {
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
                Log.d(TAG, "Floating window removed.");
            } catch (Exception e) {
                Log.w(TAG, "Error removing floating window (might be already removed): " + e.getMessage());
            }
        }
        floatingView = null;
        windowManager = null;
    }

    // --- 权限/稳定相关逻辑 ---

    private void checkBatteryOptimizationStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                btnBatteryOptimization.setVisibility(View.VISIBLE);
                btnBatteryOptimization.setText("🔴 修复后台运行问题 (点击设置白名单)");
            } else {
                btnBatteryOptimization.setVisibility(View.GONE);
            }
        } else {
            btnBatteryOptimization.setVisibility(View.GONE);
        }
    }

    private void checkOverlayPermissionStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isOverlayPermissionGranted()) {
                btnOverlayPermission.setVisibility(View.VISIBLE);
                btnOverlayPermission.setText("🔔 启用悬浮窗（稳定后台必备）");
            } else {
                btnOverlayPermission.setVisibility(View.GONE);
            }
        } else {
            btnOverlayPermission.setVisibility(View.GONE);
        }
    }

    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent();
            String packageName = getPackageName();
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);

            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                intent.setAction(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + packageName));
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "请手动前往系统设置 > 电池 > 忽略优化列表，将本应用加入白名单。", Toast.LENGTH_LONG).show();
                    Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    fallback.setData(Uri.parse("package:" + packageName));
                    startActivity(fallback);
                }
            } else {
                Toast.makeText(this, "应用已在白名单中，无需再次设置。", Toast.LENGTH_SHORT).show();
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

    // --- 服务状态更新 (依赖广播) ---
    private void updateServiceStatus() {
        if (MonitorService.isRunning()) {
            tvServiceStatus.setText("状态: 🟢 监控正在运行");
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
        } else {
            tvServiceStatus.setText("状态: 🔴 服务未运行");
            checkButtons(); // 检查URI是否设置，以决定是否启用启动按钮
            btnStop.setEnabled(false);
        }
    }

    // --- 服务启动/停止 ---

    private void startServiceFunc() {
        if (sourceUri == null || targetUri == null) {
            Toast.makeText(this, "请先选择源文件夹和目标文件夹。", Toast.LENGTH_SHORT).show();
            return;
        }

        btnStart.setEnabled(false); // 立即禁用开始按钮，防止双击

        if (isOverlayPermissionGranted()) {
            showFloatingWindow("监控中...", 0x80000000);
        } else {
            Toast.makeText(this, "悬浮窗权限未授予，后台运行可能不稳定！", Toast.LENGTH_LONG).show();
        }

        Intent serviceIntent = new Intent(this, MonitorService.class);
        serviceIntent.putExtra("SOURCE_URI", sourceUri.toString());
        serviceIntent.putExtra("TARGET_URI", targetUri.toString());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        // 按钮状态将由 MonitorService 启动后发出的广播来更新
    }

    private void stopServiceFunc() {
        btnStop.setEnabled(false); // 立即禁用停止按钮，防止双击

        stopService(new Intent(this, MonitorService.class));

        hideFloatingWindow();

        // 按钮状态将由 MonitorService 停止后发出的广播来更新
        Toast.makeText(this, "正在停止监控服务...", Toast.LENGTH_SHORT).show();
    }

    // --- 广播接收器 ---

    /** 用于接收 MonitorService 状态更新的广播接收器 */
    private class ServiceStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MonitorService.ACTION_SERVICE_STATUS.equals(intent.getAction())) {
                boolean isRunning = intent.getBooleanExtra(MonitorService.EXTRA_RUNNING, false);

                // 收到服务状态变更，立即更新 UI
                updateServiceStatus();

                // 如果服务在运行，则根据状态更新悬浮窗提示
                if (isRunning && isOverlayPermissionGranted()) {
                    String statusMsg = intent.getStringExtra(MonitorService.EXTRA_MESSAGE);
                    if (statusMsg != null) {
                        // 红色：错误；绿色：复制成功；灰色：监控中
                        int color = statusMsg.contains("致命错误") ? 0x80FF0000 :
                                statusMsg.contains("复制了新文件") ? 0x8000FF00 : 0x80000000;
                        showFloatingWindow(statusMsg, color);
                    }
                } else if (!isRunning) {
                    hideFloatingWindow();
                }
            }
        }
    }

    // --- 辅助方法 (loadPersistedUris, onActivityResult, getFolderName, checkButtons, openDirectoryPicker) ---
    private void openDirectoryPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }
    private void loadPersistedUris() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return;
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String srcStr = prefs.getString(KEY_SOURCE_URI, null);
        String dstStr = prefs.getString(KEY_TARGET_URI, null);
        if (srcStr != null) {
            Uri loadedUri = Uri.parse(srcStr);
            if (isUriPermissionPersisted(loadedUri)) {
                sourceUri = loadedUri;
                tvSourcePath.setText("源: " + getFolderName(sourceUri));
            } else {
                prefs.edit().remove(KEY_SOURCE_URI).apply();
            }
        }
        if (dstStr != null) {
            Uri loadedUri = Uri.parse(dstStr);
            if (isUriPermissionPersisted(loadedUri)) {
                targetUri = loadedUri;
                tvTargetPath.setText("目标: " + getFolderName(targetUri));
            } else {
                prefs.edit().remove(KEY_TARGET_URI).apply();
            }
        }
    }
    private boolean isUriPermissionPersisted(Uri uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return false;
        List<UriPermission> perms = getContentResolver().getPersistedUriPermissions();
        for (UriPermission perm : perms) {
            if (perm.getUri().equals(uri)) return perm.isReadPermission() && perm.isWritePermission();
        }
        return false;
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OVERLAY_PERMISSION) {
            checkOverlayPermissionStatus();
        }
        if (resultCode == Activity.RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    getContentResolver().takePersistableUriPermission(treeUri, takeFlags);
                }
                SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
                String folderName = getFolderName(treeUri);
                if (requestCode == REQUEST_CODE_SOURCE) {
                    sourceUri = treeUri;
                    tvSourcePath.setText("源: " + folderName);
                    editor.putString(KEY_SOURCE_URI, sourceUri.toString());
                } else if (requestCode == REQUEST_CODE_TARGET) {
                    targetUri = treeUri;
                    tvTargetPath.setText("目标: " + folderName);
                    editor.putString(KEY_TARGET_URI, targetUri.toString());
                }
                editor.apply();
                Toast.makeText(this, "文件夹授权成功", Toast.LENGTH_SHORT).show();
                checkButtons();
            }
        }
    }
    private String getFolderName(Uri uri) {
        try {
            DocumentFile root = DocumentFile.fromTreeUri(this, uri);
            if (root != null && root.getName() != null) return root.getName();
        } catch (Exception e) {
            Log.e(TAG, "Error getting folder name: " + e.getMessage());
        }
        return "未知文件夹";
    }
    private void checkButtons() {
        btnStart.setEnabled(sourceUri != null && targetUri != null);
    }
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
            if (tvEasterEgg != null) tvEasterEgg.setVisibility(View.VISIBLE);
        }
    }
}