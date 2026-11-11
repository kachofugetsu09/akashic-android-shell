# Helper recovery diagnostics

These APKs are test-only. They are not part of the Shell APK and request no new
permissions. Use a dedicated device window and the same signing key as the target.
The default build script uses the repository's local debug signing convention.

```sh
ANDROID_HOME=/path/to/android-sdk bash probes/build-native.sh /tmp/helper-probes-new-run
```

## Existing-process SIGKILL

`self-kill/SelfKillProbe.java` runs inside the existing release App with Android 16
`--no-restart`. It verifies the exact target PID and UID. First use `kill=false` and
check that App, foreground service, helper and VD identities remain unchanged:

```sh
adb install -t /tmp/helper-probes-new-run/self-kill/probe.apk
adb shell am instrument --no-restart -w -e expected_pid APP_PID -e kill false \
  dev.akashic.selfkillprobe/dev.akashic.selfkill.SelfKillProbe
```

For the fault run, use the same command with `kill=true` and a freshly checked PID.
The fixture calls `finish()` first. Android clears active instrumentation, then
`onDestroy()` sends SIGKILL from the App's own UID. This does not use force-stop,
crash injection, a diagnostic Shell variant, or an Activity launch.

Pin the target APK hash and record TLS state, App/helper PIDs, owned VD, foreground
service, SSE owner, independent task/provider results and physical-panel samples.
After disabling TLS for the authorized fault window, do not bootstrap a helper from
the computer. Restore the test's original setting afterward. A running helper is
not evidence that helper death with TLS disabled can recover.

## Binder handoff faults

Install the candidate **debug** Shell (`com.akashic.shell.debug`) separately. Then:

```sh
adb install -t /tmp/helper-probes-new-run/helper-handoff/probe.apk
adb shell am instrument -w dev.akashic.hubprobe.test/dev.akashic.hubprobe.HubProbe
```

This loads the real debug Hub and uses a real remote Android Binder. Discarding the
first acceptance reply models reply loss: repeating the same Binder must return
the same App token and keep the same owner. A different live Binder must be
rejected. Remote process death and gates around `linkToDeath` verify dead-Binder
admission and late old death callbacks.

The actual helper retry function also talks to a remote echo Binder: the fixture
discards a reply after the remote counter changed, then checks two calls to the
same provider. Killing that remote provider must end the round after one call.
The echo reply contains no product `accepted` result. Production holds one
external provider handle around this retry loop and releases it in `finally`;
the loop cannot request a replacement provider.

The remote service is a **fault double**, not a UID 2000 helper. The reflection
gates control scheduling; they do not prove production provider transport loss,
VD behavior, or helper recovery. A separately gated remote token death also calls
the real helper death handler after a replacement token has been published; it
must not clear that token or touch uninitialized resources. Run the actual UID 2000 helper SIGKILL comparison
above separately. Use original and candidate debug APKs to retain the original
same-Binder rejection and candidate result.

Before positive recovery, check negative cases: current PhoneUse disabled, user
force-stop, Task Manager Stop (`USER_REQUESTED`), changed APK/UID, unavailable or
unknown exit record. None permits active recovery. Then check manual bootstrap
while disabled followed by enabling PhoneUse; there is no cached enable flag.
Provider acquisition can start an App process merely to read current preferences.
Binder acceptance alone does not prove foreground-service or SSE recovery.
