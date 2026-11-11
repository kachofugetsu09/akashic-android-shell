package dev.akashic.phoneeval;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

public class StateProvider extends ContentProvider {
    public boolean onCreate() { return true; }
    public Cursor query(Uri u,String[] p,String s,String[] a,String sort) {
        android.content.SharedPreferences state=getContext().getSharedPreferences("state",0);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("text", state.getString("text", ""));
        values.put("submitted", state.getString("submitted", ""));
        values.put("count", state.getInt("count", 0));
        values.put("row", state.getInt("row", -1));
        values.put("paste", state.getString("paste", ""));
        // content query 的文本格式无法可靠表达逗号和换行，提供独立 JSON 列。
        String encoded = Base64.encodeToString(new JSONObject(values).toString()
            .getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        values.put("state_b64", encoded);
        MatrixCursor result = new MatrixCursor(values.keySet().toArray(new String[0]));
        result.addRow(values.values().toArray());
        return result;
    }
    public String getType(Uri u) { return "vnd.android.cursor.item/phoneeval"; }
    public Uri insert(Uri u,ContentValues v) { throw new UnsupportedOperationException(); }
    public int update(Uri u,ContentValues v,String s,String[] a) { throw new UnsupportedOperationException(); }
    public int delete(Uri u,String s,String[] a) { throw new UnsupportedOperationException(); }
}
