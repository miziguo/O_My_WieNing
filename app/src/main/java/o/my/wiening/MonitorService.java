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

import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

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
    @Nullable
    private static volatile String lastStatusMessage;

    public static boolean isRunning() {
        return serviceIsRunning.get();
    }

    @Nullable
    public static String getLastStatusMessage() {
        return lastStatusMessage;
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
        String runningMsg = "正在监控 " + groups.size() + " 个目录";
        updateNotification(runningMsg);
        sendServiceStatusBroadcast(true, runningMsg);
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
        lastStatusMessage = message;
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
            boolean webdavEnabled = sharedPrefs.getBoolean(SettingsActivity.KEY_WEBDAV_ENABLED, false);
            if (webdavEnabled && uploadQueue != null) {
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

    private String doCopy(File srcFile, File dstDir) {
        String origName = srcFile.getName();
        File targetFile = new File(dstDir, origName);

        if (targetFile.exists()) {
            boolean dedup = sharedPrefs.getBoolean(SettingsActivity.KEY_DEDUP_FILENAME, false);
            if (!dedup) {
                targetFile.delete(); // 覆盖模式
            } else {
                // 自动编号: file.txt → file(1).txt → file(2).txt ...
                int dot = origName.lastIndexOf('.');
                String base = (dot > 0) ? origName.substring(0, dot) : origName;
                String ext = (dot > 0) ? origName.substring(dot) : "";
                int counter = 1;
                do {
                    targetFile = new File(dstDir, base + "(" + counter + ")" + ext);
                    counter++;
                } while (targetFile.exists());
            }
        }

        try (FileInputStream in = new FileInputStream(srcFile);
             FileOutputStream out = new FileOutputStream(targetFile)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            Log.i(TAG, "复制完成: " + srcFile.getName() + " -> " + targetFile.getName());
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
        }, "WebDAV-Upload");
        uploadConsumerThread.start();
    }

    private void uploadSingleFile(File localFile) {
        String baseUrl = sharedPrefs.getString(SettingsActivity.KEY_WEBDAV_URL, "");
        String remotePath = sharedPrefs.getString(SettingsActivity.KEY_WEBDAV_REMOTE_PATH, "");
        String username = sharedPrefs.getString(SettingsActivity.KEY_WEBDAV_USERNAME, "");
        String password = sharedPrefs.getString(SettingsActivity.KEY_WEBDAV_PASSWORD, "");

        if (baseUrl.isEmpty()) {
            Log.w(TAG, "WebDAV 地址为空，跳过: " + localFile.getName());
            return;
        }

        showUploadWindow("上传中: " + localFile.getName());

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();

        try {
            // 构建完整上传 URL: baseUrl + remotePath + filename
            String fullBase = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
            String rp = (remotePath != null && !remotePath.isEmpty()) ? remotePath : "";
            if (rp.startsWith("/")) rp = rp.substring(1);
            if (!rp.isEmpty() && !rp.endsWith("/")) rp += "/";
            String url = fullBase + rp + localFile.getName();

            // 先尝试创建目标目录
            String dirUrl = fullBase + rp;
            ensureWebdavDir(client, dirUrl, username, password);

            MediaType mediaType = MediaType.parse("application/octet-stream");
            RequestBody body = RequestBody.create(localFile, mediaType);

            Request.Builder reqBuilder = new Request.Builder()
                    .url(url)
                    .put(body);

            if (!username.isEmpty()) {
                reqBuilder.header("Authorization", Credentials.basic(username, password));
            }

            try (Response response = client.newCall(reqBuilder.build()).execute()) {
                int code = response.code();
                if (code >= 200 && code < 300) {
                    Log.i(TAG, "WebDAV 上传成功: " + localFile.getName());
                    showUploadWindow("✅ " + localFile.getName());
                    mainHandler.postDelayed(this::hideUploadWindow, 2000);
                } else {
                    Log.e(TAG, "WebDAV 上传失败: " + localFile.getName() + " | " + code + " " + response.message());
                    showUploadWindow("❌ " + localFile.getName() + " (" + code + ")");
                    mainHandler.postDelayed(this::hideUploadWindow, 3000);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "WebDAV 异常: " + localFile.getName() + " | " + e.toString());
            showUploadWindow("❌ " + localFile.getName() + " (" + e.getClass().getSimpleName() + ")");
            mainHandler.postDelayed(this::hideUploadWindow, 3000);
        }
    }

    /** WebDAV PROPFIND 最小 XML body（兼容严格服务器） */
    private static final MediaType XML_MEDIA_TYPE = MediaType.parse("application/xml; charset=utf-8");
    private static final String PROPFIND_BODY =
            "<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n" +
            "<D:propfind xmlns:D=\"DAV:\">\n" +
            "  <D:prop>\n" +
            "    <D:displayname/>\n" +
            "    <D:resourcetype/>\n" +
            "  </D:prop>\n" +
            "</D:propfind>";

    /** 确保 WebDAV 路径存在（PROPFIND 探测 + MKCOL 创建），失败不阻塞上传 */
    private void ensureWebdavDir(OkHttpClient client, String dirUrl, String username, String password) {
        try {
            // PROPFIND 探测目录是否存在（带 XML body + Content-Type）
            Request.Builder reqBuilder = new Request.Builder()
                    .url(dirUrl)
                    .method("PROPFIND", RequestBody.create(PROPFIND_BODY, XML_MEDIA_TYPE))
                    .header("Depth", "0");
            if (!username.isEmpty()) {
                reqBuilder.header("Authorization", Credentials.basic(username, password));
            }
            try (Response resp = client.newCall(reqBuilder.build()).execute()) {
                if (resp.code() >= 200 && resp.code() < 300) {
                    return; // 目录已存在
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "WebDAV PROPFIND 异常（跳过目录检查）: " + e.getMessage());
        }

        // 目录不存在，尝试 MKCOL 创建
        try {
            Request.Builder reqBuilder = new Request.Builder()
                    .url(dirUrl)
                    .method("MKCOL", null);
            if (!username.isEmpty()) {
                reqBuilder.header("Authorization", Credentials.basic(username, password));
            }
            try (Response resp = client.newCall(reqBuilder.build()).execute()) {
                Log.i(TAG, "WebDAV MKCOL: " + dirUrl + " -> " + resp.code());
            }
        } catch (Exception e) {
            Log.w(TAG, "WebDAV MKCOL 失败（跳过目录创建，直接上传）: " + e.getMessage());
        }
    }

    // ── 双悬浮窗系统（始终显示，保活防杀） ──
    // 复制窗 (id=1): 顶部靠左  x=50,y=100   ─ 黑点 → 文字 → 缩回黑点
    // 上传窗 (id=2): 复制窗下方 x=50,y=160  ─ 黑点 → 文字 → 缩回黑点

    private void showDots() {
        showDot(1, 100);
        boolean webdavEnabled = sharedPrefs.getBoolean(SettingsActivity.KEY_WEBDAV_ENABLED, false);
        if (webdavEnabled) showDot(2, 160);
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
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "文件监控服务", NotificationManager.IMPORTANCE_MIN);
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
