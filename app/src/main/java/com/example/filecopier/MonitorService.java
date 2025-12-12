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
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class MonitorService extends Service {
    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "FileMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final long RESTART_DELAY_MS = 5000;

    // 广播 Action 和 Extras
    public static final String ACTION_SERVICE_STATUS = "com.example.filecopier.SERVICE_STATUS";
    public static final String EXTRA_RUNNING = "running_status";
    public static final String EXTRA_MESSAGE = "status_message"; // 新增：用于悬浮窗显示消息

    private static volatile boolean serviceIsRunning = false;

    private Map<String, Long> knownFiles = new HashMap<>();
    private NotificationManager nm;
    private SharedPreferences sharedPrefs;

    // --- 静态方法：供 Activity 检查服务是否正在运行 ---
    public static boolean isRunning() {
        return serviceIsRunning;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        sharedPrefs = getSharedPreferences(SettingsActivity.PREF_NAME, Context.MODE_PRIVATE);
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

        String srcStr = intent.getStringExtra("SOURCE_URI");
        String dstStr = intent.getStringExtra("TARGET_URI");

        if (srcStr != null && dstStr != null && !serviceIsRunning) {
            serviceIsRunning = true;

            // 立即发送启动广播
            sendServiceStatusBroadcast(true, "监控已启动...");

            new Thread(() -> {
                Uri src = Uri.parse(srcStr);
                Uri dst = Uri.parse(dstStr);

                initKnownFiles(src);

                String currentMsg = "监控中：一切静悄悄";
                while (serviceIsRunning) {
                    try {
                        boolean copied = checkAndCopy(src, dst);

                        String newMsg = copied ? "刚刚复制了新文件！" : "监控中：一切静悄悄";
                        if (!newMsg.equals(currentMsg)) {
                            currentMsg = newMsg;
                            updateNotification(currentMsg);
                            sendServiceStatusBroadcast(true, currentMsg);
                        } else if (copied) {
                            // 如果是复制事件，也发送广播更新悬浮窗颜色
                            sendServiceStatusBroadcast(true, currentMsg);
                        }

                        Thread.sleep(2000);
                    } catch (Exception e) {
                        Log.e(TAG, "Monitor loop failed, stopping service.", e);
                        String errorMsg = "发生致命错误，监控暂停！请检查权限。";
                        updateNotification(errorMsg);
                        sendServiceStatusBroadcast(false, errorMsg); // 错误停止广播
                        serviceIsRunning = false;
                    }
                }
            }).start();
        }
        return START_STICKY;
    }

    /** 发送服务运行状态给 MainActivity */
    private void sendServiceStatusBroadcast(boolean isRunning, String message) {
        Intent intent = new Intent(ACTION_SERVICE_STATUS);
        intent.putExtra(EXTRA_RUNNING, isRunning);
        intent.putExtra(EXTRA_MESSAGE, message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        Log.d(TAG, "Sent status broadcast: " + isRunning + ", Msg: " + message);
    }

    // ... (initKnownFiles, checkAndCopy 等 I/O 逻辑保持不变) ...

    private void initKnownFiles(Uri uri) {
        DocumentFile root = DocumentFile.fromTreeUri(this, uri);
        if (root != null && root.exists()) {
            knownFiles.clear();
            for (DocumentFile f : root.listFiles()) {
                if (f.isFile()) knownFiles.put(f.getName(), f.lastModified());
            }
            Log.d(TAG, "Initialized " + knownFiles.size() + " known files.");
        }
    }

    private boolean checkAndCopy(Uri src, Uri dst) {
        DocumentFile srcDir = DocumentFile.fromTreeUri(this, src);
        DocumentFile dstDir = DocumentFile.fromTreeUri(this, dst);
        if (srcDir == null || dstDir == null || !srcDir.exists() || !dstDir.exists()) {
            Log.e(TAG, "Source or Destination directory check failed. Permissions likely revoked.");
            return false;
        }

        int overwriteModeIndex = sharedPrefs.getInt(SettingsActivity.KEY_OVERWRITE_MODE_INDEX, 0);
        boolean shouldOverwrite = (overwriteModeIndex == 1);
        boolean shouldDeleteMirror = sharedPrefs.getBoolean(SettingsActivity.KEY_DELETE_MIRROR, false);
        boolean isFilterEnabled = sharedPrefs.getBoolean(SettingsActivity.KEY_CONTENT_FILTER_ENABLED, false);
        String keywordsString = sharedPrefs.getString(SettingsActivity.KEY_FILTER_KEYWORDS, "");
        String[] keywords = keywordsString.split(",");
        boolean activity = false;

        for (DocumentFile f : srcDir.listFiles()) {
            if (f.isDirectory() || f.getName() == null) continue;
            String name = f.getName();
            long lastMod = f.lastModified();

            if (isFilterEnabled && !checkFileNameFilter(name, keywords)) continue;

            if (!knownFiles.containsKey(name) || knownFiles.get(name) < lastMod) {
                Log.i(TAG, "Found new or modified file: " + name);
                if (doCopy(f, dstDir, shouldOverwrite)) {
                    knownFiles.put(name, lastMod);
                    activity = true;
                    Log.i(TAG, "Successfully copied: " + name);
                } else {
                    Log.e(TAG, "FAILED to copy file: " + name + ". Check file I/O errors below.");
                }
            }
        }

        if (shouldDeleteMirror) {
            if (syncDeletions(srcDir, dstDir)) {
                activity = true;
            }
        }

        return activity;
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

    private boolean syncDeletions(DocumentFile srcDir, DocumentFile dstDir) {
        DocumentFile[] dstFiles = dstDir.listFiles();
        boolean deleted = false;
        for (DocumentFile dstFile : dstFiles) {
            if (!dstFile.isFile() || dstFile.getName() == null) continue;
            DocumentFile srcFile = srcDir.findFile(dstFile.getName());
            if (srcFile == null || !srcFile.exists()) {
                if (dstFile.delete()) {
                    knownFiles.remove(dstFile.getName());
                    deleted = true;
                    Log.i(TAG, "Deleted mirror file: " + dstFile.getName());
                } else {
                    Log.w(TAG, "Failed to delete mirror file: " + dstFile.getName() + ". Permission issue?");
                }
            }
        }
        return deleted;
    }

    private boolean doCopy(DocumentFile srcFile, DocumentFile dstDir, boolean shouldOverwrite) {
        DocumentFile target = null;
        try {
            String name = srcFile.getName();

            if (shouldOverwrite) {
                target = dstDir.findFile(name);
                if (target != null && target.exists()) target.delete();
                target = dstDir.createFile(srcFile.getType(), name);
            } else {
                if (dstDir.findFile(name) != null) {
                    String pureName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                    String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                    int i = 1;
                    String newName;
                    do {
                        newName = pureName + "." + i + ext;
                        i++;
                    } while (dstDir.findFile(newName) != null);
                    name = newName;
                }
                target = dstDir.createFile(srcFile.getType(), name);
            }

            if (target == null) {
                Log.e(TAG, "Target file creation failed for: " + name);
                return false;
            }

            try (InputStream in = getContentResolver().openInputStream(srcFile.getUri());
                 OutputStream out = getContentResolver().openOutputStream(target.getUri())) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "File I/O Error during copy of " + srcFile.getName() + " to " + (target != null ? target.getName() : "N/A"), e);
            return false;
        }
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
        serviceIsRunning = false;
        if (nm != null) nm.cancel(NOTIFICATION_ID);
        stopForeground(true);

        sendServiceStatusBroadcast(false, "服务已停止"); // 停止广播

        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}