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
import android.graphics.drawable.GradientDrawable;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPReply;

public class MonitorService extends Service {
    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "FileMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final long RESTART_DELAY_MS = 1000;
    private static final int IDLE_WIDTH_DP = 1;
    private static final int IDLE_HEIGHT_DP = 1;
    private static final long DOT_COLLAPSE_DELAY_MS = 3000;
    public static final String ACTION_START = "ACTION_START";
    public static final String ACTION_STOP = "ACTION_STOP";
    public static final String ACTION_SERVICE_STATUS = "o.my.wiening.SERVICE_STATUS";
    public static final String EXTRA_IS_RUNNING = "is_running";
    public static final String EXTRA_MESSAGE = "status_message";
    private NotificationManager nm;
    private WindowManager windowManager;
    private View floatingCopyView;
    private View floatingUploadView;
    private Runnable copyCollapseTask;
    private Runnable uploadCollapseTask;
    private SharedPreferences sharedPrefs;
    private Handler mainHandler;
    private ExecutorService executorService;
    private BlockingQueue<File> uploadQueue;
    private Thread uploadConsumerThread;
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
                        sendServiceStatusBroadcast(true, "");
                    }
                } else {
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
        startUploadConsumer();
        showDots();
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
            if (uploadConsumerThread != null && uploadConsumerThread.isAlive()) {
                uploadConsumerThread.interrupt();
            }
            stopForeground(true);
            hideAllFloatingWindows();
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
            srcPathObj.register(watcher, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
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
                try { watcher.close(); } catch (IOException e) { /* ignore */ }
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
        showCopyWindow("⚠️ " + errorMsg);
        mainHandler.postDelayed(this::hideCopyWindow, 5000);
    }

    @Override
    public void onDestroy() {
        stopMonitoring();
        super.onDestroy();
    }

    @Nullable @Override
    public IBinder onBind(Intent i) { return null; }

    private void checkAndCopySingleFile(File srcFile, File dstDir, Map<String, Long> knownFiles) {
        if (!srcFile.exists() || !srcFile.isFile()) return;
        long lastMod = srcFile.lastModified();
        String originalName = srcFile.getName();
        if (knownFiles.containsKey(originalName) && knownFiles.get(originalName) >= lastMod) {
            return;
        }
        boolean isFilterEnabled = sharedPrefs.getBoolean(SettingsActivity.KEY_CONTENT_FILTER_ENABLED, false);
        if (isFilterEnabled) {
            String keywordsString = sharedPrefs.getString(SettingsActivity.KEY_FILTER_KEYWORDS, "");
            if (!checkFileNameFilter(originalName, keywordsString.split(","))) {
                return;
            }
        }
        showCopyWindow("复制中: " + originalName);
        String actualFileName = doCopy(srcFile, dstDir);
        if (actualFileName != null) {
            knownFiles.put(originalName, lastMod);
            showCopyWindow("✅ " + originalName);
            mainHandler.postDelayed(this::hideCopyWindow, 1500);

            // 丢入上传队列——上传线程独立消费，不阻塞下一次复制
            boolean ftpEnabled = sharedPrefs.getBoolean(SettingsActivity.KEY_FTP_ENABLED, false);
            if (ftpEnabled && uploadQueue != null) {
                uploadQueue.offer(new File(dstDir, actualFileName));
            }
        } else {
            showCopyWindow("❌ 复制失败: " + originalName);
            mainHandler.postDelayed(this::hideCopyWindow, 2000);
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

    // ★★★ CORE FIX: Removed all overwrite logic, forcing overwrite every time ★★★
    private String doCopy(File srcFile, File dstDir) {
        File targetFile = new File(dstDir, srcFile.getName());

        if (targetFile.exists()) {
            targetFile.delete(); // Always delete if exists
        }

        try (FileInputStream in = new FileInputStream(srcFile); FileOutputStream out = new FileOutputStream(targetFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            Log.i(TAG, "成功覆盖: " + srcFile.getName() + " -> " + targetFile.getName());
            return targetFile.getName();
        } catch (Exception e) {
            Log.e(TAG, "复制文件失败: " + srcFile.getName(), e);
            return null;
        }
    }

    // ── 上传消费者：独立线程从队列取文件上传 ──
    private void startUploadConsumer() {
        uploadQueue = new LinkedBlockingQueue<>();
        uploadConsumerThread = new Thread(() -> {
            while (serviceIsRunning.get() || !uploadQueue.isEmpty()) {
                try {
                    File file = uploadQueue.poll(1, TimeUnit.SECONDS);
                    if (file != null && file.exists()) {
                        uploadSingleFile(file);
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
            uploadQueue.clear();
        }, "FTP-Upload");
        uploadConsumerThread.start();
    }

    private void uploadSingleFile(File localFile) {
        String host = sharedPrefs.getString(SettingsActivity.KEY_FTP_HOST, "");
        int port = sharedPrefs.getInt(SettingsActivity.KEY_FTP_PORT, 21);
        String username = sharedPrefs.getString(SettingsActivity.KEY_FTP_USERNAME, "");
        String password = sharedPrefs.getString(SettingsActivity.KEY_FTP_PASSWORD, "");
        String remotePath = sharedPrefs.getString(SettingsActivity.KEY_FTP_REMOTE_PATH, "/");

        if (host.isEmpty()) {
            Log.w(TAG, "FTP 主机为空，跳过: " + localFile.getName());
            return;
        }

        showUploadWindow("上传中: " + localFile.getName());

        FTPClient ftp = new FTPClient();
        try {
            ftp.setConnectTimeout(10000);
            ftp.setDataTimeout(30000);
            ftp.connect(host, port);
            int reply = ftp.getReplyCode();
            if (!FTPReply.isPositiveCompletion(reply)) {
                Log.e(TAG, "FTP 连接被拒: " + reply);
                showUploadWindow("❌ " + localFile.getName());
                mainHandler.postDelayed(this::hideUploadWindow, 3000);
                ftp.disconnect();
                return;
            }

            ftp.setControlEncoding("UTF-8");
            if (!ftp.login(username, password)) {
                Log.e(TAG, "FTP 登录失败");
                showUploadWindow("❌ " + localFile.getName());
                mainHandler.postDelayed(this::hideUploadWindow, 3000);
                ftp.logout();
                return;
            }

            ftp.enterLocalPassiveMode();
            ftp.setFileType(FTP.BINARY_FILE_TYPE);

            if (!remotePath.isEmpty() && !remotePath.equals("/")) {
                String[] dirs = remotePath.split("/");
                for (String dir : dirs) {
                    if (dir.isEmpty()) continue;
                    if (!ftp.changeWorkingDirectory(dir)) {
                        ftp.makeDirectory(dir);
                        if (!ftp.changeWorkingDirectory(dir)) {
                            ftp.changeWorkingDirectory("/");
                            break;
                        }
                    }
                }
            }

            try (FileInputStream fis = new FileInputStream(localFile)) {
                if (ftp.storeFile(localFile.getName(), fis)) {
                    Log.i(TAG, "FTP 上传成功: " + localFile.getName());
                    showUploadWindow("✅ " + localFile.getName());
                    mainHandler.postDelayed(this::hideUploadWindow, 2000);
                } else {
                    Log.e(TAG, "FTP 上传失败: " + localFile.getName());
                    showUploadWindow("❌ " + localFile.getName());
                    mainHandler.postDelayed(this::hideUploadWindow, 3000);
                }
            }

            ftp.logout();
        } catch (IOException e) {
            Log.e(TAG, "FTP 异常: " + localFile.getName(), e);
            showUploadWindow("❌ " + localFile.getName());
            mainHandler.postDelayed(this::hideUploadWindow, 3000);
        } finally {
            try { if (ftp.isConnected()) ftp.disconnect(); } catch (IOException ignored) {}
        }
    }

    // ── 双悬浮窗系统（始终显示，保活防杀） ──
    // 复制窗 (id=1): 顶部靠左  x=50,y=100   ─ 黑点 → 文字 → 缩回黑点
    // 上传窗 (id=2): 复制窗下方 x=50,y=160  ─ 黑点 → 文字 → 缩回黑点

    private void showDots() {
        showDot(1, 100);
        boolean ftpEnabled = sharedPrefs.getBoolean(SettingsActivity.KEY_FTP_ENABLED, false);
        if (ftpEnabled) showDot(2, 160);
    }

    private void showDot(int id, int yOffset) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) return;
        if (windowManager == null) windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        mainHandler.post(() -> {
            View view = (id == 1) ? floatingCopyView : floatingUploadView;
            if (view != null) {
                setViewToIdle(view);
                return;
            }

            float density = getResources().getDisplayMetrics().density;
            int wPx = (int) (IDLE_WIDTH_DP * density);
            int hPx = (int) (IDLE_HEIGHT_DP * density);

            TextView tv = new TextView(this);
            tv.setGravity(Gravity.CENTER);

            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(0x00000000);
            tv.setBackground(bg);
            tv.setText("");

            view = tv;
            if (id == 1) floatingCopyView = view; else floatingUploadView = view;

            int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    wPx, hPx,
                    layoutFlag,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 50;
            params.y = yOffset;
            try {
                windowManager.addView(view, params);
            } catch (Exception e) {
                if (id == 1) floatingCopyView = null; else floatingUploadView = null;
            }
        });
    }

    private void setViewToIdle(View view) {
        if (!(view instanceof TextView)) return;
        TextView tv = (TextView) view;
        float density = getResources().getDisplayMetrics().density;
        int wPx = (int) (IDLE_WIDTH_DP * density);
        int hPx = (int) (IDLE_HEIGHT_DP * density);

        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(0x00000000);
        tv.setBackground(bg);
        tv.setText("");
        tv.setPadding(0, 0, 0, 0);

        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) tv.getLayoutParams();
        if (lp != null) {
            lp.width = wPx;
            lp.height = hPx;
            try { windowManager.updateViewLayout(tv, lp); } catch (Exception ignored) {}
        }
    }

    private void expandToText(int id, String message) {
        mainHandler.post(() -> {
            View view = (id == 1) ? floatingCopyView : floatingUploadView;
            if (view == null) return;
            if (!(view instanceof TextView)) return;
            TextView tv = (TextView) view;

            tv.setBackgroundColor(0x99000000);
            tv.setText(message);
            tv.setTextSize(12);
            tv.setPadding(16, 8, 16, 8);

            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) tv.getLayoutParams();
            if (lp != null) {
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                try { windowManager.updateViewLayout(tv, lp); } catch (Exception ignored) {}
            }

            // Cancel previous collapse task and schedule new one
            Runnable old = (id == 1) ? copyCollapseTask : uploadCollapseTask;
            if (old != null) mainHandler.removeCallbacks(old);
            Runnable collapse = () -> setViewToIdle(tv);
            if (id == 1) copyCollapseTask = collapse; else uploadCollapseTask = collapse;
            mainHandler.postDelayed(collapse, DOT_COLLAPSE_DELAY_MS);
        });
    }

    private void showCopyWindow(String message) {
        showDot(1, 100);               // ensure dot exists
        expandToText(1, message);      // expand to show text, auto-collapse after 3s
    }

    private void showUploadWindow(String message) {
        showDot(2, 160);
        expandToText(2, message);
    }

    private void hideCopyWindow() {
        View v = floatingCopyView;
        if (v != null) setViewToIdle(v);
    }

    private void hideUploadWindow() {
        View v = floatingUploadView;
        if (v != null) setViewToIdle(v);
    }

    private void hideAllFloatingWindows() {
        mainHandler.post(() -> {
            if (copyCollapseTask != null) mainHandler.removeCallbacks(copyCollapseTask);
            if (uploadCollapseTask != null) mainHandler.removeCallbacks(uploadCollapseTask);
            if (floatingCopyView != null && windowManager != null) {
                try { windowManager.removeView(floatingCopyView); } catch (Exception ignored) {}
                floatingCopyView = null;
            }
            if (floatingUploadView != null && windowManager != null) {
                try { windowManager.removeView(floatingUploadView); } catch (Exception ignored) {}
                floatingUploadView = null;
            }
        });
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
