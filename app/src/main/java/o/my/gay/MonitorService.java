package o.my.gay;

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
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class MonitorService extends Service {
    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "FileMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final long RESTART_DELAY_MS = 1000;

    // 定义 Action 常量，用于区分启动和停止
    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";

    public static final String ACTION_SERVICE_STATUS = "com.example.filecopier.SERVICE_STATUS";
    public static final String EXTRA_IS_RUNNING = "is_running";
    public static final String EXTRA_MESSAGE = "status_message";

    private NotificationManager nm;
    private WindowManager windowManager;
    private View floatingView;
    private SharedPreferences sharedPrefs;
    private Handler mainHandler;

    private File srcDir;
    private File dstDir;
    private WatchService watcher;
    private Thread monitorThread;

    // 使用 AtomicBoolean 确保线程安全
    private final AtomicBoolean serviceIsRunning = new AtomicBoolean(false);
    private final Map<String, Long> knownFiles = new HashMap<>();

    // 辅助方法，供外部判断（虽然通常用广播通信更好）
    public static boolean isRunning() {
        return false;
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

        // --- 修复点 1：优先处理停止指令 ---
        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "收到停止指令");
            stopMonitoring();
            return START_NOT_STICKY;
        }

        // --- 处理启动指令 ---
        if (action == null || ACTION_START.equals(action)) {
            // 设置前台服务通知
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, buildNotification("正在准备就绪..."),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIFICATION_ID, buildNotification("正在准备就绪..."));
            }

            String srcPath = intent.getStringExtra("SOURCE_PATH");
            String dstPath = intent.getStringExtra("TARGET_PATH");

            if (srcPath != null && dstPath != null) {
                // 防止重复启动
                if (serviceIsRunning.compareAndSet(false, true)) {
                    startMonitoringThread(srcPath, dstPath);
                } else {
                    Toast.makeText(this, "服务已在运行中", Toast.LENGTH_SHORT).show();
                    // 补发一个状态广播，防止 UI 显示错误
                    sendServiceStatusBroadcast(true, "");
                }
            }
        }

        return START_STICKY;
    }

    // --- 新增：专门的停止逻辑 ---
    private void stopMonitoring() {
        if (serviceIsRunning.compareAndSet(true, false)) {
            Log.i(TAG, "正在停止...");

            // 1. 关闭 WatchService，这会强制中断 poll() 的阻塞
            if (watcher != null) {
                try {
                    watcher.close();
                } catch (IOException e) {
                    Log.e(TAG, "关闭 WatchService 时出错", e);
                }
            }

            // 2. 清理 UI
            stopForeground(true);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
            hideFloatingWindow();

            // 3. 通知 Activity 更新按钮状态
            sendServiceStatusBroadcast(false, "已停止");

            // 4. 停止服务自身
            stopSelf();
        }
    }

    // --- 将监控逻辑封装到独立方法 ---
    private void startMonitoringThread(String srcPath, String dstPath) {
        sendServiceStatusBroadcast(true, "就绪");

        monitorThread = new Thread(() -> {
            srcDir = new File(srcPath);
            dstDir = new File(dstPath);

            if (!srcDir.isDirectory()) {
                handleFatalError("源目录无效或不存在：" + srcPath);
                return;
            }

            String loadingMsg = "正在初始化 WatchService...";
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

                String currentMsg = "";
                updateFloatingWindow(currentMsg);
                updateNotification(currentMsg);
                sendServiceStatusBroadcast(true, "运行中");

                // --- 核心循环 ---
                while (serviceIsRunning.get()) {
                    // poll(timeout) 配合循环标志位检查
                    WatchKey key = watcher.poll(100, TimeUnit.MILLISECONDS);

                    if (key == null) {
                        continue;
                    }

                    boolean activity = false;
                    for (WatchEvent<?> event : key.pollEvents()) {
                        WatchEvent.Kind<?> kind = event.kind();
                        Path fileNamePath = (Path) event.context();
                        File changedFile = new File(srcDir, fileNamePath.toFile().getName());

                        if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                        // 处理新增或修改
                        if (kind == StandardWatchEventKinds.ENTRY_CREATE ||
                                kind == StandardWatchEventKinds.ENTRY_MODIFY) {

                            // 稍微等待文件写入锁定释放
                            Thread.sleep(50);

                            if (checkAndCopySingleFile(changedFile)) {
                                activity = true;
                            }
                        }

                        // 处理删除（仅从记录中移除，不执行同步删除）
                        if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                            knownFiles.remove(changedFile.getName());
                        }
                    }

                    boolean valid = key.reset();
                    if (!valid) {
                        handleFatalError("目录失效（可能被删除或重命名）");
                        break;
                    }

                    if (activity) {
                        updateFloatingWindow("检测到活动，处理完成...");
                        updateNotification("同步完成，等待变动");
                        Thread.sleep(1000);
                        updateFloatingWindow("");
                    }
                } // end while

            } catch (InterruptedException e) {
                Log.i(TAG, "线程被中断");
            } catch (ClosedWatchServiceException e) {
                Log.i(TAG, "WatchService 已关闭");
            } catch (java.nio.file.AccessDeniedException e) {
                handleFatalError("访问被拒绝。请确保已授予文件权限。", e);
            } catch (Exception e) {
                // 只有在服务应该运行的时候报错，才算是错误
                if (serviceIsRunning.get()) {
                    handleFatalError("监控线程异常：" + e.getClass().getSimpleName(), e);
                }
            } finally {
                // 确保清理
                if (serviceIsRunning.get()) {
                    serviceIsRunning.set(false);
                }
                Log.i(TAG, "监控线程结束");
                // 确保服务最终停止
                stopSelf();
            }
        });
        monitorThread.start();
    }

    private void sendServiceStatusBroadcast(boolean isRunning, String message) {
        Intent intent = new Intent(ACTION_SERVICE_STATUS);
        intent.putExtra(EXTRA_IS_RUNNING, isRunning);
        intent.putExtra(EXTRA_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void handleFatalError(String errorMsg) {
        handleFatalError(errorMsg, null);
    }

    private void handleFatalError(String errorMsg, Throwable e) {
        if (e != null) Log.e(TAG, errorMsg, e);
        else Log.e(TAG, errorMsg);

        serviceIsRunning.set(false);
        updateNotification("致命错误！" + errorMsg);
        updateFloatingWindow("错误，监控已停止");
        mainHandler.postDelayed(this::hideFloatingWindow, 3000);
        sendServiceStatusBroadcast(false, "错误: " + errorMsg);

        stopMonitoring(); // 触发清理流程
    }

    // ... (UI 更新方法保持不变) ...

    private void showFloatingWindow(String message, int bgColor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            // 如果没有权限，就不显示悬浮窗，避免崩溃
            return;
        }

        if (floatingView != null) {
            if (floatingView instanceof TextView) {
                TextView textView = (TextView) floatingView;
                textView.setText(message);
                textView.setBackgroundColor(bgColor);
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

        int layoutType = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 50;
        params.y = 100;

        try {
            windowManager.addView(floatingView, params);
        } catch (Exception e) {
            Log.e(TAG, "添加悬浮窗失败", e);
        }
    }

    private void hideFloatingWindow() {
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                // 忽略已移除的异常
            }
        }
        floatingView = null;
    }

    private void updateFloatingWindow(final String message) {
        final int color = message.contains("错误") ? 0x80FF0000 :
                message.contains("Copying") ? 0x8000FF00 : 0x80000000;
        mainHandler.post(() -> showFloatingWindow(message, color));
    }

    private void updateNotification(String msg) {
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(msg));
        }
    }

    private Notification buildNotification(String msg) {
        // 修改说明：
        // 1. 去掉了 notificationIntent 和 pendingIntent (点击不跳转)
        // 2. 去掉了 stopIntent 和 stopPendingIntent (去掉停止按钮)
        // 3. 去掉了 .setContentIntent() 和 .addAction()

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("哦！我可爱的小南娘")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                // 设为正在进行中，防止被用户左右滑动清除（前台服务必须项）
                .setOngoing(true);

        return builder.build();
    }


    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "状态", NotificationManager.IMPORTANCE_LOW);
            if (nm != null) {
                nm.createNotificationChannel(c);
            }
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 任务被移除时的保活逻辑（可选）
        Intent restartService = new Intent(getApplicationContext(), this.getClass());
        restartService.setPackage(getPackageName());
        PendingIntent restartIntent = PendingIntent.getService(
                getApplicationContext(), 1, restartService,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE : PendingIntent.FLAG_ONE_SHOT
        );
        AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RESTART_DELAY_MS, restartIntent);
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        // 最后的清理防线
        stopMonitoring();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    // --- 核心业务逻辑 ---

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

        // 简单的去重逻辑：如果文件名相同且修改时间没变，就不复制
        if (!knownFiles.containsKey(name) || knownFiles.get(name) < lastMod) {
            updateFloatingWindow("Copying: " + name);
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
        // 如果没有设置任何有效关键词，默认不过滤（返回true）；否则必须匹配
        return !hasValidKeyword;
    }

    // --- 文件复制逻辑（补全了结尾） ---
    private boolean doCopy(File srcFile, File dstDir, boolean shouldOverwrite) {
        File targetFile;
        try {
            String name = srcFile.getName();

            if (shouldOverwrite) {
                targetFile = new File(dstDir, name);
                if (targetFile.exists()) targetFile.delete();
            } else {
                targetFile = new File(dstDir, name);
                if (targetFile.exists()) {
                    // 自动重命名逻辑：name.1.txt
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

            // 使用 try-with-resources 自动关闭流
            try (FileInputStream in = new FileInputStream(srcFile);
                 FileOutputStream out = new FileOutputStream(targetFile)) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "复制文件失败: " + srcFile.getName(), e);
            return false;
        }
    }
}
