package io.github.mesteriis.lik;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;

/** Standalone test-APK process: no dependency on the target APK's Kotlin runtime. */
public class ImportFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "image/png"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if ("missing".equals(uri.getLastPathSegment())) throw new FileNotFoundException();
        File file = new File(getContext().getCacheDir(), "fixture-" + uri.getLastPathSegment() + ".png");
        if ("invalid".equals(uri.getLastPathSegment())) {
            try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[] {1, 2, 3}); }
            catch (IOException exception) { throw new FileNotFoundException(); }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        }
        if ("slow".equals(uri.getLastPathSegment())) {
            try { Thread.sleep(1000); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new FileNotFoundException(); }
        }
        Bitmap bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor("blue".equals(uri.getLastPathSegment()) ? Color.BLUE : Color.GREEN);
        try (FileOutputStream output = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (IOException exception) { throw new FileNotFoundException(); }
        finally { bitmap.recycle(); }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
