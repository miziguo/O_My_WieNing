package com.example.filecopier;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MonitorService extends Service {
    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "FileMonitorChannel";
    private static final int NOTIFICATION_ID = 1;

    // 【常量】供 MainActivity 调用的状态广播
    public static final String ACTION_SERVICE_STATUS = "com.example.filecopier.SERVICE_STATUS";
    public static final String EXTRA_MESSAGE = "status_message";

    private static AtomicBoolean serviceIsRunning = new AtomicBoolean(false);

    // 文件缓存和依赖
    private Map<String, Long> knownFiles = new HashMap<>();
    private NotificationManager nm;
    private SharedPreferences sharedPrefs;

    // 浮动窗口依赖
    private WindowManager windowManager;
    private View floatingView;
    private TextView tvStatus;
    private Handler mainHandler;

    // 监控依赖
    private File srcDir;
    private File dstDir;
    private WatchService watcher;

    // 【核心方法】检查服务是否在运行
    public static boolean isRunning() {
        return serviceIsRunning.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        sharedPrefs = getSharedPreferences(SettingsActivity.PREF_NAME, Context.MODE_PRIVATE);
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || "STOP".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        createNotificationChannel();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification("正在初始化监控..."),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, buildNotification("正在初始化监控..."));
        }

        String srcPath = intent.getStringExtra("SOURCE_PATH");
        String dstPath = intent.getStringExtra("TARGET_PATH");

        if (srcPath != null && dstPath != null && serviceIsRunning.compareAndSet(false, true)) {

            sendServiceStatusBroadcast(true, "监控已启动...");

            new Thread(() -> {
                srcDir = new File(srcPath);
                dstDir = new File(dstPath);

                if (!srcDir.isDirectory() || !dstDir.isDirectory()) {
                    String error = "源目录或目标目录无效/不存在。";
                    handleFatalError(error);
                    return;
                }

                String loadingMsg = "监控已启动，正在初始化 WatchService...";
                showFloatingWindow(loadingMsg, 0xFF4CAF50); // 绿色背景
                updateNotification(loadingMsg);

                try {
                    watcher = FileSystems.getDefault().newWatchService();
                    Path srcPathObj = Paths.get(srcPath);

                    srcPathObj.register(watcher,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                            StandardWatchEventKinds.ENTRY_DELETE);

                    initKnownFiles();

                    String currentMsg = "监控中：等待文件变动 (事件驱动)";
                    updateFloatingWindow(currentMsg);
                    updateNotification(currentMsg);

                    while (serviceIsRunning.get()) {
                        WatchKey key = watcher.poll(100, TimeUnit.MILLISECONDS);

                        if (key == null) {
                            if (shouldDeleteMirror()) {
                                syncDeletions();
                            }
                            continue;
                        }

                        boolean activity = false;
                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();

                            Path fileNamePath = (Path) event.context();
                            File changedFile = new File(srcDir, fileNamePath.toFile().getName());

                            if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                            if (kind == StandardWatchEventKinds.ENTRY_CREATE ||
                                    kind == StandardWatchEventKinds.ENTRY_MODIFY) {

                                Thread.sleep(100); // 延迟，等待文件写入完成

                                if (checkAndCopySingleFile(changedFile)) {
                                    activity = true;
                                }
                            }

                            if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                                knownFiles.remove(changedFile.getName());
                                activity = true;
                            }
                        }

                        boolean valid = key.reset();
                        if (!valid) {
                            handleFatalError("Watch Key 失效。目录不再可访问或已删除。");
                            break;
                        }

                        if (activity) {
                            updateFloatingWindow("检测到活动，处理完成...");
                            updateNotification("同步完成，等待文件变动");
                            Thread.sleep(1000);
                            updateFloatingWindow("监控中：等待文件变动 (事件驱动)");
                        }
                    }
                } catch (InterruptedException e) {
                    Log.i(TAG, "WatchService loop interrupted, stopping gracefully.");
                } catch (java.nio.file.AccessDeniedException e) {
                    handleFatalError("访问被拒绝。请确保已授予 '所有文件访问权限'。", e);
                } catch (Exception e) {
                    handleFatalError("WatchService 线程失败，错误类型：" + e.getClass().getSimpleName(), e);
                } finally {
                    if (serviceIsRunning.get()) {
                        serviceIsRunning.set(false);
                        Log.e(TAG, "WatchService thread finished unexpectedly.");
                    }
                    stopSelf();
                }
            }).start();
        } else if (serviceIsRunning.get()) {
            Toast.makeText(this, "服务已在运行中。", Toast.LENGTH_SHORT).show();
        }
        return START_STICKY;
    }

    // --- 辅助方法实现 ---

    private void sendServiceStatusBroadcast(boolean isRunning, String message) {
        Intent intent = new Intent(ACTION_SERVICE_STATUS);
        intent.putExtra(MonitorService.EXTRA_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void handleFatalError(String errorMsg) {
        handleFatalError(errorMsg, null);
    }

    private void handleFatalError(String errorMsg, Throwable e) {
        if (e != null) Log.e(TAG, errorMsg, e);

        serviceIsRunning.set(false);

        updateNotification("致命错误！" + errorMsg);
        updateFloatingWindow("致命错误，监控暂停！");

        mainHandler.postDelayed(this::hideFloatingWindow, 3000);

        sendServiceStatusBroadcast(false, "致命错误！" + errorMsg);
        stopSelf();
    }

    private void showFloatingWindow(String message, int bgColor) {
        if (!Settings.canDrawOverlays(this)) return;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (floatingView != null) {
            updateFloatingWindow(message);
            return;
        }

        // 假设您在 res/layout/floating_status.xml 中定义了一个简单的TextView
        floatingView = View.inflate(this, R.layout.floating_status, null);
        tvStatus = floatingView.findViewById(R.id.tvFloatingStatus);
        tvStatus.setText(message);

        // 允许拖动但这里省略了拖动逻辑

        final WindowManager.LayoutParams params;
        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 100;
        params.y = 100;

        try {
            windowManager.addView(floatingView, params);
        } catch (Exception e) {
            Log.e(TAG, "Failed to add floating window: " + e.getMessage());
            floatingView = null;
        }
    }

    private void hideFloatingWindow() {
        if (floatingView != null) {
            try {
                windowManager.removeView(floatingView);
                floatingView = null;
                tvStatus = null;
            } catch (Exception e) {
                Log.e(TAG, "Error removing floating window: " + e.getMessage());
            }
        }
    }

    private void updateFloatingWindow(final String message) {
        mainHandler.post(() -> {
            if (tvStatus != null) {
                tvStatus.setText(message);
            } else if (Settings.canDrawOverlays(this)) {
                // 如果浮窗被意外移除但权限仍在，重新创建
                showFloatingWindow(message, 0xFF4CAF50);
            }
        });
    }

    private void updateNotification(String msg) {
        Notification notification = buildNotification(msg);
        nm.notify(NOTIFICATION_ID, notification);
    }

    private Notification buildNotification(String msg) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("文件同步服务")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_menu_rotate)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "文件监控通知",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(serviceChannel);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 服务被移除时自动重启
        Intent restartService = new Intent(getApplicationContext(), this.getClass());
        restartService.setPackage(getPackageName());
        PendingIntent restartIntent = PendingIntent.getService(this, 1, restartService, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.set(AlarmManager.ELAPSED_REALTIME, System.currentTimeMillis() + 1000, restartIntent);
        super.onTaskRemoved(rootIntent);
    }

    private boolean checkAndCopySingleFile(File srcFile) {
        if (!srcFile.isFile()) return false;

        String fileName = srcFile.getName();

        // 1. 过滤检查
        String keywordsStr = sharedPrefs.getString(SettingsActivity.KEY_FILTER_KEYWORDS, "");
        boolean isFilterEnabled = sharedPrefs.getBoolean(SettingsActivity.KEY_CONTENT_FILTER_ENABLED, false);

        if (isFilterEnabled && !checkFileNameFilter(fileName, keywordsStr.split(","))) {
            Log.d(TAG, "File skipped by filter: " + fileName);
            return false;
        }

        // 2. 检查已知文件状态（防止重复复制）
        long currentLastModified = srcFile.lastModified();
        if (knownFiles.containsKey(fileName) && knownFiles.get(fileName) == currentLastModified) {
            return false;
        }

        // 3. 复制逻辑
        File dstFile = new File(dstDir, fileName);
        int overwriteMode = sharedPrefs.getInt(SettingsActivity.KEY_OVERWRITE_MODE_INDEX, 0); // 0-跳过, 1-覆盖
        boolean shouldOverwrite = (overwriteMode == 1);

        if (!shouldOverwrite && dstFile.exists()) {
            // 跳过模式，且目标文件存在
            Log.d(TAG, "File skipped: " + fileName + " (Exists, Overwrite disabled)");
            knownFiles.put(fileName, currentLastModified);
            return false;
        }

        if (doCopy(srcFile, dstFile, shouldOverwrite)) {
            knownFiles.put(fileName, currentLastModified);
            updateFloatingWindow("复制成功: " + fileName);
            Log.i(TAG, "File copied: " + fileName);
            return true;
        } else {
            Log.e(TAG, "File copy FAILED: " + fileName);
            updateFloatingWindow("复制失败: " + fileName);
            return false;
        }
    }

    private void initKnownFiles() {
        knownFiles.clear();
        File[] files = srcDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    knownFiles.put(file.getName(), file.lastModified());
                }
            }
        }
        Log.i(TAG, "Initialized " + knownFiles.size() + " files in knownFiles map.");
    }

    private boolean checkFileNameFilter(String fileName, String[] keywords) {
        if (keywords == null || keywords.length == 0) return true;

        fileName = fileName.toLowerCase();
        for (String keyword : keywords) {
            keyword = keyword.trim().toLowerCase();
            if (!keyword.isEmpty() && fileName.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldDeleteMirror() {
        return sharedPrefs.getBoolean(SettingsActivity.KEY_DELETE_MIRROR, false);
    }

    private boolean syncDeletions() {
        if (dstDir == null || srcDir == null) return false;

        File[] dstFiles = dstDir.listFiles();
        if (dstFiles == null) return false;

        boolean deleted = false;
        for (File dstFile : dstFiles) {
            if (dstFile.isFile()) {
                File srcFile = new File(srcDir, dstFile.getName());
                if (!srcFile.exists()) {
                    if (dstFile.delete()) {
                        Log.i(TAG, "Synced deletion: " + dstFile.getName());
                        deleted = true;
                    } else {
                        Log.w(TAG, "Failed to delete mirror file: " + dstFile.getName());
                    }
                }
            }
        }
        return deleted;
    }

    // 文件复制操作
    private boolean doCopy(File srcFile, File dstFile, boolean shouldOverwrite) {
        try (FileChannel inputChannel = new FileInputStream(srcFile).getChannel();
             FileChannel outputChannel = new FileOutputStream(dstFile).getChannel()) {

            outputChannel.transferFrom(inputChannel, 0, inputChannel.size());
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Copy failed for " + srcFile.getName() + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public void onDestroy() {
        serviceIsRunning.set(false);

        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopForeground(true);

        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing WatchService in onDestroy", e);
            }
        }

        mainHandler.post(this::hideFloatingWindow);
        sendServiceStatusBroadcast(false, "服务已停止");

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    // 在 MonitorService 类内部
    private void updateSettingsFromPrefs() {
        if (sharedPrefs == null) return;
        // 读取 SettingsActivity 里定义的 Key
        int overwriteMode = sharedPrefs.getInt("overwrite_mode_index", 0);
        boolean deleteMirror = sharedPrefs.getBoolean("delete_mirror", false);
        boolean filterEnabled = sharedPrefs.getBoolean("content_filter_enabled", false);
        String keywords = sharedPrefs.getString("filter_keywords", "");

        // TODO: 将这些变量应用到你的复制逻辑中
        // 例如：this.shouldOverwrite = (overwriteMode == 0);
    }

}