package com.example.filecopier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import androidx.documentfile.provider.DocumentFile;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

public class MonitorService extends Service {
    private static final String CHANNEL_ID = "FileMonitorChannel";
    private static final int NOTIFICATION_ID = 1;
    private boolean isRunning = false;
    private Map<String, Long> knownFiles = new HashMap<>();
    private NotificationManager nm;

    @Override
    public void onCreate() {
        super.onCreate();
        nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        // 立即显示通知，防止系统杀掉
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("准备监控中..."),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0);

        String srcStr = intent.getStringExtra("SOURCE_URI");
        String dstStr = intent.getStringExtra("TARGET_URI");

        if (srcStr != null && dstStr != null && !isRunning) {
            isRunning = true;
            new Thread(() -> {
                Uri src = Uri.parse(srcStr);
                Uri dst = Uri.parse(dstStr);
                // 初始化
                initKnownFiles(src);
                while (isRunning) {
                    try {
                        boolean copied = checkAndCopy(src, dst);
                        updateNotification(copied ? "完事了！" : "正在等待");
                        Thread.sleep(2000); // 2秒查一次
                    } catch (Exception e) { e.printStackTrace(); }
                }
            }).start();
        }
        return START_STICKY;
    }

    private void initKnownFiles(Uri uri) {
        DocumentFile root = DocumentFile.fromTreeUri(this, uri);
        if (root != null) {
            for (DocumentFile f : root.listFiles()) {
                if (f.isFile()) knownFiles.put(f.getName(), f.lastModified());
            }
        }
    }

    private boolean checkAndCopy(Uri src, Uri dst) {
        DocumentFile srcDir = DocumentFile.fromTreeUri(this, src);
        DocumentFile dstDir = DocumentFile.fromTreeUri(this, dst);
        if (srcDir == null || dstDir == null) return false;

        boolean activity = false;
        for (DocumentFile f : srcDir.listFiles()) {
            if (f.isDirectory()) continue; // 忽略文件夹
            String name = f.getName();
            long lastMod = f.lastModified();

            if (!knownFiles.containsKey(name) || knownFiles.get(name) < lastMod) {
                boolean isUpdate = knownFiles.containsKey(name);
                if (doCopy(f, dstDir, isUpdate)) {
                    knownFiles.put(name, lastMod);
                    activity = true;
                }
            }
        }
        return activity;
    }

    private boolean doCopy(DocumentFile srcFile, DocumentFile dstDir, boolean isUpdate) {
        try {
            String name = srcFile.getName();
            if (isUpdate) {
                // 自动重命名逻辑：file.1.txt
                String pureName = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name;
                String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
                int i = 1;
                while (dstDir.findFile(pureName + "." + i + ext) != null) i++;
                name = pureName + "." + i + ext;
            }
            DocumentFile target = dstDir.createFile(srcFile.getType(), name);
            try (InputStream in = getContentResolver().openInputStream(srcFile.getUri());
                 OutputStream out = getContentResolver().openOutputStream(target.getUri())) {
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
            }
            return true;
        } catch (Exception e) { return false; }
    }

    private void updateNotification(String msg) {
        nm.notify(NOTIFICATION_ID, buildNotification(msg));
    }

    private Notification buildNotification(String msg) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("小南娘好可爱")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setOngoing(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "监控状态", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(c);
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}