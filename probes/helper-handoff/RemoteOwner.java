package dev.akashic.hubprobe;
import android.app.Service;
import android.content.Intent;
import android.os.*;
public final class RemoteOwner extends Service {
 public IBinder onBind(Intent i) { return new Binder() {
  private int calls;
  protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
   if(code==1) {
    calls++;
    if(data.readInt()!=0){android.os.Process.killProcess(android.os.Process.myPid());return true;}
    Bundle echo=new Bundle();echo.putInt("echo_calls",calls);reply.writeNoException();reply.writeBundle(echo);return true;
   }
   if(code==2) { android.os.Process.killProcess(android.os.Process.myPid()); return true; }
   return false;
  }
 }; }
}
