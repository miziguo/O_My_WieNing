package o.my.wiening;

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

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MonitorService extends Service {
    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "FileMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final long RESTART_DELAY_MS = 1000;

    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";

    public static final String ACTION_SERVICE_STATUS = "o.my.wiening.SERVICE_STATUS";
    public static final String EXTRA_IS_RUNNING = "is_running";
    public static final String EXTRA_MESSAGE = "status_message";

    private NotificationManager nm;
    private WindowManager windowManager;
    private View floatingView;
    private SharedPreferences sharedPrefs;
    private Handler mainHandler;

    private ExecutorService executorService;
    private final List<WatchService> activeWatchers = Collections.synchronizedList(new ArrayList<>());

    private static final AtomicBoolean serviceIsRunning = new AtomicBoolean(false);

    public static boolean isRunning() {
        return serviceIsRunning.get();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        mainHandler = new Handler(Looper.getMainLooper());
        sharedPrefs = getSharedPreferences(SettingsActivity.PREF_NAME, Context.MODE_PRIVATE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();

        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "收到停止指令");
            stopMonitoring();
            return START_NOT_STICKY;
        }

        if (action == null || ACTION_START.equals(action)) {
            // 立即发送广播，让UI响应
            sendServiceStatusBroadcast(true, "准备中...");
            startForeground(NOTIFICATION_ID, buildNotification("正在准备..."));

            Serializable extra = intent.getSerializableExtra("MONITOR_GROUPS");
            if (extra instanceof ArrayList) {
                List<MonitorGroup> groups = (ArrayList<MonitorGroup>) extra;

                if (!groups.isEmpty()) {
                    if (serviceIsRunning.compareAndSet(false, true)) {
                        Log.i(TAG, "开始监控 " + groups.size() + " 个组");
                        startAllMonitoring(groups);
                    } else {
                        Toast.makeText(this, "服务已在运行中", Toast.LENGTH_SHORT).show();
                        // 补发一个状态广播，确保UI同步
                        sendServiceStatusBroadcast(true, "");
                    }
                } else {
                    // 列表为空也是一个启动错误
                    handleFatalError("启动失败：监控列表为空。");
                }
            } else {
                handleFatalError("启动失败：未提供有效的监控组列表。");
            }
        }
        return START_STICKY;
    }

    private void startAllMonitoring(List<MonitorGroup> groups) {
        executorService = Executors.newCachedThreadPool();
        activeWatchers.clear();

        sendServiceStatusBroadcast(true, "正在启动 " + groups.size() + " 个监控...");

        for (MonitorGroup group : groups) {
            executorService.submit(() -> startSingleMonitoringThread(group));
        }

        updateNotification("正在监控 " + groups.size() + " 个目录");
        updateFloatingWindow("");
    }

    private void stopMonitoring() {
        if (serviceIsRunning.compareAndSet(true, false)) {
            Log.i(TAG, "正在停止所有监控...");

            synchronized (activeWatchers) {
                for (WatchService watcher : activeWatchers) {
                    try {
                        watcher.close();
                    } catch (IOException e) {
                        Log.e(TAG, "关闭 Watcher 时出错", e);
                    }
                }
                activeWatchers.clear();
            }

            if (executorService != null && !executorService.isShutdown()) {
                executorService.shutdownNow();
            }

            stopForeground(true);
            hideFloatingWindow();

            sendServiceStatusBroadcast(false, "监控已停止");
            stopSelf();
        }
    }

    private void startSingleMonitoringThread(MonitorGroup group) {
        String srcPath = group.getSourcePath();
        String dstPath = group.getTargetPath();
        File srcDir = new File(srcPath);
        File dstDir = new File(dstPath);

        if (!srcDir.isDirectory()) {
            Log.e(TAG, "目录无效，跳过: " + srcPath);
            return;
        }

        WatchService watcher = null;
        try {
            watcher = FileSystems.getDefault().newWatchService();
            Path srcPathObj = Paths.get(srcPath);
            srcPathObj.register(watcher,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);

            activeWatchers.add(watcher);
            Log.i(TAG, "成功启动对 " + srcPath + " 的监控");

            Map<String, Long> knownFiles = initKnownFiles(srcDir);

            while (serviceIsRunning.get()) {
                WatchKey key = watcher.poll(2, TimeUnit.SECONDS);

                if (key == null) {
                    continue;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();
                    if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                    Path fileNamePath = (Path) event.context();
                    File changedFile = new File(srcDir, fileNamePath.toFile().getName());

                    if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                        Thread.sleep(150);
                        checkAndCopySingleFile(changedFile, dstDir, knownFiles);
                    } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        knownFiles.remove(changedFile.getName());
                    }
                }

                if (!key.reset()) {
                    Log.w(TAG, "监控目录失效: " + srcPath);
                    break;
                }
            }
        } catch (InterruptedException | ClosedWatchServiceException e) {
            Log.i(TAG, "监控线程正常停止: " + srcPath);
        } catch (Exception e) {
            if (serviceIsRunning.get()) {
                Log.e(TAG, "监控 " + srcPath + " 时发生错误", e);
            }
        } finally {
            Log.i(TAG, "监控线程结束: " + srcPath);
            if (watcher != null) {
                try {
                    watcher.close();
                } catch (IOException e) { /* ignore */ }
                activeWatchers.remove(watcher);
            }
        }
    }

    private void sendServiceStatusBroadcast(boolean isRunning, String message) {
        Intent intent = new Intent(ACTION_SERVICE_STATUS);
        intent.putExtra(EXTRA_IS_RUNNING, isRunning);
        intent.putExtra(EXTRA_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void handleFatalError(String errorMsg) {
        Log.e(TAG, errorMsg);
        if (serviceIsRunning.get()) {
            stopMonitoring();
        } else {
            stopForeground(true);
            sendServiceStatusBroadcast(false, "错误: " + errorMsg);
            stopSelf();
        }
        updateNotification("致命错误: " + errorMsg);
        updateFloatingWindow("错误: " + errorMsg);
        mainHandler.postDelayed(this::hideFloatingWindow, 5000);
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    private void checkAndCopySingleFile(File srcFile, File dstDir, Map<String, Long> knownFiles) {
        if (!srcFile.exists() || !srcFile.isFile()) return;

        String name = srcFile.getName();
        long lastMod = srcFile.lastModified();

        boolean isFilterEnabled = sharedPrefs.getBoolean(SettingsActivity.KEY_CONTENT_FILTER_ENABLED, false);
        if (isFilterEnabled) {
            String keywordsString = sharedPrefs.getString(SettingsActivity.KEY_FILTER_KEYWORDS, "");
            if (!checkFileNameFilter(name, keywordsString.split(","))) {
                return;
            }
        }

        if (knownFiles.containsKey(name) && knownFiles.get(name) >= lastMod) {
            return;
        }

        updateFloatingWindow("复制中: " + name);
        if (doCopy(srcFile, dstDir)) {
            knownFiles.put(name, lastMod);
            mainHandler.postDelayed(() -> updateFloatingWindow(""), 1500);
        }
    }

    private Map<String, Long> initKnownFiles(File srcDir) {
        Map<String, Long> filesMap = new HashMap<>();
        if (srcDir != null && srcDir.isDirectory()) {
            File[] files = srcDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile()) {
                        filesMap.put(f.getName(), f.lastModified());
                    }
                }
            }
        }
        Log.i(TAG, "在 " + srcDir.getName() + " 中初始化了 " + filesMap.size() + " 个文件。");
        return filesMap;
    }

    private boolean checkFileNameFilter(String fileName, String[] keywords) {
        boolean hasValidKeyword = false;
        for (String keyword : keywords) {
            if (keyword == null) continue;
            String trimmedKeyword = keyword.trim();
            if (!trimmedKeyword.isEmpty()) {
                hasValidKeyword = true;
                if (fileName.toLowerCase().contains(trimmedKeyword.toLowerCase())) {
                    return true;
                }
            }
        }
        return !hasValidKeyword;
    }

    private boolean doCopy(File srcFile, File dstDir) {
        int overwriteModeIndex = sharedPrefs.getInt(SettingsActivity.KEY_OVERWRITE_MODE_INDEX, 0);
        File targetFile = new File(dstDir, srcFile.getName());

        if (targetFile.exists()) {
            if (overwriteModeIndex == 0) { // Skip
                return false;
            } else if (overwriteModeIndex == 1) { // Overwrite
                targetFile.delete();
            }
        }

        try (FileInputStream in = new FileInputStream(srcFile);
             FileOutputStream out = new FileOutputStream(targetFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            Log.i(TAG, "成功复制: " + srcFile.getName() + " -> " + dstDir.getName());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "复制文件失败: " + srcFile.getName(), e);
            updateFloatingWindow("复制失败: " + srcFile.getName());
            return false;
        }
    }

    private void showFloatingWindow(String message) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return;
        }
        mainHandler.post(() -> {
            if (floatingView == null) {
                windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                TextView textView = new TextView(this);
                textView.setBackgroundColor(0x99000000);
                textView.setTextColor(0xFFFFFFFF);
                textView.setPadding(16, 8, 16, 8);
                floatingView = textView;
                int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, layoutFlag, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.START;
                params.x = 50;
                params.y = 100;
                try {
                    windowManager.addView(floatingView, params);
                } catch (Exception e) {
                    floatingView = null;
                }
            }
            if (floatingView instanceof TextView) {
                ((TextView) floatingView).setText(message);
            }
        });
    }

    private void hideFloatingWindow() {
        mainHandler.post(() -> {
            if (floatingView != null && windowManager != null) {
                try {
                    windowManager.removeView(floatingView);
                } catch (Exception e) { /* ignore */ }
                floatingView = null;
            }
        });
    }

    private void updateFloatingWindow(final String message) {
        mainHandler.post(() -> showFloatingWindow(message));
    }

    private void updateNotification(String msg) {
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(msg));
        }
    }

    private Notification buildNotification(String msg) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("哦！我可爱的小南娘")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "文件监控服务", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(c);
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Intent restartService = new Intent(getApplicationContext(), this.getClass());
        restartService.setPackage(getPackageName());
        PendingIntent restartIntent = PendingIntent.getService(
                getApplicationContext(), 1, restartService,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_ONE_SHOT
        );
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RESTART_DELAY_MS, restartIntent);
        super.onTaskRemoved(rootIntent);
    }
}
