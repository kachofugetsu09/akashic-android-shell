package com.akashic.shell;

oneway interface IAkashicEventSink {
    void onDeviceEvent(String eventJson);
}
