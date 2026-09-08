package io.github.mesteriis.lik;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.ResultReceiver;

/** Runs under the test APK UID, distinct from the target app/instrumentation process. */
public class ExportReceiverActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri selected = getIntent().getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        Uri other = Uri.parse(getIntent().getStringExtra("other"));
        Bundle result = new Bundle();
        try (java.io.InputStream input = getContentResolver().openInputStream(selected)) {
            result.putBoolean("selectedReadable", input.read() == 1);
        } catch (Exception ignored) { }
        try (java.io.OutputStream output = getContentResolver().openOutputStream(selected)) {
            result.putBoolean("selectedWritable", true);
        } catch (Exception ignored) { }
        try (java.io.InputStream input = getContentResolver().openInputStream(other)) {
            result.putBoolean("otherReadable", true);
        } catch (Exception ignored) { }
        ResultReceiver receiver = getIntent().getParcelableExtra("receiver", ResultReceiver.class);
        receiver.send(0, result);
        finish();
    }
}
