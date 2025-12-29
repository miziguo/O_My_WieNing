package o.my.wiening;

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
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
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
    private CardView cardViewWarning, cardViewStatus, cardViewPaths, cardViewControls, cvPermissions, cardViewVersion, cardViewAbout;
    private View mainRootLayout;

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
        mainRootLayout = findViewById(R.id.main_root_layout);
        cardViewWarning = findViewById(R.id.card_view_warning);
        cardViewStatus = findViewById(R.id.card_view_status);
        cardViewPaths = findViewById(R.id.card_view_paths);
        cardViewControls = findViewById(R.id.card_view_controls);
        cvPermissions = findViewById(R.id.cvPermissions);
        cardViewVersion = findViewById(R.id.card_view_version);
        cardViewAbout = findViewById(R.id.card_view_about);
        
        tvSourcePath = findViewById(R.id.tvSourcePath);
        tvTargetPath = findViewById(R.id.tvTargetPath);
        tvEasterEgg = findViewById(R.id.tvEasterEgg);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        btnSelectSource = findViewById(R.id.btnSelectSource);
        btnSelectTarget = findViewById(R.id.btnSelectTarget);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        btnStoragePermission = findViewById(R.id.btnOverlayPermission);

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
        applyThemeColors(); // 确保每次返回都应用最新主题
        IntentFilter filter = new IntentFilter(MonitorService.ACTION_SERVICE_STATUS);
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, filter);

        checkPermissionsAndStability(); // 统一的权限检查
        updateServiceStatus();
    }

    private int getThemeColorFromIndex(int index, String customKey) {
        if (index == colorValues.length - 1) {
            String customColor = monitorSettings.getString(customKey, "#FF000000");
            try { return Color.parseColor(customColor); } catch (Exception e) { return Color.BLACK; }
        } else if (index >= 0 && index < colorValues.length) {
            try { return Color.parseColor(colorValues[index]); } catch (Exception e) { return Color.BLACK; }
        }
        return Color.BLACK;
    }
    private int getTextColorFromIndex(int index) {
        if (index == textColorValues.length - 1) {
            String customColor = monitorSettings.getString(SettingsActivity.KEY_CUSTOM_TEXT_COLOR, "#FFFFFFFF");
            try { return Color.parseColor(customColor); } catch (Exception e) { return Color.WHITE; }
        } else if (index >= 0 && index < textColorValues.length) {
            try { return Color.parseColor(textColorValues[index]); } catch (Exception e) { return Color.WHITE; }
        }
        return Color.WHITE;
    }

    private void applyThemeColors() {
        int actionBarIndex = monitorSettings.getInt(SettingsActivity.KEY_ACTIONBAR_COLOR_INDEX, 7);
        int buttonIndex = monitorSettings.getInt(SettingsActivity.KEY_BUTTON_COLOR_INDEX, 7);
        int textColorIndex = monitorSettings.getInt(SettingsActivity.KEY_TEXT_COLOR_INDEX, 2);
        int cardAlpha = monitorSettings.getInt(SettingsActivity.KEY_CARD_ALPHA, 255);
        
        int actionBarColor = getThemeColorFromIndex(actionBarIndex, SettingsActivity.KEY_CUSTOM_ACTIONBAR_COLOR);
        int buttonColor = getThemeColorFromIndex(buttonIndex, SettingsActivity.KEY_CUSTOM_BUTTON_COLOR);
        int textColor = getTextColorFromIndex(textColorIndex);

        // 1. ActionBar & Status Bar
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setBackgroundDrawable(new ColorDrawable(actionBarColor));
            CharSequence title = actionBar.getTitle() != null ? actionBar.getTitle() : getString(R.string.app_name);
            SpannableString text = new SpannableString(title);
            text.setSpan(new ForegroundColorSpan(textColor), 0, text.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            actionBar.setTitle(text);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(actionBarColor);
        }

        // 2. Buttons
        ColorStateList colorStateList = ColorStateList.valueOf(buttonColor);
        applyButtonColor(btnStart, colorStateList, textColor);
        applyButtonColor(btnStop, colorStateList, textColor);
        applyButtonColor(btnSelectSource, colorStateList, textColor);
        applyButtonColor(btnSelectTarget, colorStateList, textColor);

        // 3. Background Image & Card Alpha
        applyBackgroundImage();
        applyCardAlpha(cardAlpha);
    }
    
    private void applyBackgroundImage() {
        String uriString = monitorSettings.getString(SettingsActivity.KEY_BACKGROUND_IMAGE_URI, null);
        if (uriString != null) {
            try {
                Uri imageUri = Uri.parse(uriString);
                Drawable background = Drawable.createFromStream(getContentResolver().openInputStream(imageUri), uriString);
                mainRootLayout.setBackground(background);
            } catch (Exception e) {
                mainRootLayout.setBackgroundColor(Color.WHITE);
            }
        } else {
            mainRootLayout.setBackgroundColor(Color.WHITE);
        }
    }

    private void applyCardAlpha(int alpha) {
        int color = Color.argb(alpha, 255, 255, 255);
        if(cardViewWarning != null) cardViewWarning.setCardBackgroundColor(color);
        if(cardViewStatus != null) cardViewStatus.setCardBackgroundColor(color);
        if(cardViewPaths != null) cardViewPaths.setCardBackgroundColor(color);
        if(cardViewControls != null) cardViewControls.setCardBackgroundColor(color);
        if(cvPermissions != null) cvPermissions.setCardBackgroundColor(color);
        if(cardViewVersion != null) cardViewVersion.setCardBackgroundColor(color);
        if(cardViewAbout != null) cardViewAbout.setCardBackgroundColor(color);
    }

    private void applyButtonColor(Button btn, ColorStateList backgroundTint, int textColor) {
        if (btn != null) {
            btn.setBackgroundTintList(backgroundTint);
            btn.setTextColor(textColor);
        }
    }

    private void checkPermissionsAndStability() {
        boolean manageStorageGranted = isManageExternalStorageGranted();
        boolean overlayGranted = isOverlayPermissionGranted();
        boolean batteryOptIgnored = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            batteryOptIgnored = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        }

        if (manageStorageGranted && overlayGranted && batteryOptIgnored) {
            if (cvPermissions != null) {
                cvPermissions.setVisibility(View.GONE);
            }
            return;
        }

        if (cvPermissions != null) {
            cvPermissions.setVisibility(View.VISIBLE);
        }

        if (btnStoragePermission != null) {
            if (!manageStorageGranted) {
                btnStoragePermission.setVisibility(View.VISIBLE);
                btnStoragePermission.setText("📁 启用全部文件访问权限（核心功能）");
                btnStoragePermission.setOnClickListener(v -> requestExternalStoragePermissionGuide());
            } else if (!overlayGranted) {
                btnStoragePermission.setVisibility(View.VISIBLE);
                btnStoragePermission.setText("🛰️ 启用悬浮窗权限（稳定后台）");
                btnStoragePermission.setOnClickListener(v -> requestOverlayPermissionGuide());
            } else {
                btnStoragePermission.setVisibility(View.GONE);
            }
        }
        
        if (btnBatteryOptimization != null) {
            if (!batteryOptIgnored) {
                btnBatteryOptimization.setVisibility(View.VISIBLE);
                btnBatteryOptimization.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
            } else {
                btnBatteryOptimization.setVisibility(View.GONE);
            }
        }
    }

    @Override protected void onPause() { super.onPause(); LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver); }
    @Override public boolean onCreateOptionsMenu(Menu menu) { getMenuInflater().inflate(R.menu.main_menu, menu); return true; }
    @Override public boolean onOptionsItemSelected(MenuItem item) { if (item.getItemId() == R.id.action_settings) { startActivity(new Intent(this, SettingsActivity.class)); return true; } return super.onOptionsItemSelected(item); }
    
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

    private boolean isOverlayPermissionGranted() { return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.canDrawOverlays(this); }
    private boolean isManageExternalStorageGranted() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { return Environment.isExternalStorageManager(); } else { return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED; } }
    
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
    void requestIgnoreBatteryOptimizations() {
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
    private void updateServiceStatus() { /*...*/ }
    private void startServiceFunc() { /*...*/ }
    private void stopServiceFunc() { /*...*/ }
    private void loadPersistedPaths() { /*...*/ }
    private void checkButtons() { /*...*/ }
    private String getFolderName(String path) { if (path == null) return ""; int lastSlash = path.lastIndexOf('/'); if (lastSlash != -1 && lastSlash < path.length() - 1) { return path.substring(lastSlash + 1); } return path; }
    private void requestNotificationPermission() { /*...*/ }
    private void checkDateEasterEgg() { /*...*/ }
    @Override public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) { /*...*/
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
    @Override protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) { /*...*/
        super.onActivityResult(requestCode, resultCode, data);
    }
    private class ServiceStatusReceiver extends BroadcastReceiver { @Override public void onReceive(Context context, Intent intent) { /*...*/ } }
}