package com.datalogic.largeshareapp.storage;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Where shared files come from and where received files go. */
public final class StorageLocations {
    private static final String TAG = "StorageLocations";

    private StorageLocations() {}

    /**
     * Received files go to Download/LargeShare when the app has all-files access, otherwise to the
     * app's own external files directory (no permission needed).
     */
    public static File defaultReceiveDir(Context context) {
        if (Environment.isExternalStorageManager()) {
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "LargeShare");
        }
        File external = context.getExternalFilesDir("received");
        return external != null ? external : new File(context.getFilesDir(), "received");
    }

    /**
     * Resolves a document picked by the user to a real file path so a multi-GB payload can be
     * shared in place, without copying. Returns null if it cannot be resolved to a readable file.
     */
    public static File resolveToFile(Context context, Uri uri) {
        if (uri == null) {
            return null;
        }
        File file = null;
        try {
            if ("file".equals(uri.getScheme())) {
                file = new File(uri.getPath());
            } else if (DocumentsContract.isDocumentUri(context, uri)) {
                file = resolveDocument(context, uri);
            }
            if (file == null) {
                file = fromDataColumn(context, uri);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot resolve " + uri, e);
        }
        return file != null && file.isFile() && file.canRead() ? file : null;
    }

    private static File resolveDocument(Context context, Uri uri) {
        String authority = uri.getAuthority();
        String docId = DocumentsContract.getDocumentId(uri);
        if ("com.android.externalstorage.documents".equals(authority)) {
            String[] parts = docId.split(":", 2);
            if (parts.length == 2) {
                File root = "primary".equalsIgnoreCase(parts[0])
                        ? Environment.getExternalStorageDirectory()
                        : new File("/storage/" + parts[0]);
                return new File(root, parts[1]);
            }
        } else if ("com.android.providers.downloads.documents".equals(authority)) {
            if (docId.startsWith("raw:")) {
                return new File(docId.substring(4));
            }
        }
        return fromDataColumn(context, uri);
    }

    @SuppressWarnings("deprecation")
    private static File fromDataColumn(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{"_data"}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String path = cursor.getString(0);
                if (path != null) {
                    return new File(path);
                }
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "No _data column for " + uri);
        }
        return null;
    }

    public static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (RuntimeException e) {
            Log.d(TAG, "No display name for " + uri);
        }
        return "shared.bin";
    }

    /** Last resort for providers that expose no path: copy the stream into app storage. */
    public static File copyToAppStorage(Context context, Uri uri) throws IOException {
        File dir = context.getExternalFilesDir("shared");
        if (dir == null) {
            dir = new File(context.getFilesDir(), "shared");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        File out = new File(dir, displayName(context, uri));
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) {
                throw new IOException("Cannot open " + uri);
            }
            byte[] buffer = new byte[256 * 1024];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                os.write(buffer, 0, n);
            }
        }
        return out;
    }
}
