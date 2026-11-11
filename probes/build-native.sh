#!/usr/bin/env bash
set -euo pipefail

# Build test-only APKs outside app/src; these do not ship in the Shell APK.
sdk=${ANDROID_HOME:?Set ANDROID_HOME}
tools="$sdk/build-tools/35.0.0"
android="$sdk/platforms/android-36/android.jar"
root=$(cd "$(dirname "$0")" && pwd)
output=${1:?Usage: build-native.sh OUTPUT_DIRECTORY}
mkdir -p "$output"
output=$(cd "$output" && pwd)

for probe in self-kill helper-handoff; do
    destination="$output/$probe"
    test ! -e "$destination" # Preserve any earlier evidence.
    mkdir -p "$destination/classes" "$destination/dex"
    javac --release 8 -cp "$android" -d "$destination/classes" "$root/$probe/"*.java
    mapfile -t classes < <(rg --files "$destination/classes" -g '*.class')
    "$tools/d8" --lib "$android" --output "$destination/dex" "${classes[@]}"
    "$tools/aapt2" link -I "$android" --manifest "$root/$probe/AndroidManifest.xml" -o "$destination/unsigned.apk"
    zip -q -j "$destination/unsigned.apk" "$destination/dex/classes.dex"
    "$tools/zipalign" -f 4 "$destination/unsigned.apk" "$destination/aligned.apk"
    "$tools/apksigner" sign --ks "${PROBE_KEYSTORE:-$HOME/.android/debug.keystore}" \
        --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
        --out "$destination/probe.apk" "$destination/aligned.apk"
    "$tools/apksigner" verify "$destination/probe.apk"
    sha256sum "$destination/probe.apk"
done
