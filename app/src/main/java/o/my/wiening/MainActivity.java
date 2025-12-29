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
import android.view.LayoutInflater;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.Serializable;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String PREFS_NAME = "AppPrefs";

    // --- UI Elements ---
    private TextView tvEasterEgg, tvServiceStatus;
    private Button btnStart, btnStop, btnBatteryOptimization, btnStoragePermission;
    private CardView cardViewWarning, cardViewStatus, cardViewControls, cvPermissions, cardViewVersion, cardViewAbout;
    private View mainRootLayout;
    private RecyclerView recyclerView;
    private FloatingActionButton fabAddGroup;

    // --- Data & Adapters ---
    private MonitorGroupAdapter adapter;
    private List<MonitorGroup> monitorGroups = new ArrayList<>();
    private ServiceStatusReceiver statusReceiver;
    private SharedPreferences monitorSettings;

    // --- Theme values ---
    private String[] colorValues;
    private String[] textColorValues;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- 初始化 ---
        initSharedPreferences();
        initViews();
        applyThemeColors(); // 应用主题必须在 initViews 之后
        initRecyclerView();
        initListeners();

        // --- 加载数据和状态 ---
        requestNotificationPermission();
        loadMonitorGroups();
        checkDateEasterEgg();
        setupBroadcastReceiver();

        // 显示欢迎弹窗
        showWelcomeDialog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyThemeColors();
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, new IntentFilter(MonitorService.ACTION_SERVICE_STATUS));
        checkPermissionsAndStability();
        updateServiceStatusUI(MonitorService.isRunning(), "未设置参数"); // 每次返回界面时更新UI
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
    }

    // --- 初始化方法 ---

    private void initSharedPreferences() {
        monitorSettings = getSharedPreferences(SettingsActivity.PREF_NAME, Context.MODE_PRIVATE);
        colorValues = getResources().getStringArray(R.array.theme_color_values);
        textColorValues = getResources().getStringArray(R.array.text_color_values);
    }

    private void initViews() {
        mainRootLayout = findViewById(R.id.main_root_layout);
        cardViewWarning = findViewById(R.id.card_view_warning);
        cardViewStatus = findViewById(R.id.card_view_status);
        // cardViewPaths 不再需要单独控制，由 RecyclerView 管理
        cardViewControls = findViewById(R.id.card_view_controls);
        cvPermissions = findViewById(R.id.cvPermissions);
        cardViewVersion = findViewById(R.id.card_view_version);
        cardViewAbout = findViewById(R.id.card_view_about);

        tvEasterEgg = findViewById(R.id.tvEasterEgg);
        tvServiceStatus = findViewById(R.id.tv_service_status);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);
        btnBatteryOptimization = findViewById(R.id.btnBatteryOptimization);
        btnStoragePermission = findViewById(R.id.btnOverlayPermission);
        fabAddGroup = findViewById(R.id.fab_add_group); // FAB for adding new group
        recyclerView = findViewById(R.id.rv_monitor_groups); // RecyclerView

        TextView versionView = findViewById(R.id.version);
        if (versionView != null) {
            versionView.setText("当前版本: " + BuildConfig.VERSION_NAME + "\n此版本为内部测试版本，正在开发中");
        }
    }

    private void initRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MonitorGroupAdapter(monitorGroups, position -> {
            // 删除按钮的点击事件
            new AlertDialog.Builder(this)
                    .setTitle("确认删除")
                    .setMessage("您确定要删除这个监控组吗？")
                    .setPositiveButton("删除", (dialog, which) -> {
                        monitorGroups.remove(position);
                        adapter.notifyItemRemoved(position);
                        adapter.notifyItemRangeChanged(position, monitorGroups.size()); // 更新后续项的位置
                        saveMonitorGroups(); // 保存更改
                        checkButtons(); // 检查开始按钮状态
                    })
                    .setNegativeButton("取消", null)
                    .show();
        });
        recyclerView.setAdapter(adapter);
    }

    private void initListeners() {
        btnStart.setOnClickListener(v -> startServiceFunc());
        btnStop.setOnClickListener(v -> stopServiceFunc());
        fabAddGroup.setOnClickListener(v -> showAddGroupDialog());
        btnBatteryOptimization.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
        btnStoragePermission.setOnClickListener(v -> requestExternalStoragePermissionGuide());
    }

    private void setupBroadcastReceiver() {
        statusReceiver = new ServiceStatusReceiver();
    }

    // --- 主题和UI应用 ---

    private void applyThemeColors() {
        int actionBarIndex = monitorSettings.getInt(SettingsActivity.KEY_ACTIONBAR_COLOR_INDEX, 7);
        int buttonIndex = monitorSettings.getInt(SettingsActivity.KEY_BUTTON_COLOR_INDEX, 7);
        int textColorIndex = monitorSettings.getInt(SettingsActivity.KEY_TEXT_COLOR_INDEX, 2);
        int cardAlpha = monitorSettings.getInt(SettingsActivity.KEY_CARD_ALPHA, 255);

        int actionBarColor = getThemeColorFromIndex(actionBarIndex, SettingsActivity.KEY_CUSTOM_ACTIONBAR_COLOR);
        int buttonColor = getThemeColorFromIndex(buttonIndex, SettingsActivity.KEY_CUSTOM_BUTTON_COLOR);
        int textColor = getTextColorFromIndex(textColorIndex);

        // ActionBar & Status Bar
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setBackgroundDrawable(new ColorDrawable(actionBarColor));
            SpannableString text = new SpannableString(actionBar.getTitle() != null ? actionBar.getTitle() : getString(R.string.app_name));
            text.setSpan(new ForegroundColorSpan(textColor), 0, text.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            actionBar.setTitle(text);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(actionBarColor);
        }

        // Buttons and FAB
        ColorStateList colorStateList = ColorStateList.valueOf(buttonColor);
        applyButtonColor(btnStart, colorStateList, textColor);
        applyButtonColor(btnStop, colorStateList, textColor);
        if(fabAddGroup != null) fabAddGroup.setBackgroundTintList(colorStateList);
        // ... (对其他按钮应用)

        // Background & Card Alpha
        applyBackgroundImage();
        applyCardAlpha(cardAlpha);
    }

    // ... (其他 applyXXX, getXXXColorFromIndex 方法保持不变) ...
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

    // --- 核心逻辑：多组路径管理 ---

    private void showAddGroupDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_add_group, null); // 需要创建一个 dialog_add_group.xml 布局

        final EditText etSource = dialogView.findViewById(R.id.et_dialog_source);
        final EditText etTarget = dialogView.findViewById(R.id.et_dialog_target);

        new AlertDialog.Builder(this)
                .setTitle("添加监控组")
                .setView(dialogView)
                .setPositiveButton("添加", (dialog, which) -> {
                    String source = etSource.getText().toString().trim();
                    String target = etTarget.getText().toString().trim();
                    if (source.isEmpty() || target.isEmpty()) {
                        Toast.makeText(this, "源路径和目标路径都不能为空", Toast.LENGTH_SHORT).show();
                    } else {
                        addAndSaveGroup(new MonitorGroup(source, target));
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addAndSaveGroup(MonitorGroup group) {
        monitorGroups.add(group);
        adapter.notifyItemInserted(monitorGroups.size() - 1);
        saveMonitorGroups();
        checkButtons();
    }

    private void loadMonitorGroups() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString("monitor_groups_json", null);
        if (json != null) {
            Gson gson = new Gson();
            // ★★★ 使用正确的 Gson TypeToken ★★★
            Type type = new com.google.gson.reflect.TypeToken<ArrayList<MonitorGroup>>() {}.getType();
            List<MonitorGroup> loadedGroups = gson.fromJson(json, type);
            if (loadedGroups != null) {
                monitorGroups.clear();
                monitorGroups.addAll(loadedGroups);
                adapter.notifyDataSetChanged();
            }
        }
        checkButtons();
    }


    private void saveMonitorGroups() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        Gson gson = new Gson();
        String json = gson.toJson(monitorGroups);
        editor.putString("monitor_groups_json", json);
        editor.apply();
    }

    // --- 服务控制 ---

    private void startServiceFunc() {
        if (monitorGroups.isEmpty()) {
            Toast.makeText(this, "请先添加至少一组监控路径。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!isManageExternalStorageGranted()) {
            Toast.makeText(this, "请先授予'所有文件访问权限'。", Toast.LENGTH_SHORT).show();
            requestExternalStoragePermissionGuide();
            return;
        }

        updateServiceStatusUI(true, "正在启动...");

        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(MonitorService.ACTION_START);
        // 传递整个列表给服务
        intent.putExtra("MONITOR_GROUPS", (Serializable) monitorGroups);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void stopServiceFunc() {
        updateServiceStatusUI(false, "正在停止...");
        Intent intent = new Intent(this, MonitorService.class);
        intent.setAction(MonitorService.ACTION_STOP);
        startService(intent);
    }

    private void updateServiceStatusUI(boolean isRunning, String message) {
        if (tvServiceStatus != null) {
            tvServiceStatus.setText(message);
        }
        if (btnStart != null) {
            btnStart.setEnabled(!isRunning && !monitorGroups.isEmpty());
        }
        if (btnStop != null) {
            btnStop.setEnabled(isRunning);
        }
    }

    private void checkButtons() {
        boolean isServiceRunning = MonitorService.isRunning(); // 这个方法可能不准，最好依赖广播
        btnStart.setEnabled(!isServiceRunning && !monitorGroups.isEmpty());
        btnStop.setEnabled(isServiceRunning);
    }

    // --- 权限/彩蛋/欢迎弹窗/广播 等辅助方法 (大部分保持不变) ---
    // ... (checkPermissionsAndStability, request..., checkDateEasterEgg, showWelcomeDialog, ServiceStatusReceiver 等方法)
    private void checkDateEasterEgg() {
        Calendar c = Calendar.getInstance();
        boolean isJune4th = (c.get(Calendar.MONTH) == Calendar.JUNE) && (c.get(Calendar.DAY_OF_MONTH) == 4);
        if (tvEasterEgg != null) {
            tvEasterEgg.setVisibility(isJune4th ? View.VISIBLE : View.GONE);
        }
    }
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2001);
            }
        }
    }
    private void showWelcomeDialog() {
        // ... (保持您原来的10秒倒计时逻辑)
    }
    private class ServiceStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MonitorService.ACTION_SERVICE_STATUS.equals(intent.getAction())) {
                boolean isRunning = intent.getBooleanExtra(MonitorService.EXTRA_IS_RUNNING, false);
                String msg = intent.getStringExtra(MonitorService.EXTRA_MESSAGE);
                updateServiceStatusUI(isRunning, msg);
            }
        }
    }
    // ... (其他所有 request, checkPermission, onActivityResult 等方法都保持原样)
    private boolean isManageExternalStorageGranted() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
    }
    private boolean isOverlayPermissionGranted() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
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
            if (cvPermissions != null) cvPermissions.setVisibility(View.GONE);
            return;
        }

        if (cvPermissions != null) cvPermissions.setVisibility(View.VISIBLE);

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
    private void requestExternalStoragePermissionGuide() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!isManageExternalStorageGranted()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                try {
                    startActivityForResult(intent, 4001);
                } catch (Exception e) {
                    Toast.makeText(this, "无法打开权限设置", Toast.LENGTH_LONG).show();
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 4001);
            }
        }
    }
    private void requestOverlayPermissionGuide() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !isOverlayPermissionGranted()) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            try {
                startActivityForResult(intent, 3001);
            } catch (Exception e) {
                Toast.makeText(this, "无法打开悬浮窗权限设置", Toast.LENGTH_LONG).show();
            }
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
                Toast.makeText(this, "无法打开设置页面", Toast.LENGTH_LONG).show();
            }
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 4001 || requestCode == 2001) {
            checkPermissionsAndStability();
        }
    }
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 4001 || requestCode == 3001) {
            checkPermissionsAndStability();
        }
    }

    // --- 菜单和旧方法占位 ---
    @Override
    public boolean onCreateOptionsMenu(Menu menu) { getMenuInflater().inflate(R.menu.main_menu, menu); return true; }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) { if (item.getItemId() == R.id.action_settings) { startActivity(new Intent(this, SettingsActivity.class)); return true; } return super.onOptionsItemSelected(item); }

}
