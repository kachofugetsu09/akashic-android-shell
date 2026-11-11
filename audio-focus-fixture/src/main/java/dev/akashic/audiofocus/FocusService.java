package dev.akashic.audiofocus;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import java.util.UUID;

public final class FocusService extends Service {
    private EventStore events;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private String requestId = "", runId = "";
    private int displayId = -1, deviceId = -1;
    private boolean foreground;
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() { super.onCreate(); events = new EventStore(this); }

    /** Execute only explicit test commands; a recreated service never requests focus. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { stopSelf(); return START_NOT_STICKY; }
        String command = intent.getStringExtra("command");
        if ("abandon".equals(command) || "stop".equals(command)) {
            abandon();
            if (foreground) stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!"request".equals(command)) throw new IllegalArgumentException("Unknown focus command");
        int requestedDisplay = intent.getIntExtra("display_id", -1);
        Display display = getSystemService(DisplayManager.class).getDisplay(requestedDisplay);
        if (display == null || requestedDisplay <= Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("An existing non-default display is required");
        }
        String gain = intent.getStringExtra("gain");
        int gainCode;
        if ("gain".equals(gain)) gainCode = AudioManager.AUDIOFOCUS_GAIN;
        else if ("transient".equals(gain)) gainCode = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT;
        else throw new IllegalArgumentException("gain must be gain or transient");
        String run = intent.getStringExtra("run_id");
        if (run == null || run.isBlank()) throw new IllegalArgumentException("run_id is required");
        abandon();
        Context displayContext = createDisplayContext(display);
        audio = displayContext.getSystemService(AudioManager.class);
        displayId = display.getDisplayId();
        deviceId = displayContext.getDeviceId();
        runId = run;
        requestId = UUID.randomUUID().toString();
        startSilentHost();
        final String request = requestId, correlation = runId;
        final int originDisplay = displayId, originDevice = deviceId;
        AudioManager.OnAudioFocusChangeListener listener = new AudioManager.OnAudioFocusChangeListener() {
            @Override public void onAudioFocusChange(int change) {
                events.add(FocusService.this, originDisplay, originDevice, correlation, request,
                        "callback", change, "No player or automatic re-request");
            }
            @Override public String toString() { return getPackageName() + ":" + request; }
        };
        focus = new AudioFocusRequest.Builder(gainCode)
                .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(listener, new Handler(Looper.getMainLooper()))
                .build();
        events.add(this, displayId, deviceId, runId, requestId, "request_begin", gainCode, "listener=" + listener);
        try {
            int result = audio.requestAudioFocus(focus);
            events.add(this, displayId, deviceId, runId, requestId, "request_result", result, gain);
        } catch (SecurityException error) {
            events.add(this, displayId, deviceId, runId, requestId, "request_exception", null, error.toString());
            stopSelf();
            throw error;
        }
        return START_NOT_STICKY;
    }

    /** Create a silent foreground host so target-36 focus requests are eligible. */
    private void startSilentHost() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel("focus-test", "Silent focus test", NotificationManager.IMPORTANCE_MIN);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
        NotificationChannel actual = manager.getNotificationChannel("focus-test");
        if (actual.getSound() != null || actual.shouldVibrate() || actual.getImportance() >= NotificationManager.IMPORTANCE_DEFAULT) {
            throw new IllegalStateException("Test channel must stay silent and below DEFAULT importance");
        }
        Notification notification = new Notification.Builder(this, "focus-test")
                .setSmallIcon(android.R.drawable.ic_media_pause).setContentTitle("Silent audio focus test")
                .setContentText("No audio is played").setOngoing(true).setOnlyAlertOnce(true)
                .setShowWhen(false).setSound(null).setVibrate(null).setDefaults(0).build();
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        foreground = true;
        events.add(this, displayId, deviceId, runId, requestId, "foreground_started", actual.getImportance(), "Silent channel");
    }

    /** Abandon the exact request object and keep the real return value. */
    private void abandon() {
        if (focus == null) return;
        int result = audio.abandonAudioFocusRequest(focus);
        events.add(this, displayId, deviceId, runId, requestId, "abandon_result", result, "Explicit release");
        focus = null;
    }
    @Override public void onDestroy() {
        abandon();
        events.add(this, displayId, deviceId, runId, requestId, "service_destroyed", null, "No automatic restart");
        events.close();
        super.onDestroy();
    }
}
