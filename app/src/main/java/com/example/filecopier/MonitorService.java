package com.example.filecopier;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
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
import java.util.concurrent.TimeUnit;

public class MonitorService extends Service {
    private static final String TAG = "MonitorService";
    private static final String CHANNEL_ID = "FileMonitorChannel";
    private boolean isRunning = false;
    private Thread monitorThread;

    // 核心修改：使用 Map 记录文件名及其最后修改时间戳 (解决问题 1 & 2)
    private Map<String, Long> knownFiles = new HashMap<>();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if ("STOP".equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String sourceUriStr = intent.getStringExtra("SOURCE_URI");
        String targetUriStr = intent.getStringExtra("TARGET_URI");

        if (sourceUriStr != null && targetUriStr != null) {
            startForegroundNotification();
            startMonitoring(Uri.parse(sourceUriStr), Uri.parse(targetUriStr));
        }

        return START_STICKY;
    }

    private void startForegroundNotification() {
        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("文件监控运行中")
                .setContentText("正在检测文件修改和新增...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();

        // API 34+ 必须使用特定类型，确保前台服务优先级最高
        startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
    }

    private void startMonitoring(Uri sourceUri, Uri targetUri) {
        if (isRunning) return;
        isRunning = true;

        initializeKnownFiles(sourceUri);

        monitorThread = new Thread(() -> {
            while (isRunning) {
                try {
                    checkAndCopy(sourceUri, targetUri);
                    // 间隔改为 10 秒，避免过于频繁唤醒导致系统休眠
                    Thread.sleep(TimeUnit.SECONDS.toMillis(10));
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    broadcastLog("错误: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
        monitorThread.start();
        broadcastLog("服务已启动，开始监控...");
    }

    // 初始化时记录当前文件夹中所有文件的名称和时间戳
    private void initializeKnownFiles(Uri sourceUri) {
        DocumentFile sourceDir = DocumentFile.fromTreeUri(this, sourceUri);
        if (sourceDir != null && sourceDir.isDirectory()) {
            knownFiles.clear();
            for (DocumentFile file : sourceDir.listFiles()) {
                // 确保只记录文件 (解决问题 3)
                if (file.isFile()) {
                    knownFiles.put(file.getName(), file.lastModified());
                }
            }
            broadcastLog("初始化完成，当前已有文件数: " + knownFiles.size());
        } else {
            broadcastLog("初始化失败: 无法访问源路径");
        }
    }

    // 核心检测与复制逻辑
    private void checkAndCopy(Uri sourceUri, Uri targetUri) {
        DocumentFile sourceDir = DocumentFile.fromTreeUri(this, sourceUri);
        DocumentFile targetDir = DocumentFile.fromTreeUri(this, targetUri);

        if (sourceDir == null || !sourceDir.exists() || targetDir == null || !targetDir.exists()) {
            broadcastLog("错误：无法访问源文件夹或目标文件夹");
            return;
        }

        DocumentFile[] currentFiles = sourceDir.listFiles();

        for (DocumentFile file : currentFiles) {
            String fileName = file.getName();

            // 解决问题 3：确保是文件
            if (file.isFile() && fileName != null) {
                long currentModified = file.lastModified();

                // 解决问题 2：检测新增或修改
                if (!knownFiles.containsKey(fileName) || knownFiles.get(fileName) < currentModified) {

                    String status = knownFiles.containsKey(fileName) ? "文件被修改" : "发现新文件";
                    broadcastLog(status + ": " + fileName);

                    boolean success = copyFile(file, targetDir, status.contains("修改")); // 传递是否是修改状态
                    if (success) {
                        // 复制成功后，更新 Map 中的时间戳
                        knownFiles.put(fileName, currentModified);
                        broadcastLog("复制成功: " + fileName);
                    } else {
                        broadcastLog("复制失败: " + fileName);
                    }
                }
            }
        }
    }

    // 文件复制函数 (包含编号逻辑)
    private boolean copyFile(DocumentFile srcFile, DocumentFile destDir, boolean isModified) {
        InputStream in = null;
        OutputStream out = null;
        try {
            String baseName = srcFile.getName();
            String mimeType = srcFile.getType();

            // 解决问题 2：如果文件已修改，则进行编号
            if (isModified) {
                String name = baseName;
                String ext = "";
                int dotIndex = baseName.lastIndexOf('.');
                if (dotIndex > 0) {
                    name = baseName.substring(0, dotIndex);
                    ext = baseName.substring(dotIndex);
                }

                int counter = 1;
                String newName;
                DocumentFile numberedFile;

                // 循环查找下一个可用的编号 (text.1.txt, text.2.txt, etc.)
                do {
                    newName = name + "." + counter + ext;
                    numberedFile = destDir.findFile(newName);
                    counter++;
                } while (numberedFile != null);

                baseName = newName; // 使用带编号的新文件名
            }

            // 如果是新文件，或者 (已修改且找到了新编号)，则创建文件
            DocumentFile destFile = destDir.createFile(mimeType, baseName);
            if (destFile == null) return false;

            in = getContentResolver().openInputStream(srcFile.getUri());
            out = getContentResolver().openOutputStream(destFile.getUri());

            if (in == null || out == null) return false;

            byte[] buffer = new byte[4096];
            int read;

            // 修复了 read 变量未初始化 bug
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return true;
        } catch (Exception e) {
            broadcastLog("Copy Error: " + e.getMessage());
            return false;
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (Exception ignored) {}
        }
    }

    private void broadcastLog(String message) {
        Intent intent = new Intent("MonitorServiceLog");
        intent.putExtra("log", message);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (monitorThread != null) monitorThread.interrupt();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "File Monitor Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(serviceChannel);
        }
    }
}