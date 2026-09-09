package io.github.mesteriis.lik;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;

public class SaveCopyPickerActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Button done = new Button(this);
        done.setText("Finish save picker");
        done.setOnClickListener(view -> {
            setResult(RESULT_OK, new Intent().setData(Uri.parse(getIntent().getStringExtra("destination")))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            finish();
        });
        setContentView(done);
    }
}
