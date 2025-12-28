package o.client.gay;

import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 40, 40, 40);

        Button btnFile = new Button(this);
        btnFile.setText("1. 开启文件管理权限");
        btnFile.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                i.setData(Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        });

        Button btnFloat = new Button(this);
        btnFloat.setText("2. 开启悬浮窗保活权限");
        btnFloat.setOnClickListener(v -> {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        });

        Button btnStart = new Button(this);
        btnStart.setText("3. 启动同步服务");
        btnStart.setOnClickListener(v -> {
            Intent intent = new Intent(this, ClientService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "服务已在后台尝试连接", Toast.LENGTH_SHORT).show();
        });

        root.addView(btnFile);
        root.addView(btnFloat);
        root.addView(btnStart);
        setContentView(root);
    }
}