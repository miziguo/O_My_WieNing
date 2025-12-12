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
                    Thread.sleep(TimeUnit.SECONDS.toMillis(10));
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    // 仅内部打印，因为日志功能已移除
                    // broadcastLog("错误: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
        monitorThread.start();
    }

    private void initializeKnownFiles(Uri sourceUri) {
        DocumentFile sourceDir = DocumentFile.fromTreeUri(this, sourceUri);
        if (sourceDir != null && sourceDir.isDirectory()) {
            knownFiles.clear();
            for (DocumentFile file : sourceDir.listFiles()) {
                if (file.isFile()) {
                    knownFiles.put(file.getName(), file.lastModified());
                }
            }
        }
    }

    private void checkAndCopy(Uri sourceUri, Uri targetUri) {
        DocumentFile sourceDir = DocumentFile.fromTreeUri(this, sourceUri);
        DocumentFile targetDir = DocumentFile.fromTreeUri(this, targetUri);

        if (sourceDir == null || !sourceDir.exists() || targetDir == null || !targetDir.exists()) {
            // 无法发送日志，只能依赖通知栏提示服务运行
            return;
        }

        DocumentFile[] currentFiles = sourceDir.listFiles();

        for (DocumentFile file : currentFiles) {
            String fileName = file.getName();

            if (file.isFile() && fileName != null) {
                long currentModified = file.lastModified();

                if (!knownFiles.containsKey(fileName) || knownFiles.get(fileName) < currentModified) {

                    boolean isModified = knownFiles.containsKey(fileName);

                    boolean success = copyFile(file, targetDir, isModified);
                    if (success) {
                        knownFiles.put(fileName, currentModified);
                    } else {
                        // 复制失败处理
                    }
                }
            }
        }
    }

    private boolean copyFile(DocumentFile srcFile, DocumentFile destDir, boolean isModified) {
        InputStream in = null;
        OutputStream out = null;
        try {
            String baseName = srcFile.getName();
            String mimeType = srcFile.getType();

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

                // 如果目标文件夹中已经存在同名文件，则在文件名后加 .1, .2, ...
                if (destDir.findFile(baseName) != null) {
                    // 如果原文件名已经存在于目标文件夹，则从 .1 开始编号
                    do {
                        newName = name + "." + counter + ext;
                        numberedFile = destDir.findFile(newName);
                        counter++;
                    } while (numberedFile != null);

                    baseName = newName;
                } else {
                    // 如果是修改的文件，但目标文件夹里没有同名文件，说明是第一次复制/修改，无需编号
                    // 但由于逻辑只处理修改和新增，为了保证不覆盖，我们仍然进行编号
                    int existingCopies = 0;
                    do {
                        newName = name + "." + existingCopies + ext;
                        numberedFile = destDir.findFile(newName);
                        if (numberedFile != null) {
                            existingCopies++;
                        }
                    } while (numberedFile != null);

                    if (existingCopies > 0) {
                        // 从下一个编号开始
                        counter = existingCopies;
                        do {
                            newName = name + "." + counter + ext;
                            numberedFile = destDir.findFile(newName);
                            counter++;
                        } while (numberedFile != null);
                        baseName = newName;
                    }
                    // 否则 (existingCopies == 0)， baseName 保持原名
                }
            }

            DocumentFile destFile = destDir.createFile(mimeType, baseName);
            if (destFile == null) return false;

            in = getContentResolver().openInputStream(srcFile.getUri());
            out = getContentResolver().openOutputStream(destFile.getUri());

            if (in == null || out == null) return false;

            byte[] buffer = new byte[4096];
            int read;

            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return true;
        } catch (Exception e) {
            // Log.e(TAG, "Copy Error: " + e.getMessage()); // 仅内部 logcat 打印
            return false;
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (Exception ignored) {}
        }
    }

    // **broadcastLog 方法已移除**

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