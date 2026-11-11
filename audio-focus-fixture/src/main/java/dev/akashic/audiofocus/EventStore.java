package dev.akashic.audiofocus;

import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Process;
import android.os.SystemClock;

final class EventStore extends SQLiteOpenHelper {
    EventStore(Context context) { super(context, "focus-events.db", null, 1); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY, wall_ms INTEGER, elapsed_ms INTEGER, " +
                "package_name TEXT, uid INTEGER, pid INTEGER, display_id INTEGER, device_id INTEGER, " +
                "run_id TEXT, request_id TEXT, event TEXT, value INTEGER, detail TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        throw new IllegalStateException("Unexpected fixture database version");
    }

    /** Store each completed API call or callback as a separate durable event. */
    void add(Context context, int displayId, int deviceId, String run, String request,
             String event, Integer value, String detail) {
        ContentValues row = new ContentValues();
        row.put("wall_ms", System.currentTimeMillis());
        row.put("elapsed_ms", SystemClock.elapsedRealtime());
        row.put("package_name", context.getPackageName());
        row.put("uid", Process.myUid());
        row.put("pid", Process.myPid());
        row.put("display_id", displayId);
        row.put("device_id", deviceId);
        row.put("run_id", run);
        row.put("request_id", request);
        row.put("event", event);
        row.put("value", value);
        row.put("detail", detail);
        getWritableDatabase().insertOrThrow("events", null, row);
    }
}
