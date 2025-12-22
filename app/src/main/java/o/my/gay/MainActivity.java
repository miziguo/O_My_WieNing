package o.my.gay;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private static final int REQUEST_PERMISSION_NOTIFY = 2001;
    private static final int REQUEST_OVERLAY_PERMISSION = 3001;
    private static final int REQUEST_MANAGE_EXTERNAL_STORAGE = 4001;

    private static final String PREFS_NAME = "AppPrefs";
    private static final String KEY_SOURCE_PATH = "sourcePath";
    private static final String KEY_TARGET_PATH = "targetPath";

    private TextView tvSourcePath, tvTargetPath, tvEasterEgg, tvServiceStatus;
    private Button btnSelectSource, btnSelectTarget, btnStart, btnStop, btnBatteryOptimization, btnStoragePermission, btnStartStorageTest;
    private CardView cvPermissions;

    private String sourcePath = null;
    private String targetPath = null;

    private ServiceStatusReceiver statusReceiver;
    private SharedPreferences monitorSettings;
    private String[] colorValues;
    private String[] textColorValues;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        monitorSettings = getSharedPreferences(SettingsActivity.PREF_NAME, Context.MODE_PRIVATE);
        colorValues = getResources().getStringArray(R.array.theme_color_values);
        textColorValues = getResources().getStringArray(R.array.text_color_values);

        // 1. 初始化UI
        tvSourcePath = findViewById(R.id.tvSourcePath);
        tvTargetPath = findViewById(R.id.tvTargetPath);
        tvEasterEgg = findViewById(R.id.tvEasterEgg);
        tvServiceStatus = findViewById(R.id.tv_service_status);

        btnSelectSource = findViewById(R.id.btnSelectSource);
        btnSelectTarget = findViewById(R.id.btnSelectTarget);

        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        btnStartStorageTest = findViewById(R.id.btnStartStorageTest);

        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        btnStoragePermission = findViewById(R.id.btnOverlayPermission);
        cvPermissions = findViewById(R.id.cvPermissions);

        // 应用主题颜色 (放在 UI 初始化之后)
        applyThemeColors();

        // 2. 权限请求和路径加载
        requestNotificationPermission();
        loadPersistedPaths();

        // 3. 绑定点击事件
        btnSelectSource.setOnClickListener(v -> showPathInputDialog(KEY_SOURCE_PATH, "源文件夹路径"));
        btnSelectTarget.setOnClickListener(v -> showPathInputDialog(KEY_TARGET_PATH, "目标文件夹路径"));

        btnStart.setOnClickListener(v -> startServiceFunc());
        btnStop.setOnClickListener(v -> stopServiceFunc());

        btnStartStorageTest.setOnClickListener(v -> startStorageTest());

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

    private void startStorageTest() {
        if (!isManageExternalStorageGranted()) {
            Toast.makeText(this, "请先授予'所有文件访问权限'。", Toast.LENGTH_SHORT).show();
            requestExternalStoragePermissionGuide();
            return;
        }

        new Thread(() -> {
            File gayDir = new File(Environment.getExternalStorageDirectory(), ".gay");
            if (!gayDir.exists()) {
                gayDir.mkdirs();
            }

            File largeFile = new File(gayDir, "large_file.bin");
            try {
                createLargeFile(largeFile, 1024 * 1024 * 1024); // 1GB
            } catch (IOException e) {
                Log.e(TAG, "Failed to create large file", e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "创建1GB文件失败", Toast.LENGTH_SHORT).show());
                return;
            }

            while (true) {
                StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getPath());
                long availableBytes = stat.getAvailableBytes();
                if (availableBytes < 1024 * 1024 * 1024) { // 1GB
                    break;
                }

                File newFile = new File(gayDir, "copy_" + System.currentTimeMillis() + ".bin");
                try {
                    copyFile(largeFile, newFile);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to copy file", e);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "复制文件失败", Toast.LENGTH_SHORT).show());
                    break;
                }
            }
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "硬盘压力测试完成", Toast.LENGTH_SHORT).show());
        }).start();
    }

    private void createLargeFile(File file, long size) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024 * 1024]; // 1MB buffer
            for (long i = 0; i < size; i += buffer.length) {
                out.write(buffer);
            }
        }
    }

    private void copyFile(File source, File dest) throws IOException {
        try (InputStream in = java.nio.file.Files.newInputStream(source.toPath());
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[1024 * 1024]; // 1MB buffer
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        // 每次恢复界面时重新应用主题颜色，以便从设置返回时立即生效
        applyThemeColors();
        
        IntentFilter filter = new IntentFilter(MonitorService.ACTION_SERVICE_STATUS);
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter);

        checkBatteryOptimizationStatus();
        checkStoragePermissionStatus();
        checkAllPermissions();
        updateServiceStatus();
    }

    // --- 核心修复：安全获取颜色方法 ---
    private int getThemeColorFromIndex(int index, String customKey) {
        // 如果选中的是最后一项（自定义）
        if (index == colorValues.length - 1) {
            String customColor = monitorSettings.getString(customKey, "#FF000000");
            try {
                return Color.parseColor(customColor);
            } catch (Exception e) {
                return Color.BLACK; // 解析失败默认黑色，防止崩溃
            }
        } 
        // 常规选项
        else if (index >= 0 && index < colorValues.length) {
            try {
                return Color.parseColor(colorValues[index]);
            } catch (Exception e) {
                return Color.BLACK;
            }
        }
        return Color.BLACK;
    }

    private void applyThemeColors() {
        // 读取配置索引
        int actionBarIndex = monitorSettings.getInt(SettingsActivity.KEY_ACTIONBAR_COLOR_INDEX, 7);
        int buttonIndex = monitorSettings.getInt(SettingsActivity.KEY_BUTTON_COLOR_INDEX, 7);
        int textColorIndex = monitorSettings.getInt(SettingsActivity.KEY_TEXT_COLOR_INDEX, 2);
        
        // 获取实际颜色值
        int actionBarColor = getThemeColorFromIndex(actionBarIndex, SettingsActivity.KEY_CUSTOM_ACTIONBAR_COLOR);
        int buttonColor = getThemeColorFromIndex(buttonIndex, SettingsActivity.KEY_CUSTOM_BUTTON_COLOR);
        
        int textColor = Color.WHITE;
        if (textColorIndex >= 0 && textColorIndex < textColorValues.length) {
            try {
                textColor = Color.parseColor(textColorValues[textColorIndex]);
            } catch (Exception e) { textColor = Color.WHITE; }
        }

        // 1. 设置 ActionBar 背景颜色和文字颜色
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setBackgroundDrawable(new ColorDrawable(actionBarColor));
            
            // 动态设置 Title 颜色
            CharSequence title = actionBar.getTitle();
            if (title == null) title = getString(R.string.app_name); // 兜底
            
            SpannableString text = new SpannableString(title);
            text.setSpan(new ForegroundColorSpan(textColor), 0, text.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            actionBar.setTitle(text);
        }

        // 2. 设置状态栏颜色 (同步 ActionBar 颜色)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(actionBarColor);
        }

        // 3. 设置按钮颜色
        ColorStateList colorStateList = ColorStateList.valueOf(buttonColor);
        applyButtonColor(btnStart, colorStateList, textColor);
        applyButtonColor(btnStop, colorStateList, textColor);
        applyButtonColor(btnSelectSource, colorStateList, textColor);
        applyButtonColor(btnSelectTarget, colorStateList, textColor);
    }

    private void applyButtonColor(Button btn, ColorStateList backgroundTint, int textColor) {
        if (btn != null) {
            btn.setBackgroundTintList(backgroundTint);
            btn.setTextColor(textColor);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
    }

    // ... (后续方法保持不变: onCreateOptionsMenu, onOptionsItemSelected, showPathInputDialog, savePath, showWelcomeDialog 等) ...

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

    private void showWelcomeDialog() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        boolean isFirstRun = prefs.getBoolean("isFirstRun", true);
        if (!isFirstRun) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("使用条款")
                .setMessage("南娘是我们最好的朋友，请不要对南娘使用本软件。如果对南娘使用本软件被弄死了，软件作者概不负责\n\n©2023-2025 miziguo Studio")
                .setCancelable(false)
                .setNegativeButton("我同意 (10s)", null)
                .setPositiveButton("滚！", (dialog, which) -> {
                    finish();
                });

        AlertDialog dialog = builder.create();
        dialog.show();

        Button btnAgree = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button btnDisagree = dialog.getButton(AlertDialog.BUTTON_POSITIVE);

        btnDisagree.setTextColor(0xFFFF0000);
        btnAgree.setTextColor(0xFF888888);
        btnAgree.setEnabled(false);

        new android.os.CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                btnAgree.setText("我同意 (" + (millisUntilFinished / 1000 + 1) + "s)");
            }

            @Override
            public void onFinish() {
                btnAgree.setText("我同意");
                btnAgree.setEnabled(true);
                btnAgree.setTextColor(0xFF009900);
                btnAgree.setOnClickListener(v -> {
                    prefs.edit().putBoolean("isFirstRun", false).apply();
                    dialog.dismiss();
                });
            }
        }.start();
    }

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
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            try {
                startActivity(intent);
                Toast.makeText(this, "请在“耗电管理”或“省电策略”中手动选择“无限制”", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
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

    private void updateServiceStatus() {
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
        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(MonitorService.ACTION_STOP);
        startService(intent);
        btnStop.setEnabled(false);
    }

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
        boolean isJune4th = (c.get(java.util.Calendar.MONTH) == java.util.Calendar.JUNE)
                && (c.get(java.util.Calendar.DAY_OF_MONTH) == 4);
        if (tvEasterEgg != null) {
            if (isJune4th) {
                tvEasterEgg.setVisibility(View.VISIBLE);
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

    private class ServiceStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MonitorService.ACTION_SERVICE_STATUS.equals(intent.getAction())) {
                boolean isRunning = intent.getBooleanExtra(MonitorService.EXTRA_IS_RUNNING, false);
                String msg = intent.getStringExtra(MonitorService.EXTRA_MESSAGE);
                tvServiceStatus.setText(msg != null ? msg : (isRunning ? "服务正在运行" : "服务已停止"));
                btnStart.setEnabled(!isRunning);
                btnStop.setEnabled(isRunning);
                if (!isRunning) {
                    checkButtons();
                }
            }
        }
    }
}
