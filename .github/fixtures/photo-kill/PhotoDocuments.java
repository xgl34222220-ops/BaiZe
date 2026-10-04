package test.baize.photo.fixture;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

/** CI-only external provider: actual document grants and a deterministic partial write. */
public final class PhotoDocuments extends DocumentsProvider {
    private File root;
    private int created;
    private String interrupted;
    private static final String[] DOC = {"document_id", "_display_name", "mime_type", "flags", "_size", "last_modified"};
    @Override public boolean onCreate() {
        if (!Build.FINGERPRINT.contains("generic") && !Build.MODEL.toLowerCase().contains("sdk")) return false;
        root = new File(getContext().getFilesDir(), "fixture"); root.mkdirs(); return true;
    }
    private File file(String id) throws java.io.FileNotFoundException {
        if (root == null || (!id.equals("root") && !id.matches("(?:0[12]-source|export-[0-9a-f-]+)\\.jpg")))
            throw new java.io.FileNotFoundException("outside owned fixture");
        return id.equals("root") ? root : new File(root, id);
    }
    @Override public Cursor queryRoots(String[] projection) {
        String[] columns = projection != null ? projection : new String[]{"root_id", "document_id", "title", "flags", "mime_types", "available_bytes"};
        MatrixCursor cursor = new MatrixCursor(columns);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) row.add(column, column.equals("root_id") ? "owned" : column.equals("document_id") ? "root" :
            column.equals("title") ? "BaiZe CI Photos" : column.equals("flags") ? DocumentsContract.Root.FLAG_SUPPORTS_CREATE | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD :
            column.equals("mime_types") ? "image/jpeg" : column.equals("available_bytes") ? 104857600L : null);
        return cursor;
    }
    private void add(MatrixCursor cursor, String id) throws java.io.FileNotFoundException {
        File value = file(id); MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) row.add(column, column.equals("document_id") ? id :
            column.equals("_display_name") ? id.equals("root") ? "BaiZe CI Photos" : id :
            column.equals("mime_type") ? id.equals("root") ? DocumentsContract.Document.MIME_TYPE_DIR : "image/jpeg" :
            column.equals("flags") ? id.equals("root") ? DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE :
                id.startsWith("export-") ? DocumentsContract.Document.FLAG_SUPPORTS_WRITE : 0 :
            column.equals("_size") ? value.length() : column.equals("last_modified") ? value.lastModified() : null);
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws java.io.FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOC); add(cursor, id); return cursor;
    }
    @Override public Cursor queryChildDocuments(String id, String[] projection, String sortOrder) throws java.io.FileNotFoundException {
        if (!id.equals("root")) throw new java.io.FileNotFoundException();
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOC);
        String[] names = root.list(); if (names != null) { Arrays.sort(names); for (String name : names) if (name.endsWith(".jpg")) add(cursor, name); }
        return cursor;
    }
    @Override public boolean isChildDocument(String parent, String child) {
        try { return parent.equals("root") && !child.equals("root") && file(child).isFile(); }
        catch (java.io.FileNotFoundException e) { return false; }
    }
    @Override public synchronized String createDocument(String parent, String mime, String name) throws java.io.FileNotFoundException {
        if (!parent.equals("root") || !mime.equals("image/jpeg")) throw new java.io.FileNotFoundException();
        String id = "export-" + UUID.randomUUID() + ".jpg";
        try { if (!file(id).createNewFile()) throw new IOException(); } catch (IOException e) { throw new java.io.FileNotFoundException(e.toString()); }
        if (++created == 2 && new File(root, "armed").isFile()) interrupted = id;
        return id;
    }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws java.io.FileNotFoundException {
        File target = file(id);
        if (mode.contains("w")) {
            if (!id.startsWith("export-")) throw new java.io.FileNotFoundException("original is read-only");
            if (id.equals(interrupted)) {
                try {
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                    new Thread(() -> {
                        try (FileInputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]); FileOutputStream output = new FileOutputStream(target)) {
                            byte[] bytes = new byte[64]; int count = input.read(bytes);
                            if (count <= 0) throw new IOException("no bytes written");
                            output.write(bytes, 0, count); output.flush(); output.getFD().sync();
                            try (FileOutputStream marker = new FileOutputStream(new File(root, "partial-marker"))) { marker.write(id.getBytes("UTF-8")); marker.getFD().sync(); }
                            long deadline = System.currentTimeMillis() + 120000;
                            while (!new File(root, "abort").isFile() && System.currentTimeMillis() < deadline) Thread.sleep(20);
                            // Deliberately retain the partial file. No provider deletes or rewrites an original.
                        } catch (Exception e) { android.util.Log.e("BaiZePhotoFixture", "controlled pipe ended", e); }
                    }, "owned-partial-copy").start();
                    return pipe[1];
                } catch (IOException e) { throw new java.io.FileNotFoundException(e.toString()); }
            }
        } else if (id.equals(interrupted)) {
            long deadline = System.currentTimeMillis() + 120000;
            while (!new File(root, "abort").isFile() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(20); } catch (InterruptedException e) { throw new java.io.FileNotFoundException("interrupted"); }
            }
        }
        return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode));
    }
}
