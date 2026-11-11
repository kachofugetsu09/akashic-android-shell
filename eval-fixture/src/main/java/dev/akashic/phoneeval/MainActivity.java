package dev.akashic.phoneeval;

import android.app.Activity;
import android.os.Bundle;
import android.content.SharedPreferences;
import android.widget.*;
import android.text.*;

public class MainActivity extends Activity {
    /** 建立真实输入、只接受粘贴的控件、非幂等提交和可滚动列表。 */
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences prefs = getSharedPreferences("state", 0);
        LinearLayout column = new LinearLayout(this); column.setOrientation(1);
        // 1. 普通输入与粘贴输入分别持久保存，不能只靠无障碍返回值评分。
        EditText field = new EditText(this); field.setHint("Enter target text"); field.setSingleLine(true);
        field.setText(prefs.getString("text", "")); column.addView(field);
        EditText paste = new EditText(this) {
            @Override public boolean performAccessibilityAction(int action, Bundle args) {
                if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT) return false;
                return super.performAccessibilityAction(action,args);
            }
        };
        paste.setHint("Paste-only input"); paste.setSingleLine(true); column.addView(paste);
        paste.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int a,int c,int f) {}
            public void onTextChanged(CharSequence s,int a,int b,int c) { prefs.edit().putString("paste",s.toString()).commit(); }
            public void afterTextChanged(Editable e) {}
        });
        TextView result = new TextView(this); result.setText("Not submitted"); column.addView(result);
        Button submit = new Button(this); submit.setText("Submit text"); column.addView(submit);
        // 2. 每次真实点击增加计数，重复发送与漏发都能被独立读出。
        submit.setOnClickListener(v -> {
            int count = prefs.getInt("count",0) + 1;
            String text = field.getText().toString();
            prefs.edit().putString("submitted",text).putInt("count",count).commit();
            result.setText("Saved: " + text + " (" + count + ")");
        });
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s,int a,int c,int f) {}
            public void onTextChanged(CharSequence s,int a,int b,int c) { prefs.edit().putString("text",s.toString()).commit(); }
            public void afterTextChanged(Editable e) {}
        });
        // 3. 保留屏外目标，供 agent 的滚动定位任务使用。
        ScrollView scroll = new ScrollView(this); LinearLayout rows = new LinearLayout(this); rows.setOrientation(1);
        for (int i=0;i<40;i++) {
            Button row = new Button(this); final int number=i; row.setText("Row " + i);
            row.setOnClickListener(v -> { prefs.edit().putInt("row",number).commit(); row.setText("Selected row " + number); });
            rows.addView(row, new LinearLayout.LayoutParams(-1,150));
        }
        scroll.addView(rows); column.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(column);
    }
}
