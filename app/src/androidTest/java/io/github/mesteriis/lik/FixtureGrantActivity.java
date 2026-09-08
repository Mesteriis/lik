package io.github.mesteriis.lik;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import java.util.ArrayList;

/** Grants access using the test APK's actual provider-owner UID. */
public class FixtureGrantActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        grantAndFinish(getIntent());
    }

    @Override public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        grantAndFinish(intent);
    }

    private void grantAndFinish(Intent intent) {
        ArrayList<Uri> uris = intent.getParcelableArrayListExtra("uris", Uri.class);
        if (uris != null) for (Uri uri : uris) {
            grantUriPermission("io.github.mesteriis.lik", uri,
                intent.getIntExtra("grantFlags", Intent.FLAG_GRANT_READ_URI_PERMISSION));
        }
        finish();
    }
}
