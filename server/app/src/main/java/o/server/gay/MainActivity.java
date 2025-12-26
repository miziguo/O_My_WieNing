package o.server.gay;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements ControllerService.ServiceCallback {

    private static final int UPLOAD_REQUEST_CODE = 1001;

    private ControllerService controllerService;
    private boolean isBound = false;
    private Spinner spinnerDevices;
    private Button btnListRoot;
    private Button btnUploadFile;
    private LinearLayout layoutFileList;
    private TextView tvCurrentPath;

    private String selectedDeviceId;
    private String selectedDeviceIp;
    private String currentPath = "";

    private ArrayAdapter<String> deviceAdapter;
    private final ArrayList<String> deviceIdList = new ArrayList<>();
    private final ArrayList<String> deviceNameList = new ArrayList<>();
    private final Gson gson = new Gson();
    private final Map<String, CommandCallback> pendingCommands = new ConcurrentHashMap<>();
    private final OkHttpClient httpClient = new OkHttpClient();

    private ActivityResultLauncher<Intent> filePickerLauncher;

    private interface CommandCallback {
        void onResult(String json);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // --- View Initialization ---
        spinnerDevices = findViewById(R.id.spinner_devices);
        btnListRoot = findViewById(R.id.btn_list_files); // This button now lists root
        btnUploadFile = new Button(this); // Programmatically create upload button
        btnUploadFile.setText("上传文件到当前目录");
        layoutFileList = findViewById(R.id.layout_file_list);
        tvCurrentPath = new TextView(this); // Programmatically create path TextView
        tvCurrentPath.setPadding(0, 16, 0, 16);

        // --- Adapter Setup ---
        deviceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, deviceNameList);
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerDevices.setAdapter(deviceAdapter);

        // --- Event Listeners ---
        spinnerDevices.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isBound && position < deviceIdList.size()) {
                    selectedDeviceId = deviceIdList.get(position);
                    selectedDeviceIp = controllerService.getDeviceIp(selectedDeviceId);
                    listFilesOnDevice(selectedDeviceId, ""); // List root on device change
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { selectedDeviceId = null; selectedDeviceIp = null; }
        });

        btnListRoot.setOnClickListener(v -> {
            if (selectedDeviceId != null) listFilesOnDevice(selectedDeviceId, "");
            else Toast.makeText(this, "没有选择任何设备", Toast.LENGTH_SHORT).show();
        });

        btnUploadFile.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            filePickerLauncher.launch(intent);
        });

        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Uri uri = result.getData().getData();
                        uploadFile(uri);
                    }
                });

        // --- Service Binding ---
        Intent intent = new Intent(this, ControllerService.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    // --- WebSocket Command Senders ---
    private void listFilesOnDevice(String deviceId, String path) {
        currentPath = path;
        final String commandId = UUID.randomUUID().toString();
        String commandJson = "{\"type\":\"listFiles\",\"path\":\"" + path + "\",\"commandId\":\"" + commandId + "\"}";
        
        pendingCommands.put(commandId, json -> {
            try {
                FileListResponse response = gson.fromJson(json, FileListResponse.class);
                runOnUiThread(() -> updateFileListView(response.files));
            } catch (JsonSyntaxException e) { /* ... */ }
        });

        if (isBound) controllerService.sendCommand(deviceId, commandJson);
    }

    private void deleteFileOnDevice(FileItem item) {
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("确定要删除 " + item.name + " 吗？此操作不可逆。")
                .setPositiveButton("删除", (dialog, which) -> {
                    final String commandId = UUID.randomUUID().toString();
                    String commandJson = "{\"type\":\"deleteFile\",\"path\":\"" + item.path + "\",\"commandId\":\"" + commandId + "\"}";
                    pendingCommands.put(commandId, json -> {
                        runOnUiThread(() -> {
                            Toast.makeText(this, item.name + " 已删除", Toast.LENGTH_SHORT).show();
                            listFilesOnDevice(selectedDeviceId, currentPath); // Refresh list
                        });
                    });
                    if (isBound) controllerService.sendCommand(selectedDeviceId, commandJson);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // --- UI Update ---
    private void updateFileListView(FileItem[] files) {
        layoutFileList.removeAllViews();
        tvCurrentPath.setText("当前路径: " + (currentPath.isEmpty() ? "/" : currentPath));
        layoutFileList.addView(tvCurrentPath);

        // Add ".." (Parent Directory) button if not in root
        if (!currentPath.isEmpty() && !currentPath.equals("/")) {
            Button backButton = new Button(this);
            backButton.setText(".. (返回上一级)");
            backButton.setGravity(Gravity.START);
            backButton.setOnClickListener(v -> {
                String parentPath = new File(currentPath).getParent();
                if (parentPath == null) parentPath = "";
                listFilesOnDevice(selectedDeviceId, parentPath);
            });
            layoutFileList.addView(backButton);
        }

        if (files == null) return;

        for (FileItem item : files) {
            LinearLayout entry = new LinearLayout(this);
            entry.setOrientation(LinearLayout.HORIZONTAL);
            entry.setGravity(Gravity.CENTER_VERTICAL);

            TextView fileName = new TextView(this);
            fileName.setText(item.name);
            fileName.setLayoutParams(new LinearLayout.LayoutParams(0, -2, 1.0f));
            if(item.isDirectory) fileName.setOnClickListener(v -> listFilesOnDevice(selectedDeviceId, item.path));
            
            Button actionButton = new Button(this);
            actionButton.setText(item.isDirectory ? "打包" : "下载");
            actionButton.setOnClickListener(v -> downloadFile(item));
            
            Button deleteButton = new Button(this);
            deleteButton.setText("删除");
            deleteButton.setOnClickListener(v -> deleteFileOnDevice(item));
            
            entry.addView(fileName);
            entry.addView(actionButton);
            entry.addView(deleteButton);
            layoutFileList.addView(entry);
        }
        
        layoutFileList.addView(btnUploadFile); // Add upload button at the end
    }

    // --- HTTP Actions (Download/Upload) ---
    private void downloadFile(FileItem item) {
        if (selectedDeviceIp == null) { /* ... */ return; }
        try {
            String encodedPath = URLEncoder.encode(item.path, "UTF-8");
            String endpoint = item.isDirectory ? "/zip" : "/download";
            String url = "http://" + selectedDeviceIp + ":9999" + endpoint + "?path=" + encodedPath;
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) { /* ... */ }
    }

    private void uploadFile(Uri uri) {
        if (selectedDeviceIp == null) { /* ... */ return; }
        
        String filename = getFileName(uri);
        if (filename == null) filename = "upload-" + System.currentTimeMillis();
        final String finalFilename = filename;
        
        new Thread(() -> {
            try {
                InputStream inputStream = getContentResolver().openInputStream(uri);
                RequestBody requestBody = RequestBody.create(inputStream.readAllBytes());
                
                String url = "http://" + selectedDeviceIp + ":9999/upload?path=" + URLEncoder.encode(currentPath, "UTF-8") + "&filename=" + URLEncoder.encode(finalFilename, "UTF-8");

                Request request = new Request.Builder().url(url).post(requestBody).build();
                try (Response response = httpClient.newCall(request).execute()) {
                    runOnUiThread(() -> {
                        if (response.isSuccessful()) {
                            Toast.makeText(this, "上传成功!", Toast.LENGTH_SHORT).show();
                            listFilesOnDevice(selectedDeviceId, currentPath);
                        } else {
                            Toast.makeText(this, "上传失败: " + response.message(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "上传错误: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }
    
    private String getFileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex != -1) return cursor.getString(nameIndex);
            }
        }
        return null;
    }
    
    // --- Service Connection & Callbacks ---
    private final ServiceConnection serviceConnection = new ServiceConnection() { /* ... Same as before ... */ 
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            ControllerService.LocalBinder binder = (ControllerService.LocalBinder) service;
            controllerService = binder.getService();
            controllerService.setCallback(MainActivity.this);
            isBound = true;
            updateDeviceSpinner();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
        }
    };
    private void updateDeviceSpinner() { /* ... Same as before ... */ 
        if (!isBound) return;
        runOnUiThread(() -> {
            deviceIdList.clear();
            deviceNameList.clear();
            Map<String, String> clients = controllerService.getConnectedClients();
            if (clients.isEmpty()) {
                deviceNameList.add("没有在线设备");
            } else {
                for (Map.Entry<String, String> entry : clients.entrySet()) {
                    deviceIdList.add(entry.getKey());
                    deviceNameList.add(entry.getValue());
                }
            }
            deviceAdapter.notifyDataSetChanged();
            if (!deviceIdList.isEmpty()){
                int selectedPos = spinnerDevices.getSelectedItemPosition();
                if(selectedPos == AdapterView.INVALID_POSITION) selectedPos = 0;
                selectedDeviceId = deviceIdList.get(selectedPos);
                selectedDeviceIp = controllerService.getDeviceIp(selectedDeviceId);
            }
        });
    }
    @Override public void onDeviceConnected(String deviceId, String deviceName) { updateDeviceSpinner(); }
    @Override public void onDeviceDisconnected(String deviceId) { updateDeviceSpinner(); }
    @Override public void onMessageReceived(String deviceId, String message) { /* ... Same as before ... */ 
        try {
            CommandResponse baseResponse = gson.fromJson(message, CommandResponse.class);
            if (baseResponse != null && baseResponse.commandId != null) {
                CommandCallback callback = pendingCommands.remove(baseResponse.commandId);
                if (callback != null) {
                    runOnUiThread(() -> callback.onResult(message));
                }
            }
        } catch (JsonSyntaxException e) { /* ... */ }
    }
    private static class CommandResponse { String commandId; }
    private static class FileItem { String name; String path; boolean isDirectory; long size; }
    private static class FileListResponse { FileItem[] files; }
    @Override protected void onDestroy() { /* ... Same as before ... */ 
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}
