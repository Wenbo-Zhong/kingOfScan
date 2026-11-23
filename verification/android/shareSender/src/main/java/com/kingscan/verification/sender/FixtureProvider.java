package com.kingscan.verification.sender;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** A separate application UID verifies actual content URI permission grants. */
public final class FixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File fixture(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!"retouch-input.jpg".equals(name) && !"ocr-input.jpg".equals(name))
            throw new FileNotFoundException("Unknown verification fixture");
        File file = new File(getContext().getFilesDir(), name);
        if (!file.exists()) {
            try (InputStream in = getContext().getAssets().open(name); FileOutputStream out = new FileOutputStream(file)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
        }
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(fixture(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "image/jpeg"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        try { File file = fixture(uri); cursor.addRow(new Object[] {file.getName(), file.length()}); }
        catch (FileNotFoundException error) { throw new IllegalArgumentException(error); }
        return cursor;
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
}
