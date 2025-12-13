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

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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
    private static final long RESTART_DELAY_MS = 5000;

    public static final String ACTION_SERVICE_STATUS = "com.example.filecopier.SERVICE_STATUS";
    public static final String EXTRA_RUNNING = "running_status";
    public static final String EXTRA_MESSAGE = "status_message";
    public static final String EXTRA_TOTAL_COUNT = "total_count";
    public static final String EXTRA_COPIED_COUNT = "copied_count";
    public static final String EXTRA_PROGRESS = "progress_percent";

    // 使用 AtomicBoolean 确保线程安全
    private static AtomicBoolean serviceIsRunning = new AtomicBoolean(false);

    private Map<String, Long> knownFiles = new HashMap<>();
    private NotificationManager nm;
    private SharedPreferences sharedPrefs;

    private WindowManager windowManager;
    private View floatingView;
    private Handler mainHandler;

    private File srcDir;
    private File dstDir;
    private WatchService watcher;

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

    /** * 发送服务运行状态给 MainActivity
     * @param isRunning 当前服务状态
     * @param message 状态或错误消息
     */
    private void sendServiceStatusBroadcast(boolean isRunning, String message) {
        Intent intent = new Intent(ACTION_SERVICE_STATUS);
        intent.putExtra(EXTRA_RUNNING, isRunning);
        intent.putExtra(EXTRA_MESSAGE, message);

        // 以下是为了完整性，即使没有实际进度，也提供默认值
        intent.putExtra(EXTRA_TOTAL_COUNT, 0);
        intent.putExtra(EXTRA_COPIED_COUNT, 0);
        intent.putExtra(EXTRA_PROGRESS, 100);

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
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

                if (!srcDir.isDirectory()) {
                    String error = "源目录无效或不存在：" + srcPath;
                    handleFatalError(error);
                    return;
                }

                String loadingMsg = "监控已启动，正在初始化 WatchService...";
                updateFloatingWindow(loadingMsg);
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

                    // --- 核心循环 ---
                    while (serviceIsRunning.get()) {
                        // poll 的超时时间是为了让线程能定期检查 serviceIsRunning 标志
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

                                Thread.sleep(50);

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
                    } // end while(serviceIsRunning)
                    // 【关键修复点 1】：捕获中断异常 (正常停止时产生)
                } catch (InterruptedException e) {
                    // 当 stopService() 调用时，如果 poll 正在阻塞，会产生此异常
                    Log.i(TAG, "WatchService loop interrupted, stopping gracefully.");
                    // 允许线程自然退出，不报告错误

                } catch (java.nio.file.ClosedWatchServiceException e) {
                    // 当 watcher.close() 被调用时，poll 会抛出此异常
                    Log.i(TAG, "WatchService closed, stopping gracefully.");
                } catch (java.nio.file.AccessDeniedException e) {
                    handleFatalError("访问被拒绝。请确保已授予 '所有文件访问权限'。", e);
                } catch (Exception e) {
                    handleFatalError("WatchService 失败或线程中断，错误类型：" + e.getClass().getSimpleName(), e);
                } finally {
                    // 确保服务状态被设置为停止，以防 WatchService 线程意外退出
                    if (serviceIsRunning.get()) {
                        serviceIsRunning.set(false);
                        Log.e(TAG, "WatchService thread finished unexpectedly.");
                    }
                }
            }).start();
        }
        return START_STICKY;
    }

    // ... (sendServiceStatusBroadcast, showFloatingWindow, hideFloatingWindow, updateFloatingWindow 保持不变) ...

    /**
     * 集中处理所有致命错误和崩溃，并确保清理资源
     */
    private void handleFatalError(String errorMsg) {
        handleFatalError(errorMsg, null);
    }

    private void handleFatalError(String errorMsg, Throwable e) {
        if (e != null) Log.e(TAG, errorMsg, e);

        // 1. 强制停止服务标志
        serviceIsRunning.set(false);

        // 2. 更新通知和悬浮窗
        updateNotification("致命错误！" + errorMsg);
        updateFloatingWindow("致命错误，监控暂停！");

        // 3. 延迟后自动隐藏悬浮窗
        mainHandler.postDelayed(this::hideFloatingWindow, 3000);

        // 4. 发送停止广播
        sendServiceStatusBroadcast(false, "致命错误！" + errorMsg);
    }

    // ... (checkAndCopySingleFile, initKnownFiles, checkFileNameFilter, syncDeletions, doCopy 等方法保持不变) ...

    private void showFloatingWindow(String message, int bgColor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot show floating window: Overlay Permission denied.");
            return;
        }

        if (floatingView != null) {
            if (floatingView instanceof TextView) {
                TextView textView = (TextView) floatingView;
                textView.setText(message);
                textView.setBackgroundColor(bgColor);
                textView.setSingleLine(true);
            }
            return;
        }

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        TextView textView = new TextView(this);
        textView.setText(message);
        textView.setBackgroundColor(bgColor);
        textView.setTextColor(0xFFFFFFFF);
        textView.setPadding(10, 5, 10, 5);
        textView.setTextSize(12);
        textView.setGravity(Gravity.CENTER);
        textView.setSingleLine(true);

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
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 50;
        params.y = 100;

        try {
            windowManager.addView(floatingView, params);
        } catch (Exception e) {
            Log.e(TAG, "Error adding floating window: ", e);
        }
    }

    private void hideFloatingWindow() {
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                Log.w(TAG, "Error removing floating window (might be already removed): " + e.getMessage());
            }
        }
        floatingView = null;
        windowManager = null;
    }

    private void updateFloatingWindow(final String message) {
        final int color = message.contains("致命错误") ? 0x80FF0000 :
                message.contains("复制中") ? 0x8000FF00 :
                        message.contains("同步完成") ? 0x80008000 : 0x80000000;

        mainHandler.post(() -> showFloatingWindow(message, color));
    }

    private void updateNotification(String msg) {
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(msg));
        }
    }

    private Notification buildNotification(String msg) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("文件夹自动同步器")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true);

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "监控状态", NotificationManager.IMPORTANCE_LOW);
            if (nm != null) {
                nm.createNotificationChannel(c);
            }
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartService = new Intent(getApplicationContext(), this.getClass());
        restartService.setPackage(getPackageName());

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_ONE_SHOT;

        PendingIntent restartIntent = PendingIntent.getService(
                getApplicationContext(), 1, restartService, flags
        );

        AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RESTART_DELAY_MS, restartIntent);

        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        // 【关键修复点 2】：确保 WatchService 线程退出并清理资源
        serviceIsRunning.set(false); // 停止循环

        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopForeground(true);

        // 1. 关闭 WatchService。WatchService 的 close() 会中断正在 poll() 的线程。
        if (watcher != null) {
            try {
                watcher.close();
                // 此时，WatchService 线程会抛出 InterruptedException，并安全退出
            } catch (IOException e) {
                Log.e(TAG, "Error closing WatchService in onDestroy", e);
            }
        }

        // 2. 安全移除悬浮窗
        mainHandler.post(() -> {
            try {
                hideFloatingWindow();
            } catch (Exception e) {
                Log.e(TAG, "Error removing floating window in onDestroy: " + e.getMessage());
            }
        });

        sendServiceStatusBroadcast(false, "服务已停止");

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }


    // --- 辅助方法 (initKnownFiles, checkAndCopySingleFile, syncDeletions, doCopy, etc. 保持不变) ---
    private boolean checkAndCopySingleFile(File srcFile) {
        String name = srcFile.getName();
        long lastMod = srcFile.lastModified();

        if (!srcFile.isFile()) return false;

        int overwriteModeIndex = sharedPrefs.getInt(SettingsActivity.KEY_OVERWRITE_MODE_INDEX, 0);
        boolean shouldOverwrite = (overwriteModeIndex == 1);
        boolean isFilterEnabled = sharedPrefs.getBoolean(SettingsActivity.KEY_CONTENT_FILTER_ENABLED, false);
        String keywordsString = sharedPrefs.getString(SettingsActivity.KEY_FILTER_KEYWORDS, "");
        String[] keywords = keywordsString.split(",");

        if (isFilterEnabled && !checkFileNameFilter(name, keywords)) return false;

        if (!knownFiles.containsKey(name) || knownFiles.get(name) < lastMod) {

            updateFloatingWindow("文件正在复制中...");

            if (doCopy(srcFile, dstDir, shouldOverwrite)) {
                knownFiles.put(name, lastMod);
                return true;
            } else {
                return false;
            }
        }
        return false;
    }

    private void initKnownFiles() {
        if (srcDir != null && srcDir.isDirectory()) {
            knownFiles.clear();
            File[] files = srcDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) knownFiles.put(f.getName(), f.lastModified());
                }
            }
        }
    }

    private boolean checkFileNameFilter(String fileName, String[] keywords) {
        boolean hasValidKeyword = false;
        for (String keyword : keywords) {
            if (keyword == null) continue;
            String trimmedKeyword = keyword.trim();
            if (!trimmedKeyword.isEmpty()) {
                hasValidKeyword = true;
                if (fileName.toLowerCase().contains(trimmedKeyword.toLowerCase())) return true;
            }
        }
        return !hasValidKeyword && !keywords.toString().trim().isEmpty() ? true : false;
    }

    private boolean shouldDeleteMirror() {
        return sharedPrefs.getBoolean(SettingsActivity.KEY_DELETE_MIRROR, false);
    }

    private boolean syncDeletions() {
        if (!shouldDeleteMirror()) return false;

        File[] dstFiles = dstDir.listFiles();
        boolean deleted = false;
        if (dstFiles != null) {
            for (File dstFile : dstFiles) {
                if (!dstFile.isFile() || dstFile.getName() == null) continue;

                File srcFile = new File(srcDir, dstFile.getName());
                if (!srcFile.exists()) {
                    if (dstFile.delete()) {
                        knownFiles.remove(dstFile.getName());
                        deleted = true;
                    }
                }
            }
        }
        return deleted;
    }

    private boolean doCopy(File srcFile, File dstDir, boolean shouldOverwrite) {
        File targetFile = null;
        try {
            String name = srcFile.getName();

            if (shouldOverwrite) {
                targetFile = new File(dstDir, name);
                if (targetFile.exists()) targetFile.delete();
            } else {
                targetFile = new File(dstDir, name);
                if (targetFile.exists()) {
                    String pureName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                    int i = 1;
                    String newName;
                    do {
                        newName = pureName + "." + i + ext;
                        i++;
                        targetFile = new File(dstDir, newName);
                    } while (targetFile.exists());
                }
            }

            if (targetFile.getParentFile() != null && !targetFile.getParentFile().exists()) {
                targetFile.getParentFile().mkdirs();
            }

            try (FileInputStream in = new FileInputStream(srcFile);
                 FileOutputStream out = new FileOutputStream(targetFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "File I/O Error during copy of " + srcFile.getName(), e);
            return false;
        }
    }
}