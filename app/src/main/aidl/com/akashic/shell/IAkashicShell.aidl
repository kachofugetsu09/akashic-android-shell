package com.akashic.shell;

import android.view.Surface;
import android.content.Intent;

interface IAkashicShell {
    const int TASK_TRANSFER_VERSION = 1;
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    int startApp(int displayId, String packageName) = 3;
    boolean injectTap(int displayId, int x, int y) = 4;
    boolean injectSwipe(int displayId, int x1, int y1, int x2, int y2, int durationMs) = 5;
    boolean injectKey(int displayId, int keyCode) = 6;
    // Transaction 7 retired: never move tasks by package name.
    String topActivity(int displayId) = 8;
    int[] identity() = 9;
    void releaseAll() = 10;
    boolean keepAwake(int displayId) = 11;
    int startIntent(int displayId, in Intent intent) = 12;
    int topTaskId(int sourceDisplayId) = 13;
    boolean moveTaskById(int taskId, int expectedSourceDisplayId, int targetDisplayId) = 14;
    void destroy() = 16777114;
}
