package com.example.filecopier;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings; // 导入 Settings
import android.util.Log;
import android.view.Gravity;      // 导入 Gravity
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager; // 导入 WindowManager
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;

import java.util.Calendar;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final int REQUEST_CODE_SOURCE = 1001;
    private static final int REQUEST_CODE_TARGET = 1002;
    private static final int REQUEST_PERMISSION_NOTIFY = 2001;
    private static final int REQUEST_OVERLAY_PERMISSION = 3001; // 悬浮窗请求码

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
    // --------------------------

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
        updateServiceStatus();

        // 如果服务已经在后台运行，但应用被杀或重启，尝试重新显示悬浮窗
        if (MonitorService.isRunning() && isOverlayPermissionGranted()) {
            showFloatingWindow("监控正在运行...", 0x80000000); // 默认灰色
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkBatteryOptimizationStatus();
        checkOverlayPermissionStatus();
        updateServiceStatus();
    }

    @Override
    protected void onDestroy() {
        // 确保应用主界面关闭时，如果服务还在运行，悬浮窗不会被销毁
        // 只有当服务停止时，才应该调用 hideFloatingWindow()
        super.onDestroy();
    }

    // --- 悬浮窗管理 ---

    private boolean isOverlayPermissionGranted() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this);
    }

    /** * 显示悬浮窗。
     * @param message 悬浮窗上显示的文本
     * @param bgColor 悬浮窗背景颜色 (例如 0x80000000 半透明黑色)
     */
    private void showFloatingWindow(String message, int bgColor) {
        if (!isOverlayPermissionGranted()) {
            Log.w(TAG, "Cannot show floating window: Permission denied.");
            return;
        }

        if (floatingView == null) {
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

            // 悬浮窗内容：一个简单的 TextView
            TextView textView = new TextView(this);
            textView.setText(message);
            textView.setBackgroundColor(bgColor);
            textView.setTextColor(0xFFFFFFFF);
            textView.setPadding(10, 5, 10, 5);
            textView.setTextSize(12);
            textView.setGravity(Gravity.CENTER);
            floatingView = textView;

            // 设置 LayoutParams
            int layoutType;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            } else {
                // TYPE_PHONE 在低版本中也能工作
                layoutType = WindowManager.LayoutParams.TYPE_PHONE;
            }

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    layoutType,
                    // FLAG_NOT_FOCUSABLE: 不拦截点击事件
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    android.graphics.PixelFormat.TRANSLUCENT);

            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = 50; // 初始位置
            params.y = 100;

            try {
                windowManager.addView(floatingView, params);
            } catch (Exception e) {
                Log.e(TAG, "Error adding floating window: ", e);
            }
        } else {
            // 如果已存在，仅更新内容
            if (floatingView instanceof TextView) {
                ((TextView) floatingView).setText(message);
                floatingView.setBackgroundColor(bgColor);
            }
        }
    }

    /** 隐藏并移除悬浮窗 */
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
        // ... (保持不变，用于指导用户)
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

    /** 检查悬浮窗权限状态并更新按钮 */
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

    /** 请求忽略电池优化权限 */
    private void requestIgnoreBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // ... (保持不变)
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

    /** 跳转到悬浮窗权限设置 */
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

    // --- 服务状态更新 ---
    private void updateServiceStatus() {
        if (MonitorService.isRunning()) {
            tvServiceStatus.setText("状态: 🟢 监控正在运行");
            btnStart.setEnabled(false);
            btnStop.setEnabled(true);
        } else {
            tvServiceStatus.setText("状态: 🔴 服务未运行");
            checkButtons();
            btnStop.setEnabled(false);
        }
    }

    // --- 服务启动/停止 ---

    private void startServiceFunc() {
        if (sourceUri == null || targetUri == null) {
            Toast.makeText(this, "请先选择源文件夹和目标文件夹。", Toast.LENGTH_SHORT).show();
            return;
        }

        // 1. 启动悬浮窗（如果有权限）
        if (isOverlayPermissionGranted()) {
            showFloatingWindow("监控中...", 0x80000000); // 启动时显示默认状态
        } else {
            Toast.makeText(this, "悬浮窗权限未授予，后台运行可能不稳定！", Toast.LENGTH_LONG).show();
        }

        // 2. 启动服务
        Intent serviceIntent = new Intent(this, MonitorService.class);
        serviceIntent.putExtra("SOURCE_URI", sourceUri.toString());
        serviceIntent.putExtra("TARGET_URI", targetUri.toString());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        updateServiceStatus();
    }

    private void stopServiceFunc() {
        // 1. 停止服务
        stopService(new Intent(this, MonitorService.class));

        // 2. 隐藏悬浮窗
        hideFloatingWindow();

        updateServiceStatus();
        Toast.makeText(this, "监控服务已停止。", Toast.LENGTH_SHORT).show();
    }

    // --- 辅助方法 (loadPersistedUris, onActivityResult, getFolderName, checkButtons, openDirectoryPicker) ---
    // ... (保持与上一轮代码一致) ...
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
            checkOverlayPermissionStatus(); // 检查悬浮窗权限是否已授予
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