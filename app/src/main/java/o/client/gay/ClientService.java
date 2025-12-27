package o.client.gay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class ClientService extends Service {

    private static final String TAG = "ClientService";
    private static final String CHANNEL_ID = "ClientServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final int HTTP_PORT = 9999;
    private static final long RECONNECT_DELAY_MS = 5000;

    private OkHttpClient httpClient;
    private WebSocket webSocket;
    private DownloadHttpServer downloadServer; // Kept for upload functionality
    private final Gson gson = new Gson();
    private NotificationManager notificationManager;
    private Handler reconnectHandler;
    private boolean isStopping = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();

        reconnectHandler = new Handler(Looper.getMainLooper());

        httpClient = new OkHttpClient.Builder()
                .pingInterval(30, TimeUnit.SECONDS)
                .build();

        // The HTTP server is now ONLY for uploads. Downloads are handled via WebSocket.
        downloadServer = new DownloadHttpServer();
        try {
            downloadServer.start();
        } catch (IOException e) {
            Log.e(TAG, "Error starting HTTP server for uploads", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        isStopping = false;
        startForeground(NOTIFICATION_ID, createNotification("正在初始化..."));
        connectToController();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isStopping = true;
        reconnectHandler.removeCallbacksAndMessages(null);
        if (webSocket != null) webSocket.close(1000, "Service destroyed");
        if (downloadServer != null) downloadServer.stop();
        stopForeground(true);
    }

    private String getDeviceIpAddress() {
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (SocketException ex) {
            Log.e(TAG, "IP address finding error", ex);
        }
        return null;
    }

    private void connectToController() {
        if (isStopping) return;

        SharedPreferences prefs = getSharedPreferences(SettingsActivity.PREF_NAME, Context.MODE_PRIVATE);
        String controllerIp = prefs.getString(SettingsActivity.KEY_SERVER_IP, null);

        if (controllerIp == null || controllerIp.isEmpty()) {
            updateNotification("错误：未设置控制端IP");
            stopSelf();
            return;
        }

        updateNotification("正在连接 " + controllerIp + "...");

        Request.Builder requestBuilder = new Request.Builder()
                .url("ws://" + controllerIp + ":9998")
                .addHeader("Device-Name", Build.MODEL);

        String deviceIp = getDeviceIpAddress();
        if (deviceIp != null) {
            requestBuilder.addHeader("X-Device-IP", deviceIp);
        }

        Request request = requestBuilder.build();

        if (webSocket != null) {
            webSocket.cancel();
        }
        webSocket = httpClient.newWebSocket(request, new ClientWebSocketListener());
    }

    private void scheduleReconnect() {
        if (isStopping) return;
        reconnectHandler.removeCallbacksAndMessages(null);
        reconnectHandler.postDelayed(this::connectToController, RECONNECT_DELAY_MS);
    }

    private final class ClientWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(@NonNull WebSocket webSocket, @NonNull okhttp3.Response response) {
            updateNotification("已连接到控制端");
            reconnectHandler.removeCallbacksAndMessages(null);
        }

        @Override
        public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
            handleCommand(webSocket, text);
        }

        @Override
        public void onClosing(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            webSocket.close(1000, null);
        }

        @Override
        public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
            updateNotification("连接已断开，5秒后重连...");
            scheduleReconnect();
        }

        @Override
        public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, @Nullable okhttp3.Response response) {
            updateNotification("连接失败，5秒后重连...");
            scheduleReconnect();
        }
    }

    private void handleCommand(WebSocket ws, String commandJson) {
        try {
            Command command = gson.fromJson(commandJson, Command.class);
            if (command == null || command.type == null) return;
            switch (command.type) {
                case "listFiles":
                    listFilesAndSend(ws, command.path, command.commandId);
                    break;
                case "deleteFile":
                    deleteFileAndSend(ws, command.path, command.commandId);
                    break;
                case "startZip":
                    startZipAndNotify(ws, command.path, command.deviceName);
                    break;
                case "fetchFile": // New command for WebSocket download
                    streamFileOverWebSocket(ws, command.path);
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing command: " + commandJson, e);
        }
    }
    
    private void streamFileOverWebSocket(final WebSocket ws, final String path) {
        new Thread(() -> {
            File fileToStream = new File(path);
            if (!fileToStream.exists() || !fileToStream.isFile()) {
                sendJsonMessage(ws, new FetchFileErrorMessage(path, "File not found or is a directory."));
                return;
            }

            try (FileInputStream fis = new FileInputStream(fileToStream)) {
                byte[] buffer = new byte[8192]; // 8KB chunks
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    ws.send(ByteString.of(buffer, 0, bytesRead));
                }
                // Signal completion
                sendJsonMessage(ws, new FetchFileCompleteMessage(path));
                Log.d(TAG, "Finished streaming file: " + path);
            } catch (Exception e) {
                Log.e(TAG, "Error streaming file: " + path, e);
                sendJsonMessage(ws, new FetchFileErrorMessage(path, e.getMessage()));
            }
        }).start();
    }

    private void sendJsonMessage(WebSocket ws, Object message) {
        try {
            ws.send(gson.toJson(message));
        } catch(Exception e) {
            Log.e(TAG, "Failed to send JSON message", e);
        }
    }

    private void listFilesAndSend(WebSocket ws, String path, String commandId) {
        try {
            // Android 11 (API 30) and above requires MANAGE_EXTERNAL_STORAGE for broad access.
            // Ensure this permission is granted in SettingsActivity.
            File directory = (path != null && !path.isEmpty()) ? new File(path) : Environment.getExternalStorageDirectory();
            if (!directory.isDirectory()) {
                sendError(ws, commandId, "Invalid path: Not a directory.");
                return;
            }
            File[] files = directory.listFiles();
            if (files == null) {
                sendError(ws, commandId, "Cannot list files. Check permissions.");
                return;
            }
            FileItem[] fileItems = new FileItem[files.length];
            for (int i = 0; i < files.length; i++) {
                fileItems[i] = new FileItem(files[i]);
            }
            ws.send(gson.toJson(new FileListResponse(commandId, fileItems)));
        } catch (Exception e) {
            sendError(ws, commandId, "Error listing files: " + e.getMessage());
        }
    }

    private void deleteFileAndSend(WebSocket ws, String path, String commandId) {
        try {
            File fileToDelete = new File(path);
            if (!fileToDelete.exists()) {
                sendError(ws, commandId, "File not found.");
                return;
            }
            if (deleteRecursive(fileToDelete)) {
                ws.send(gson.toJson(new SimpleResponse(commandId, "success")));
            } else {
                sendError(ws, commandId, "Failed to delete file.");
            }
        } catch (Exception e) {
            sendError(ws, commandId, "Error deleting file: " + e.getMessage());
        }
    }

    private void startZipAndNotify(final WebSocket ws, final String path, final String deviceName) {
        new Thread(() -> {
            try {
                File dirToZip = new File(path);
                if (!dirToZip.exists() || !dirToZip.isDirectory()) {
                    Log.e(TAG, "Directory to zip does not exist or is not a directory: " + path);
                    return;
                }

                final long totalSize = getDirectorySize(dirToZip);
                final long[] progress = {0L};

                String safeDeviceName = (deviceName != null && !deviceName.isEmpty()) ? deviceName.replaceAll("[^a-zA-Z0-9.-]", "_") : "UnknownDevice";
                String timeStamp = new SimpleDateFormat("yyyy.MM.dd_HH.mm.ss", Locale.getDefault()).format(new Date());
                String newFileName = dirToZip.getName() + "_" + safeDeviceName + "_" + timeStamp + ".zip";

                updateNotification("正在打包: " + dirToZip.getName());

                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs();
                }
                File outputZipFile = new File(downloadDir, newFileName);

                try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outputZipFile))) {
                    zipDirectory(dirToZip, dirToZip.getName(), zos, ws, totalSize, progress, path);
                }

                ZipReadyMessage message = new ZipReadyMessage(outputZipFile.getName(), outputZipFile.getAbsolutePath());
                ws.send(gson.toJson(message));
                updateNotification("打包完成: " + outputZipFile.getName());

            } catch (IOException e) {
                Log.e(TAG, "Failed to zip directory and notify server", e);
                updateNotification("打包失败");
            }
        }).start();
    }

    private long getDirectorySize(File dir) {
        long length = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    length += file.length();
                } else if (file.isDirectory()) {
                    length += getDirectorySize(file);
                }
            }
        }
        return length;
    }

    private void zipDirectory(File dir, String baseName, ZipOutputStream zos, WebSocket ws, long totalSize, long[] progressHolder, String originalPath) throws IOException {
        File[] files = dir.listFiles();
        byte[] buffer = new byte[8192];
        if (files == null) return;
        for (File file : files) {
            String entryName = baseName + "/" + file.getName();
            if (file.isDirectory()) {
                zipDirectory(file, entryName, zos, ws, totalSize, progressHolder, originalPath);
            } else {
                try (FileInputStream fis = new FileInputStream(file)) {
                    zos.putNextEntry(new ZipEntry(entryName));
                    int length;
                    while ((length = fis.read(buffer)) > 0) {
                        zos.write(buffer, 0, length);
                    }
                    zos.closeEntry();

                    progressHolder[0] += file.length();
                    ws.send(gson.toJson(new ZipProgressMessage(originalPath, progressHolder[0], totalSize)));
                }
            }
        }
    }

    private boolean deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        return fileOrDirectory.delete();
    }

    private void sendError(WebSocket ws, String commandId, String errorMessage) {
        try {
            ws.send(gson.toJson(new ErrorResponse(commandId, errorMessage)));
        } catch (Exception e) {
            Log.e(TAG, "Failed to send error message.", e);
        }
    }
    
    // This server is now only used for uploads.
    private class DownloadHttpServer extends NanoHTTPD {
        public DownloadHttpServer() { super(HTTP_PORT); }

        @Override
        public Response serve(IHTTPSession session) {
            Method method = session.getMethod();
            String uri = session.getUri();

            try {
                if (Method.POST.equals(method) && "/upload".equals(uri)) {
                    return handleFileUpload(session);
                }
                // The /download endpoint is no longer used.
                return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Endpoint not found.");

            } catch (Exception e) {
                Log.e(TAG, "HTTP Server Error", e);
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "An internal server error occurred: " + e.getMessage());
            }
        }

        private Response handleFileUpload(IHTTPSession session) throws IOException, ResponseException {
            Map<String, String> files = new HashMap<>();
            session.parseBody(files);

            String tempFilePath = files.get("file");
            if (tempFilePath == null) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "No file in upload request.");
            }

            Map<String, List<String>> params = session.getParameters();
            String targetDirPath = params.containsKey("path") ? params.get("path").get(0) : "";
            String originalFilename = params.containsKey("filename") ? params.get("filename").get(0) : "unknown_file";

            File targetDir = targetDirPath.isEmpty() ? Environment.getExternalStorageDirectory() : new File(targetDirPath);
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Could not create target directory.");
            }

            File destination = new File(targetDir, originalFilename);
            File source = new File(tempFilePath);

            try (FileInputStream fis = new FileInputStream(source); FileOutputStream fos = new FileOutputStream(destination)) {
                byte[] buffer = new byte[4096];
                int bytesRead;
                while ((bytesRead = fis.read(buffer)) != -1) {
                    fos.write(buffer, 0, bytesRead);
                }
            }
            source.delete();

            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "File uploaded to " + destination.getAbsolutePath());
        }
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(CHANNEL_ID, "被控端连接服务", NotificationManager.IMPORTANCE_LOW);
            notificationManager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification createNotification(String contentText) {
        Intent notificationIntent = new Intent(this, SettingsActivity.class);
        int pendingIntentFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, pendingIntentFlags);
        return new NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("被控端服务正在运行").setContentText(contentText).setSmallIcon(R.drawable.ic_service_active).setContentIntent(pendingIntent).build();
    }

    private void updateNotification(String contentText) {
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText));
    }

    // --- JSON Message Classes ---
    private static class Command { String type; String path; String commandId; String deviceName;}
    private static class FileItem {
        public String name;
        public String path;
        public boolean isDirectory;
        public long size;

        public FileItem(File file) {
            this.name = file.getName();
            this.path = file.getAbsolutePath();
            this.isDirectory = file.isDirectory();
            this.size = getFileSize(file);
        }

        private long getFileSize(File file) {
            if (file.isFile()) {
                return file.length();
            }
            if (file.isDirectory()) {
                // Return -1 for directories to avoid slow recursive calculation
                return -1;
            }
            return 0;
        }
    }

    private static class FileListResponse { String type = "fileListResult"; String commandId; FileItem[] files; public FileListResponse(String commandId, FileItem[] files) { this.commandId = commandId; this.files = files; } }
    private static class ErrorResponse { String type = "errorResult"; String commandId; String error; public ErrorResponse(String commandId, String error) { this.commandId = commandId; this.error = error; } }
    private static class SimpleResponse { String type = "simpleResult"; String commandId; String status; public SimpleResponse(String commandId, String status) { this.commandId = commandId; this.status = status; } }
    private static class ZipReadyMessage { String type = "zipReady"; String name; String path; public ZipReadyMessage(String name, String path) { this.name = name; this.path = path; } }
    private static class ZipProgressMessage { String type = "zipProgress"; String path; long progress; long total; public ZipProgressMessage(String path, long progress, long total) { this.path = path; this.progress = progress; this.total = total; } }
    
    // New message types for file tunneling
    private static class FetchFileCompleteMessage { String type = "fetchFileComplete"; String path; public FetchFileCompleteMessage(String path) { this.path = path; } }
    private static class FetchFileErrorMessage { String type = "fetchFileError"; String path; String error; public FetchFileErrorMessage(String path, String error) { this.path = path; this.error = error; } }
}