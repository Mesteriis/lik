package io.github.mesteriis.lik;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** A separate-UID provider which returns an already-existing document from createDocument. */
public class ExistingDocumentProvider extends DocumentsProvider {
    private File directory;
    private static final String[] COLUMNS = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE };
    private File file(String id) throws FileNotFoundException {
        if (!id.matches("existing-(source|write|security)")) throw new FileNotFoundException(id);
        return new File(directory, id);
    }
    @Override public boolean onCreate() {
        directory = new File(getContext().getFilesDir(), "existing-documents");
        directory.mkdirs();
        File initialized = new File(directory, "initialized");
        if (!initialized.exists()) {
            try {
                for (String mode : new String[]{"source", "write", "security"}) {
                    try (FileOutputStream out = new FileOutputStream(file("existing-" + mode))) {
                        out.write(("PREEXISTING SENTINEL " + mode).getBytes(StandardCharsets.UTF_8));
                    }
                }
                initialized.createNewFile();
            } catch (IOException e) { throw new IllegalStateException(e); }
        }
        return true;
    }
    @Override public String createDocument(String parentId, String mime, String name) throws FileNotFoundException {
        if (!"root".equals(parentId) || !file(name).isFile()) throw new FileNotFoundException(name);
        return name; // Existing bytes were written before this create call.
    }
    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(COLUMNS);
        if ("root".equals(id)) cursor.addRow(new Object[]{id, id, Document.MIME_TYPE_DIR, Document.FLAG_DIR_SUPPORTS_CREATE, 0});
        else if (file(id).isFile()) cursor.addRow(new Object[]{id, id, "image/png", Document.FLAG_SUPPORTS_WRITE | Document.FLAG_SUPPORTS_DELETE, file(id).length()});
        return cursor;
    }
    @Override public Cursor queryRoots(String[] projection) { return new MatrixCursor(new String[]{"root_id", "document_id"}); }
    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String order) { return new MatrixCursor(COLUMNS); }
    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (mode.contains("w")) {
            if (id.endsWith("security")) throw new SecurityException("Destination access revoked");
            throw new FileNotFoundException("Destination has no space");
        }
        return ParcelFileDescriptor.open(file(id), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public void deleteDocument(String id) throws FileNotFoundException {
        if (!file(id).delete()) throw new FileNotFoundException(id);
    }
}
