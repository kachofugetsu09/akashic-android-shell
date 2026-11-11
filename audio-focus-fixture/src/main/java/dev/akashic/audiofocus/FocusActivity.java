package dev.akashic.audiofocus;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Display;

public final class FocusActivity extends Activity {
    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int displayId = getDisplay().getDisplayId();
        String command = getIntent().getStringExtra("command");
        if (displayId == Display.DEFAULT_DISPLAY) {
            new EventStore(this).add(this, displayId, getDeviceId(), "", "", "refused_main_display", null, "No service started");
            finish();
            return;
        }
        if (!"request".equals(command)) throw new IllegalArgumentException("Activity command must be request");
        Intent service = new Intent(this, FocusService.class)
                .putExtra("command", command).putExtra("display_id", displayId)
                .putExtra("run_id", getIntent().getStringExtra("run_id"))
                .putExtra("gain", getIntent().getStringExtra("gain"));
        startForegroundService(service);
        // Leave the request with its service; this test UI does not keep focus or a main-screen task.
        finish();
    }
}
