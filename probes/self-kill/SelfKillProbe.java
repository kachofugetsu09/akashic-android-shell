package dev.akashic.selfkill;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Process;

/** Same-signature diagnostic; use only with --no-restart and an exact live target PID. */
public final class SelfKillProbe extends Instrumentation {
 private boolean kill;
 private int expectedPid;
 @Override public void onCreate(Bundle arguments) {
  super.onCreate(arguments);
  Bundle result=new Bundle();
  try {
   expectedPid=Integer.parseInt(arguments.getString("expected_pid","-1"));
   if(!getTargetContext().getPackageName().equals("com.akashic.shell") ||
      Process.myPid()!=expectedPid || Process.myUid()!=getTargetContext().getApplicationInfo().uid)
    throw new IllegalStateException("Target package, current PID or UID did not match; no signal sent");
   String mode=arguments.getString("kill","false");
   if(!mode.equals("true")&&!mode.equals("false"))throw new IllegalArgumentException("kill must be true or false");
   kill=mode.equals("true");
   result.putInt("existing_target_pid",Process.myPid());result.putInt("target_uid",Process.myUid());
   result.putBoolean("kill_requested",kill);result.putBoolean("ok",true);
   // AMS clears active instrumentation synchronously. ActivityThread then calls onDestroy.
   finish(Activity.RESULT_OK,result);
  }catch(RuntimeException error){kill=false;result.putString("error",error.toString());result.putBoolean("ok",false);finish(Activity.RESULT_CANCELED,result);}
 }
 @Override public void onDestroy() {
  super.onDestroy();
  if(kill && Process.myPid()==expectedPid)Process.killProcess(expectedPid);
 }
}
