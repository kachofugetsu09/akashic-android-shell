package dev.akashic.hubprobe;
import android.app.*;
import android.os.*;
import android.content.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

public final class HubProbe extends Instrumentation {
 private final CountDownLatch oldDeathDone=new CountDownLatch(1); private Object hub; private Method accept, getShell; private Context context;
 @Override public void onCreate(Bundle b){super.onCreate(b);start();}
 @Override public void onStart(){Bundle out=new Bundle();
  try {
   context=getTargetContext();if(!context.getPackageName().equals("com.akashic.shell.debug"))throw new AssertionError("wrong target");
   Class<?> c=Class.forName("com.akashic.shell.ShellHelperHub",true,context.getClassLoader());hub=c.getField("INSTANCE").get(null);accept=c.getMethod("acceptBinder",IBinder.class);getShell=c.getMethod("getShell");
   // 1. A real remote Binder owns the Hub; a second Binder receives no token.
   Remote a=new Remote();a.bind();Bundle first=attach(a.binder);check(first.getBoolean("accepted")&&first.getBinder("token")!=null,"first owner");
   Object firstOwner=getShell.invoke(hub);Bundle repeated=attach(a.binder);check(repeated.getBoolean("accepted")&&repeated.getBinder("token")==first.getBinder("token")&&getShell.invoke(hub)==firstOwner,"unknown reply retry kept same owner and token");out.putBoolean("same_binder_reply_retry",true);
   Bundle duplicate=attach(new Binder());check(!duplicate.getBoolean("accepted")&&duplicate.getBinder("token")==null,"duplicate rejection");out.putBoolean("live_owner_rejected_duplicate",true);
   a.kill();waitEmpty();Bundle dead=attach(a.binder);check(!dead.getBoolean("accepted")&&dead.getBinder("token")==null&&getShell.invoke(hub)==null,"dead owner rejected");a.unbind();out.putBoolean("dead_binder_rejected",true);
   // 2. Kill a remote owner after real linkToDeath but before its return to Hub.
   Remote b=new Remote();b.bind();CountDownLatch linked=new CountDownLatch(1),release=new CountDownLatch(1);IBinder gate=wrapper(b.binder,linked,release,null,null);Bundle[] result=new Bundle[1];Throwable[] error=new Throwable[1];Thread t=new Thread(()->{try{result[0]=attach(gate);}catch(Throwable e){error[0]=e;}});t.start();check(linked.await(5,TimeUnit.SECONDS),"link gate");b.kill();waitDead(b.binder);release.countDown();t.join(5000);check(!t.isAlive()&&error[0]==null&&result[0]!=null&&!result[0].getBoolean("accepted")&&getShell.invoke(hub)==null,"link death race");b.unbind();out.putBoolean("link_death_race_rejected",true);
   // 3. An old delayed death must not clear a newly accepted real owner.
   Remote old=new Remote();old.bind();CountDownLatch died=new CountDownLatch(1),deathRelease=new CountDownLatch(1);IBinder delayed=wrapper(old.binder,null,null,died,deathRelease);check(attach(delayed).getBoolean("accepted"),"delayed owner accepted");old.kill();check(died.await(5,TimeUnit.SECONDS),"death gate");waitDead(old.binder);old.unbind();Remote next=new Remote();next.bind();check(attach(next.binder).getBoolean("accepted"),"replacement accepted");Object current=getShell.invoke(hub);deathRelease.countDown();check(oldDeathDone.await(5,TimeUnit.SECONDS),"old death completed");check(((IInterface)current).asBinder()==next.binder,"replacement identity");check(getShell.invoke(hub)==current,"old death retained new owner");out.putBoolean("old_death_kept_new_owner",true);next.kill();waitEmpty();next.unbind();checkProviderRetry();out.putBoolean("same_provider_retry_and_death",true);checkHelperOldDeath();out.putBoolean("helper_old_app_death_kept_new_token",true);out.putBoolean("ok",true);finish(Activity.RESULT_OK,out);
  }catch(Throwable e){out.putString("error",e.toString());finish(Activity.RESULT_CANCELED,out);}
 }
 // The real retry function talks to a remote echo Binder, not a product-success mock.
 private void checkProviderRetry() throws Exception {
  Class<?> main=Class.forName("com.akashic.shell.HelperMain",true,context.getClassLoader());
  Object helper=main.getField("INSTANCE").get(null);
  Method call=main.getDeclaredMethod("callProvider",Object.class,String.class,String.class,Bundle.class);call.setAccessible(true);
  Remote remote=new Remote();remote.bind();ProviderDouble replyLoss=new ProviderDouble(remote.binder,true,false);
  Bundle reply=(Bundle)call.invoke(helper,replyLoss,"echo","attach",new Bundle());
  check(replyLoss.calls==2&&reply.getInt("echo_calls")==2&&replyLoss.asBinder()==remote.binder,"same provider retried after reply loss");
  ProviderDouble death=new ProviderDouble(remote.binder,false,true);
  try{call.invoke(helper,death,"echo","attach",new Bundle());throw new AssertionError("dead provider accepted");}
  catch(InvocationTargetException expected){check(death.calls==1,"dead provider was not retried or replaced");}
  waitDead(remote.binder);remote.unbind();
 }
 public static final class ProviderDouble implements IInterface {
  private final IBinder binder;private final boolean lose,die;public int calls;
  ProviderDouble(IBinder binder,boolean lose,boolean die){this.binder=binder;this.lose=lose;this.die=die;}
  public IBinder asBinder(){return binder;}
  public Bundle call(android.content.AttributionSource source,String authority,String method,String arg,Bundle extras)throws RemoteException {
   calls++;Parcel data=Parcel.obtain(),reply=Parcel.obtain();
   try{data.writeInt(die?1:0);binder.transact(1,data,reply,0);reply.readException();Bundle echoed=reply.readBundle();
    if(lose&&calls==1)throw new RemoteException("injected reply loss after real remote effect");return echoed;
   }finally{data.recycle();reply.recycle();}
  }
 }
 // Real remote token death, with only callback scheduling controlled by the fixture.
 private void checkHelperOldDeath() throws Exception {
  Class<?> main=Class.forName("com.akashic.shell.HelperMain",true,context.getClassLoader());
  Object helper=main.getField("INSTANCE").get(null);
  Class<?> owner=Class.forName("com.akashic.shell.HelperMain$AppOwner",true,context.getClassLoader());
  Constructor<?> make=owner.getDeclaredConstructor(IBinder.class,int.class,long.class);make.setAccessible(true);
  Field current=main.getDeclaredField("appOwner");current.setAccessible(true);
  Method died=main.getDeclaredMethod("appDied",owner);died.setAccessible(true);
  Remote previous=new Remote();previous.bind();
  Object old=make.newInstance(previous.binder,1,System.currentTimeMillis());
  Object next=make.newInstance(new Binder(),2,System.currentTimeMillis());current.set(null,old);
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1),done=new CountDownLatch(1);
  Throwable[] failure=new Throwable[1];
  previous.binder.linkToDeath(()->{entered.countDown();try {
   check(release.await(5,TimeUnit.SECONDS),"helper old death release");died.invoke(helper,old);
  }catch(Throwable error){failure[0]=error;}finally{done.countDown();}},0);
  previous.kill();check(entered.await(5,TimeUnit.SECONDS),"helper old token died");
  current.set(null,next);release.countDown();check(done.await(5,TimeUnit.SECONDS),"helper old death finished");
  check(failure[0]==null&&current.get(null)==next,"helper old death kept new token");
  current.set(null,null);previous.unbind();
 }
 private Bundle attach(IBinder b)throws Exception{return(Bundle)accept.invoke(hub,b);}
 private void waitEmpty()throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(getShell.invoke(hub)!=null&&System.nanoTime()<end)Thread.yield();check(getShell.invoke(hub)==null,"owner death clears");}
 private static void waitDead(IBinder b){long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(b.isBinderAlive()&&System.nanoTime()<end)Thread.yield();check(!b.isBinderAlive(),"remote died");}
 private static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 private IBinder wrapper(IBinder real,CountDownLatch linked,CountDownLatch release,CountDownLatch died,CountDownLatch deathRelease){
  return (IBinder)Proxy.newProxyInstance(IBinder.class.getClassLoader(),new Class[]{IBinder.class},(proxy,method,args)->{
   if(method.getName().equals("queryLocalInterface"))return null;
   if(method.getName().equals("linkToDeath")){
    IBinder.DeathRecipient recipient=(IBinder.DeathRecipient)args[0];real.linkToDeath(()->{if(died!=null){died.countDown();try{check(deathRelease.await(5,TimeUnit.SECONDS),"release old death");}catch(InterruptedException e){throw new RuntimeException(e);}}recipient.binderDied();if(died!=null)oldDeathDone.countDown();},(Integer)args[1]);
    if(linked!=null){linked.countDown();check(release.await(5,TimeUnit.SECONDS),"release link");}return null;
   }
   try{return method.invoke(real,args);}catch(InvocationTargetException e){throw e.getCause();}
  });
 }
 private final class Remote implements ServiceConnection{
  IBinder binder;CountDownLatch connected=new CountDownLatch(1);boolean bound;
  void bind()throws Exception{Intent i=new Intent().setComponent(new ComponentName("dev.akashic.hubprobe.test","dev.akashic.hubprobe.RemoteOwner"));bound=context.bindService(i,this,Context.BIND_AUTO_CREATE);check(bound&&connected.await(5,TimeUnit.SECONDS),"remote service bind");}
  public void onServiceConnected(ComponentName n,IBinder b){binder=b;connected.countDown();}
  public void onServiceDisconnected(ComponentName n){}
  void kill()throws Exception{Parcel p=Parcel.obtain();try{binder.transact(2,p,null,IBinder.FLAG_ONEWAY);}finally{p.recycle();}}
  void unbind(){if(bound){context.unbindService(this);bound=false;}}
 }
}
