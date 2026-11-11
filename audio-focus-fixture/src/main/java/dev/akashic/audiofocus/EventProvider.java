package dev.akashic.audiofocus;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;

public final class EventProvider extends ContentProvider {
    private EventStore store;
    @Override public boolean onCreate() { store = new EventStore(getContext()); return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        if (!uri.getPath().equals("/events")) throw new IllegalArgumentException("Expected /events");
        return store.getReadableDatabase().query("events", null, null, null, null, null, "id ASC");
    }
    @Override public String getType(Uri uri) { return "vnd.android.cursor.dir/focus-event"; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
