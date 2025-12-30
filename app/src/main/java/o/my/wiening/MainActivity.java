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
import android.widget.ImageButton;
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
    private TextView tvEasterEgg, tvServiceStatus, versionView, tvPermissionTitle;
    private TextView tvAboutTitle1, tvAboutTitle2, tvAboutCopyright;
    private Button btnStart, btnStop, btnBatteryOptimization, btnStoragePermission;
    private CardView cardViewWarning, cardViewStatus, cardViewControls, cvPermissions, cardViewVersion, cardViewAbout;
    private View mainRootLayout;
    private RecyclerView recyclerView;
    private ImageButton fabAddGroup;

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

        initSharedPreferences();
        initViews();
        applyThemeColors();
        initRecyclerView();
        initListeners();

        requestNotificationPermission();
        loadMonitorGroups();
        checkDateEasterEgg();
        setupBroadcastReceiver();

        showWelcomeDialog();
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyThemeColors();
        LocalBroadcastManager.getInstance(this).registerReceiver(statusReceiver, new IntentFilter(MonitorService.ACTION_SERVICE_STATUS));
        checkPermissionsAndStability();
        updateServiceStatusUI(MonitorService.isRunning(), "准备就绪");
    }

    @Override
    protected void onPause() {
        super.onPause();
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver);
    }

    private void initSharedPreferences() {
        monitorSettings = getSharedPreferences(SettingsActivity.PREF_NAME, Context.MODE_PRIVATE);
        colorValues = getResources().getStringArray(R.array.theme_color_values);
        textColorValues = getResources().getStringArray(R.array.text_color_values);
    }

    private void initViews() {
        mainRootLayout = findViewById(R.id.main_root_layout);
        cardViewWarning = findViewById(R.id.card_view_warning);
        cardViewStatus = findViewById(R.id.card_view_status);
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
        fabAddGroup = findViewById(R.id.fab_add_group);
        recyclerView = findViewById(R.id.rv_monitor_groups);

        tvPermissionTitle = findViewById(R.id.tv_permission_title);
        versionView = findViewById(R.id.version);
        tvAboutTitle1 = findViewById(R.id.tv_about_title1);
        tvAboutTitle2 = findViewById(R.id.tv_about_title2);
        tvAboutCopyright = findViewById(R.id.tv_about_copyright);

        if (versionView != null) {
            try {
                String fullVersionName = BuildConfig.VERSION_NAME;
                String formattedVersionName = fullVersionName.replace("|", "\n");
                versionView.setText("当前版本: " + formattedVersionName);
            } catch (Exception e) {
                versionView.setText("当前版本: 未知");
            }
        }
    }

    private void initRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MonitorGroupAdapter(monitorGroups, position -> {
            new AlertDialog.Builder(this)
                    .setTitle("确认删除")
                    .setMessage("您确定要删除这个监控组吗？")
                    .setPositiveButton("删除", (dialog, which) -> {
                        monitorGroups.remove(position);
                        adapter.notifyItemRemoved(position);
                        adapter.notifyItemRangeChanged(position, monitorGroups.size());
                        saveMonitorGroups();
                        checkButtons();
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

    private void applyThemeColors() {
        int actionBarIndex = monitorSettings.getInt(SettingsActivity.KEY_ACTIONBAR_COLOR_INDEX, 7);
        int buttonIndex = monitorSettings.getInt(SettingsActivity.KEY_BUTTON_COLOR_INDEX, 7);
        int cardAlpha = monitorSettings.getInt(SettingsActivity.KEY_CARD_ALPHA, 255);

        int buttonTextColor = getTextColorFromIndex(
                monitorSettings.getInt(SettingsActivity.KEY_BUTTON_TEXT_COLOR_INDEX, 2), // Default white
                SettingsActivity.KEY_CUSTOM_BUTTON_TEXT_COLOR,
                "#FFFFFF"
        );
        int generalTextColor = getTextColorFromIndex(
                monitorSettings.getInt(SettingsActivity.KEY_GENERAL_TEXT_COLOR_INDEX, 0), // Default black
                SettingsActivity.KEY_CUSTOM_GENERAL_TEXT_COLOR,
                "#000000"
        );

        int actionBarColor = getThemeColorFromIndex(actionBarIndex, SettingsActivity.KEY_CUSTOM_ACTIONBAR_COLOR);
        int buttonColor = getThemeColorFromIndex(buttonIndex, SettingsActivity.KEY_CUSTOM_BUTTON_COLOR);

        // ActionBar & Status Bar
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setBackgroundDrawable(new ColorDrawable(actionBarColor));
            SpannableString text = new SpannableString(actionBar.getTitle() != null ? actionBar.getTitle() : getString(R.string.app_name));
            text.setSpan(new ForegroundColorSpan(buttonTextColor), 0, text.length(), Spannable.SPAN_INCLUSIVE_INCLUSIVE);
            actionBar.setTitle(text);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(actionBarColor);
        }

        // Buttons and FAB
        ColorStateList colorStateList = ColorStateList.valueOf(buttonColor);
        applyButtonColor(btnStart, colorStateList, buttonTextColor);
        applyButtonColor(btnStop, colorStateList, buttonTextColor);
        if (fabAddGroup != null) {
            Drawable background = fabAddGroup.getBackground().mutate();
            background.setTintList(colorStateList);
            fabAddGroup.setBackground(background);
        }

        // Set color for all non-button TextViews
        if (tvServiceStatus != null) tvServiceStatus.setTextColor(generalTextColor);
        if (versionView != null) versionView.setTextColor(generalTextColor);
        if (tvPermissionTitle != null) tvPermissionTitle.setTextColor(generalTextColor);
        if (tvAboutTitle1 != null) tvAboutTitle1.setTextColor(generalTextColor);
        if (tvAboutTitle2 != null) tvAboutTitle2.setTextColor(generalTextColor);
        if (tvAboutCopyright != null) tvAboutCopyright.setTextColor(generalTextColor);

        // Background & Card Alpha
        applyBackgroundImage();
        applyCardAlpha(cardAlpha);
    }

    private int getThemeColorFromIndex(int index, String customKey) {
        if (index == colorValues.length) {
            String customColor = monitorSettings.getString(customKey, "#FF000000");
            try { return Color.parseColor(customColor); } catch (Exception e) { return Color.BLACK; }
        } else if (index >= 0 && index < colorValues.length) {
            try { return Color.parseColor(colorValues[index]); } catch (Exception e) { return Color.BLACK; }
        }
        return Color.BLACK;
    }

    private int getTextColorFromIndex(int index, String customColorKey, String defaultColor) {
        if (index == textColorValues.length) {
            String customColor = monitorSettings.getString(customColorKey, defaultColor);
            try { return Color.parseColor(customColor); } catch (Exception e) { return Color.parseColor(defaultColor); }
        } else if (index >= 0 && index < textColorValues.length) {
            try { return Color.parseColor(textColorValues[index]); } catch (Exception e) { return Color.parseColor(defaultColor); }
        }
        return Color.parseColor(defaultColor);
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
        float elevation = (alpha == 255) ? getResources().getDisplayMetrics().density * 4 : 0f;

        setCardStyle(cardViewWarning, color, elevation);
        setCardStyle(cardViewStatus, color, elevation);
        setCardStyle(cardViewControls, color, elevation);
        setCardStyle(cvPermissions, color, elevation);
        setCardStyle(cardViewVersion, color, elevation);
        setCardStyle(cardViewAbout, color, elevation);

        if (adapter != null) {
            adapter.setCardAlpha(alpha);
        }
    }

    private void setCardStyle(CardView cardView, int color, float elevation) {
        if (cardView != null) {
            cardView.setCardBackgroundColor(color);
            cardView.setCardElevation(elevation);
        }
    }

    private void applyButtonColor(Button btn, ColorStateList backgroundTint, int textColor) {
        if (btn != null) {
            btn.setBackgroundTintList(backgroundTint);
            btn.setTextColor(textColor);
        }
    }

    private void showAddGroupDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View dialogView = inflater.inflate(R.layout.dialog_add_group, null);

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
            Type type = new TypeToken<ArrayList<MonitorGroup>>() {}.getType();
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
        boolean isServiceRunning = MonitorService.isRunning();
        btnStart.setEnabled(!isServiceRunning && !monitorGroups.isEmpty());
        btnStop.setEnabled(isServiceRunning);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void checkDateEasterEgg() {
        Calendar c = Calendar.getInstance();
        boolean isJune4th = (c.get(Calendar.MONTH) == Calendar.JUNE) && (c.get(Calendar.DAY_OF_MONTH) == 4);
        if (tvEasterEgg != null) {
            tvEasterEgg.setVisibility(isJune4th ? View.VISIBLE : View.GONE);
            if (isJune4th) tvEasterEgg.setText("铭记历史，勿忘六四");
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
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isFirstRun = prefs.getBoolean("isFirstRun", true);

        if (!isFirstRun) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("使用条款")
                .setMessage("男娘是我们最好的朋友，请不要对南娘使用本软件。如果对南娘使用本软件被弄死了，软件作者概不负责\n\n©2025 MUW Group Studio")
                .setCancelable(false)
                .setNegativeButton("我同意 (10s)", null)
                .setPositiveButton("滚！", (dialog, which) -> finish());

        AlertDialog dialog = builder.create();
        dialog.show();

        Button btnAgree = dialog.getButton(AlertDialog.BUTTON_NEGATIVE);
        Button btnDisagree = dialog.getButton(AlertDialog.BUTTON_POSITIVE);

        if (btnDisagree != null) btnDisagree.setTextColor(Color.RED);
        if (btnAgree != null) {
            btnAgree.setTextColor(Color.GRAY);
            btnAgree.setEnabled(false);
        }

        new android.os.CountDownTimer(10000, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                if (btnAgree != null) {
                    btnAgree.setText("我同意 (" + (millisUntilFinished / 1000 + 1) + "s)");
                }
            }

            @Override
            public void onFinish() {
                if (btnAgree != null) {
                    btnAgree.setText("我同意");
                    btnAgree.setEnabled(true);
                    btnAgree.setTextColor(Color.parseColor("#009900"));
                    btnAgree.setOnClickListener(v -> {
                        prefs.edit().putBoolean("isFirstRun", false).apply();
                        dialog.dismiss();
                    });
                }
            }
        }.start();
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
                btnStoragePermission.setText("启用全部文件访问权限");
                btnStoragePermission.setOnClickListener(v -> requestExternalStoragePermissionGuide());
            } else if (!overlayGranted) {
                btnStoragePermission.setVisibility(View.VISIBLE);
                btnStoragePermission.setText("启用悬浮窗权限");
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
}
