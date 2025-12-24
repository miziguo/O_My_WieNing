package o.my.gay;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import java.io.IOException;

public class RemoteControlService extends Service {

    private WindowManager windowManager;
    private TextView floatingView;
    private RemoteControlServer server;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        setupFloatingView();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            try {
                server = new RemoteControlServer(this);
                server.start();
                runOnUiThread(() -> updateFloatingView("远程控制已开启\n" + getIpAddress() + ":9999"));
            } catch (IOException e) {
                e.printStackTrace();
                runOnUiThread(() -> updateFloatingView("服务开启失败"));
                stopSelf();
            }
        }).start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (server != null) {
            server.stop();
        }
        if (windowManager != null && floatingView != null) {
            windowManager.removeView(floatingView);
        }
    }

    private void setupFloatingView() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatingView = new TextView(this);
        floatingView.setText("远程服务准备中...");
        floatingView.setTextColor(Color.WHITE);
        floatingView.setBackgroundColor(Color.BLUE);
        floatingView.setPadding(16, 16, 16, 16);

        int layoutParamsType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutParamsType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutParamsType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutParamsType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 200; // Y-offset to avoid overlapping with other views

        windowManager.addView(floatingView, params);
    }

    private void updateFloatingView(String text) {
        if (floatingView != null) {
            floatingView.setText(text);
        }
    }

    private void runOnUiThread(Runnable runnable) {
        if (floatingView != null) {
            floatingView.post(runnable);
        }
    }

    private String getIpAddress() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
        if (ipAddress == 0) {
            return "无法获取IP地址";
        }
        return (ipAddress & 0xFF) + "." + ((ipAddress >> 8) & 0xFF) + "." + ((ipAddress >> 16) & 0xFF) + "." + ((ipAddress >> 24) & 0xFF);
    }
}
