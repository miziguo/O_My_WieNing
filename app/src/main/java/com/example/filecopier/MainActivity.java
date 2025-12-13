package com.example.filecopier;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

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

        // 设置 ActionBar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
            getSupportActionBar().setBackgroundDrawable(new ColorDrawable(0xFF000000)); // 背景黑色
        }

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
        btnStoragePermission = findViewById(R.id.btnOverlayPermission); // 注意 XML ID
        cvPermissions = findViewById(R.id.cvPermissions);

        // 2. 权限请求和路径加载
        requestNotificationPermission();
        loadPersistedPaths();

        // 3. 绑定点击事件
        btnSelectSource.setOnClickListener(v -> showPathInputDialog(KEY_SOURCE_PATH, "源文件夹路径"));
        btnSelectTarget.setOnClickListener(v -> showPathInputDialog(KEY_TARGET_PATH, "目标文件夹路径"));

        btnStart.setOnClickListener(v -> startServiceFunc());

        // ★★★ 修复停止按钮：发送 ACTION_STOP ★★★
        btnStop.setOnClickListener(v -> stopServiceFunc());

        btnBatteryOptimization.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        btnStoragePermission.setOnClickListener(v -> requestExternalStoragePermissionGuide());

        checkButtons();
        checkDateEasterEgg();

        statusReceiver = new ServiceStatusReceiver();

        TextView versionView = findViewById(R.id.version);
        if (versionView != null) {
            versionView.setText("当前版本: " + BuildConfig.VERSION_NAME);
        }

        // 4. 显示欢迎弹窗
        showWelcomeDialog();
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

    // --- 菜单相关 ---
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

    // --- 路径输入对话框 ---
    private void showPathInputDialog(String key, String title) {
        final EditText input = new EditText(this);
        String currentPath = key.equals(KEY_SOURCE_PATH) ? sourcePath : targetPath;
        if (currentPath != null) {
            input.setText(currentPath);
        } else {
            input.setText(Environment.getExternalStorageDirectory().getAbsolutePath() + "/");
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage("请输入绝对路径，例如: /storage/emulated/0/MyFolder")
                .setView(input)
                .setPositiveButton("确定", (dialog, which) -> {
                    String path = input.getText().toString().trim();
                    if (path.isEmpty()) {
                        Toast.makeText(this, "路径不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (!path.startsWith("/")) {
                        path = "/" + path;
                    }

                    File dir = new File(path);
                    if (!dir.exists()) {
                        Toast.makeText(this, "警告：路径不存在，请确认路径是否正确。", Toast.LENGTH_LONG).show();
                    }

                    savePath(key, path);
                })
                .setNegativeButton("取消", null);

        AlertDialog dialog = builder.create();
        dialog.show();

        // 设置按钮颜色
        Button btnAgree = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button btnDisagree = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (btnDisagree != null) btnDisagree.setTextColor(0xFF000000);
        if (btnAgree != null) btnAgree.setTextColor(0xFF000000);
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

    // --- 欢迎弹窗 (每次必弹 + 10s强制) ---
    private void showWelcomeDialog() {
        // 1. 检查是否是第一次运行
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isFirstRun = prefs.getBoolean("isFirstRun", true);

        // 如果不是第一次运行，直接返回，不再弹窗
        if (!isFirstRun) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("使用条款")
                .setMessage("南娘是我们最好的朋友，请不要对南娘使用本软件。如果对南娘使用本软件被弄死了，软件作者概不负责\n\n©2023-2025 miziguo Studio")
                .setCancelable(false) // 强制必须点击按钮
                // 暂时不设置点击监听器，稍后在 show() 之后获取按钮来设置，以防止倒计时未结束就被点击
                .setNegativeButton("我同意 (10s)", null)
                .setPositiveButton("滚！", (dialog, which) -> {
                    finish(); // 退出软件
                    // System.exit(0); // 彻底杀掉进程（可选）
                });

        AlertDialog dialog = builder.create();
        dialog.show();

        // 获取按钮实例
        Button btnAgree = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button btnDisagree = dialog.getButton(AlertDialog.BUTTON_POSITIVE);

        // 设置按钮颜色
        btnDisagree.setTextColor(0xFFFF0000); // 红色
        btnAgree.setTextColor(0xFF888888);    // 初始灰色，表示不可用

        // 2. 初始禁用同意按钮
        btnAgree.setEnabled(false);

        // 3. 开始 10 秒倒计时
        new android.os.CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                // 更新按钮文字显示剩余秒数
                btnAgree.setText("我同意 (" + (millisUntilFinished / 1000 + 1) + "s)");
            }

            @Override
            public void onFinish() {
                // 倒计时结束
                btnAgree.setText("我同意");
                btnAgree.setEnabled(true);
                btnAgree.setTextColor(0xFF009900); // 变为绿色

                // 重新绑定点击事件（因为之前设为null了，或者为了安全起见）
                btnAgree.setOnClickListener(v -> {
                    // 4. 记录已经同意过条款
                    prefs.edit().putBoolean("isFirstRun", false).apply();
                    dialog.dismiss();
                });
            }
        }.start();
    }


    // --- 权限/电池优化 ---
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
            btnStoragePermission.setText("授予全部文件访问权限");
            btnStoragePermission.setOnClickListener(v -> requestExternalStoragePermissionGuide());
        } else if (!isOverlayPermissionGranted()){
            btnStoragePermission.setVisibility(View.VISIBLE);
            btnStoragePermission.setText("授予悬浮窗权限");
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

        cvPermissions.setVisibility(allGranted ? View.GONE : View.VISIBLE);
    }

    private void requestExternalStoragePermissionGuide() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!isManageExternalStorageGranted()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    startActivityForResult(intent, REQUEST_MANAGE_EXTERNAL_STORAGE);
                } catch (Exception e) {
                    Toast.makeText(this, "无法打开权限设置", Toast.LENGTH_LONG).show();
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
                Toast.makeText(this, "无法打开悬浮窗权限设置", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void checkBatteryOptimizationStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
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
            // 直接跳应用详情页，引导手动修改
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
                Toast.makeText(this, "请在“耗电管理”或“省电策略”中手动选择“无限制”", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                // 备用：标准弹窗
                Intent requestIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                requestIntent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(requestIntent);
                } catch (Exception ex) {
                    Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    // --- 服务控制 ---

    private void updateServiceStatus() {
        // 由于服务状态是异步的，这里主要依赖广播接收器，但保留基础判断
        // 如果想更准确，可以依赖 ServiceStatusReceiver
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

        if (!isOverlayPermissionGranted()) {
            Toast.makeText(this, "悬浮窗权限未授予，将无法显示实时状态。", Toast.LENGTH_SHORT).show();
            // 不阻断，但提示
        }

        btnStart.setEnabled(false);

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(MonitorService.ACTION_START);
        intent.putExtra("SOURCE_PATH", sourcePath);
        intent.putExtra("TARGET_PATH", targetPath);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopServiceFunc() {
        // ★★★ 发送 ACTION_STOP 指令 ★★★
        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(MonitorService.ACTION_STOP);
        startService(intent); // 触发 Service 的 onStartCommand 进行停止逻辑

        btnStop.setEnabled(false); // 暂时禁用，等待广播更新 UI
    }

    // --- 辅助逻辑 ---

    private void loadPersistedPaths() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        sourcePath = prefs.getString(KEY_SOURCE_PATH, null);
        targetPath = prefs.getString(KEY_TARGET_PATH, null);

        if (sourcePath != null) tvSourcePath.setText("源: " + getFolderName(sourcePath));
        if (targetPath != null) tvTargetPath.setText("目标: " + getFolderName(targetPath));
    }

    private void checkButtons() {
        btnStart.setEnabled(sourcePath != null && targetPath != null);
    }

    private String getFolderName(String path) {
        if (path == null) return "";
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash != -1 && lastSlash < path.length() - 1) {
            return path.substring(lastSlash + 1);
        }
        return path;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_PERMISSION_NOTIFY);
            }
        }
    }

    private void checkDateEasterEgg() {
        java.util.Calendar c = java.util.Calendar.getInstance();

        // 判断是否是 6月4日 (注意：Java中月份是从0开始的，Calendar.JUNE 其实是 5)
        boolean isJune4th = (c.get(java.util.Calendar.MONTH) == java.util.Calendar.JUNE)
                && (c.get(java.util.Calendar.DAY_OF_MONTH) == 4);

        if (tvEasterEgg != null) {
            if (isJune4th) {
                tvEasterEgg.setVisibility(View.VISIBLE);
                // 这一天显示特定的文案
                tvEasterEgg.setText("铭记历史，勿忘六四");
            } else {
                tvEasterEgg.setVisibility(View.GONE);
            }
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MANAGE_EXTERNAL_STORAGE) {
            checkStoragePermissionStatus();
            checkAllPermissions();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_EXTERNAL_STORAGE || requestCode == REQUEST_OVERLAY_PERMISSION) {
            checkStoragePermissionStatus();
            checkAllPermissions();
        }
    }

    // 广播接收器：更新UI状态
    private class ServiceStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MonitorService.ACTION_SERVICE_STATUS.equals(intent.getAction())) {
                boolean isRunning = intent.getBooleanExtra(MonitorService.EXTRA_IS_RUNNING, false);
                String msg = intent.getStringExtra(MonitorService.EXTRA_MESSAGE);

                tvServiceStatus.setText(msg != null ? msg : (isRunning ? "服务正在运行" : "服务已停止"));
                btnStart.setEnabled(!isRunning);
                btnStop.setEnabled(isRunning);

                // 如果已停止，重新检查按钮状态（防止路径未选好就被启用）
                if (!isRunning) {
                    checkButtons();
                }
            }
        }
    }
}
