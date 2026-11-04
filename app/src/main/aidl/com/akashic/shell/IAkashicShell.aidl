package com.akashic.shell;

import android.view.Surface;

interface IAkashicShell {
    int createDisplay(in Surface surface, int width, int height, int dpi) = 1;
    void releaseDisplay(int displayId) = 2;
    int startApp(int displayId, String packageName) = 3;
    boolean injectTap(int displayId, int x, int y) = 4;
    boolean injectSwipe(int displayId, int x1, int y1, int x2, int y2, int durationMs) = 5;
    boolean injectKey(int displayId, int keyCode) = 6;
    boolean moveTaskToDisplay(String packageName, int targetDisplayId) = 7;
    String topActivity(int displayId) = 8;
    int[] identity() = 9;
    void releaseAll() = 10;
    void destroy() = 16777114;
}
