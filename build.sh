#!/usr/bin/env bash
# Builds build/music-relay.apk with the plain Android SDK tools (no Gradle).
#
#   ANDROID_HOME   Android SDK with build-tools and platforms/android-34 (required)
#   JAVA_HOME      JDK 17+ (optional if javac is on PATH)
#   BUILD_TOOLS    build-tools version, default 34.0.0
#   KEYSTORE, KEYSTORE_PASS, KEY_ALIAS
#                  release signing key; without them a local debug key is created
#                  in signing/debug.jks (never commit it).
set -euo pipefail
export MSYS_NO_PATHCONV=1
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] || { echo "Set ANDROID_HOME to your Android SDK" >&2; exit 1; }
BT="$SDK/build-tools/${BUILD_TOOLS:-34.0.0}"
JAR="$SDK/platforms/android-34/android.jar"
[ -d "$BT" ] && [ -f "$JAR" ] || { echo "Need build-tools ${BUILD_TOOLS:-34.0.0} and platforms;android-34" >&2; exit 1; }
[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"

# On Windows (Git Bash) the SDK tools are .exe / .bat files and need Windows paths.
if [ -f "$BT/aapt2.exe" ]; then
    win() { cygpath -w "$1"; }
    [ -n "${JAVA_HOME:-}" ] && export JAVA_HOME="$(win "$JAVA_HOME")"
    AAPT2="$BT/aapt2.exe"; ZIPALIGN="$BT/zipalign.exe"
    d8() { cmd.exe /c "$(win "$BT/d8.bat")" "$@"; }
    apksigner() { cmd.exe /c "$(win "$BT/apksigner.bat")" "$@"; }
else
    win() { echo "$1"; }
    AAPT2="$BT/aapt2"; ZIPALIGN="$BT/zipalign"
    d8() { "$BT/d8" "$@"; }
    apksigner() { "$BT/apksigner" "$@"; }
fi
AJAR="$(win "$JAR")"

rm -rf build
mkdir -p build/cls
"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link --manifest AndroidManifest.xml -R build/res.zip -I "$AJAR" \
    --java build/gen -o build/base.apk
javac --release 8 -nowarn -encoding UTF-8 -cp "$AJAR" -d build/cls \
    src/app/musicrelay/*.java build/gen/app/musicrelay/R.java
(cd build/cls && d8 --release --lib "$AJAR" --min-api 30 --output .. app/musicrelay/*.class)
(cd build && cp base.apk u.apk && jar -uf u.apk classes.dex && "$ZIPALIGN" -f 4 u.apk a.apk)

if [ -z "${KEYSTORE:-}" ]; then
    mkdir -p signing
    KEYSTORE=signing/debug.jks KEYSTORE_PASS=android KEY_ALIAS=debug
    [ -f "$KEYSTORE" ] || keytool -genkeypair -keystore "$KEYSTORE" -storepass android \
        -keypass android -alias debug -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=Music Relay Debug" >/dev/null 2>&1
    echo "Signing with a local debug key (set KEYSTORE for a release build)"
fi
apksigner sign --ks "$(win "$KEYSTORE")" --ks-pass "pass:$KEYSTORE_PASS" \
    --ks-key-alias "${KEY_ALIAS:-release}" --out build/music-relay.apk build/a.apk
ls -la build/music-relay.apk
