package o.my.gay;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import fi.iki.elonen.NanoHTTPD;

public class RemoteControlServer extends NanoHTTPD {

    private final Context context;

    public RemoteControlServer(Context context) {
        super(9999);
        this.context = context;
    }

    private String getPathParameter(Map<String, List<String>> params) {
        if (params.containsKey("path")) {
            List<String> values = params.get("path");
            if (values != null && !values.isEmpty()) {
                try {
                    return URLDecoder.decode(values.get(0), StandardCharsets.UTF_8.name());
                } catch (UnsupportedEncodingException e) { // Should not happen with UTF-8
                    return values.get(0);
                }
            }
        }
        return null;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, List<String>> params = session.getParameters();

        switch (uri) {
            case "/start_test":
                context.startService(new Intent(context, StorageTestService.class));
                return newFixedLengthResponse("Storage test started.");
            case "/stop_test":
                context.stopService(new Intent(context, StorageTestService.class));
                return newFixedLengthResponse("Storage test stopped.");
            case "/files": {
                String path = getPathParameter(params);
                File directory = (path != null && !path.isEmpty()) ? new File(path) : Environment.getExternalStorageDirectory();
                if (!directory.isDirectory()) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Not a directory.");
                }
                String fileList = getFileList(directory);
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", fileList);
            }
            case "/upload": {
                String path = getPathParameter(params);
                if (path != null) {
                    File file = new File(path);
                    if (file.exists() && file.isFile()) {
                        try {
                            FileInputStream fis = new FileInputStream(file);
                            return newChunkedResponse(Response.Status.OK, "application/octet-stream", fis);
                        } catch (FileNotFoundException e) {
                            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found.");
                        }
                    }
                }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing or invalid path parameter.");
            }
            case "/delete": {
                String path = getPathParameter(params);
                if (path != null) {
                    File file = new File(path);
                    if (file.exists()) {
                        if (deleteRecursive(file)) {
                            return newFixedLengthResponse("File or directory deleted.");
                        } else {
                            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Could not delete file or directory.");
                        }
                    }
                }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing path parameter.");
            }
            case "/zip": {
                String path = getPathParameter(params);
                if (path == null || path.isEmpty()) {
                    return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing path parameter.");
                }
                File dirToZip = new File(path);
                if (!dirToZip.exists() || !dirToZip.isDirectory()) {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Directory not found.");
                }
                return serveZip(dirToZip);
            }
            default:
                return serveHomepage();
        }
    }

    private Response serveZip(File directory) {
        try {
            final PipedInputStream in = new PipedInputStream();
            final PipedOutputStream out = new PipedOutputStream(in);

            new Thread(() -> {
                try (ZipOutputStream zos = new ZipOutputStream(out)) {
                    zipDirectory(directory, directory.getName(), zos);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }).start();

            Response res = newChunkedResponse(Response.Status.OK, "application/zip", in);
            res.addHeader("Content-Disposition", "attachment; filename=\"" + directory.getName() + ".zip\"");
            return res;
        } catch (IOException e) {
            e.printStackTrace();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Failed to create ZIP stream.");
        }
    }


    private void zipDirectory(File dir, String baseName, ZipOutputStream zos) throws IOException {
        File[] files = dir.listFiles();
        byte[] buffer = new byte[8192];
        if (files != null) {
            for (File file : files) {
                // Create a relative path for the zip entry
                String entryName = baseName + "/" + file.getName();
                if (file.isDirectory()) {
                    zipDirectory(file, entryName, zos);
                } else {
                    try (FileInputStream fis = new FileInputStream(file)) {
                        zos.putNextEntry(new ZipEntry(entryName));
                        int length;
                        while ((length = fis.read(buffer)) > 0) {
                            zos.write(buffer, 0, length);
                        }
                        zos.closeEntry();
                    }
                }
            }
        }
    }

    private Response serveHomepage() {
        String rootPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        String msg = "<html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                + "<style>body{font-family: sans-serif; padding: 1em;} a{display: block; padding: 0.5em 0;}</style></head>"
                + "<body><h1>Remote Control</h1>"
                + "<p><a href=\"/start_test\">Start Storage Test</a></p>"
                + "<p><a href=\"/stop_test\">Stop Storage Test</a></p>"
                + "<p><a href=\"/files?path=" + rootPath + "\">List Files (Root)</a></p>"
                + "</body></html>";
        return newFixedLengthResponse(Response.Status.OK, "text/html", msg);
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

    private String getFileList(File dir) {
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\"><style>body{font-family: sans-serif;} ul{list-style: none; padding-left: 0;} li {display: flex; align-items: center; margin-bottom: 0.5em; word-break: break-all;} .actions { margin-left: auto; display: flex; white-space: nowrap; } .actions a {margin-left: 1em;}</style></head><body>");
        sb.append("<h1>").append(dir.getAbsolutePath()).append("</h1>");

        if (dir.getParentFile() != null) {
            sb.append("<ul><li><a href=\"/files?path=").append(dir.getParent()).append("\">.. (Parent Directory)</a></li></ul>");
        }

        sb.append("<ul>");
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                sb.append("<li><span>");
                if (file.isDirectory()) {
                    sb.append("<a href=\"/files?path=").append(file.getAbsolutePath()).append("\">").append(file.getName()).append("</a>");
                } else {
                    sb.append(file.getName());
                }
                sb.append("</span><span class=\"actions\">");
                if (file.isDirectory()) {
                    sb.append("<a href=\"/zip?path=").append(file.getAbsolutePath()).append("\">Download as ZIP</a>");
                }
                if (!file.isDirectory()) {
                    sb.append("<a href=\"/upload?path=").append(file.getAbsolutePath()).append("\">Download</a>");
                }
                sb.append("<a href=\"/delete?path=").append(file.getAbsolutePath()).append("\" onclick=\"return confirm('Are you sure you want to delete this?');\">Delete</a>");
                sb.append("</span></li>");
            }
        }
        sb.append("</ul></body></html>");
        return sb.toString();
    }
}
