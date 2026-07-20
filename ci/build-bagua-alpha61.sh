#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$PWD"
trap 'rc=$?; mkdir -p "$ROOT_DIR/work"; printf "exit=%s\nline=%s\ncommand=%s\n" "$rc" "$LINENO" "$BASH_COMMAND" > "$ROOT_DIR/work/alpha7-error.txt"; [ -f "$ROOT_DIR/status-test.txt" ] && cp "$ROOT_DIR/status-test.txt" "$ROOT_DIR/work/status-test-failed.txt" || true; exit "$rc"' ERR

rm -rf work bagua-alpha5-source.zip status-test.txt bridge-hidden-test.txt
cat ci/bagua-src.b64.part* | tr -d '\n\r' | base64 -d > bagua-alpha5-source.zip
echo "ef766b71e62939064643ae66f1b7beeca82cd83d072e6fbd41762425c0e1408e  bagua-alpha5-source.zip" | sha256sum -c -
unzip -tq bagua-alpha5-source.zip
unzip -q bagua-alpha5-source.zip -d work

sed -i 's/fun setSkin(/fun applySkin(/; s/fun setThemeMode(/fun applyThemeMode(/; s/fun setAmoled(/fun applyAmoled(/; s/fun setMonet(/fun applyMonet(/' work/app/src/main/java/io/github/xgl34222220/bagua/BaguaController.kt
sed -i 's/controller::setSkin/controller::applySkin/g; s/controller::setThemeMode/controller::applyThemeMode/g; s/controller::setAmoled/controller::applyAmoled/g; s/controller::setMonet/controller::applyMonet/g' work/app/src/main/java/io/github/xgl34222220/bagua/Screens.kt
python3 ci/patch-bagua-alpha51.py work
base64 -d ci/bagua-alpha6-patch.py.gz.b64 | gzip -d > ci/patch-bagua-alpha6.py
echo "dde1a731ccb9678ee2fe24c8af90048522ff0397e28e8a527a4aff3efd26ba61  ci/patch-bagua-alpha6.py" | sha256sum -c -
python3 ci/patch-bagua-alpha6.py work
python3 ci/patch-bagua-alpha61.py work
python3 ci/patch-bagua-alpha62.py work
cat ci/bagua-alpha7-patch.b64.part00 ci/bagua-alpha7-patch.b64.part01 ci/bagua-alpha7-patch.b64.part02 | base64 -d | gzip -d > ci/patch-bagua-alpha7.py
echo "efe62e780835545c21e52710a341519d1d95f2e883878a08b3752b5283bb7e9a  ci/patch-bagua-alpha7.py" | sha256sum -c -
python3 ci/patch-bagua-alpha7.py work

cd work
C=app/src/main/java/io/github/xgl34222220/bagua/BaguaController.kt
test ! -d module-src/webroot
test ! -d module-src/webui
test ! -d module-src/www
for f in module-src/customize.sh module-src/post-fs-data.sh module-src/service.sh module-src/action.sh module-src/uninstall.sh module-src/scripts/*.sh; do
  bash -n "$f"
done

grep -Fq 'BRIDGE=/data/adb/bagua/bridge.sh' "$C"
grep -Fq 'sh "${'"'$'"'}BRIDGE"' "$C"
grep -q 'BAGUA_MODDIR' module-src/scripts/bagua.sh
grep -q 'HOSTS="$PERSIST/hosts.active"' module-src/scripts/bagua.sh
grep -q 'RESULT=runtime_synced' module-src/scripts/sync-runtime.sh
grep -q 'versionName = "0.7.0-alpha.7"' app/build.gradle.kts
grep -q '^version=0.7.0-alpha.7$' module-src/module.prop
grep -q '^versionCode=110$' module-src/module.prop

# Simulate the real failure mode: build the bridge, then hide the entire module directory.
sudo rm -rf /data/adb/modules/bagua /data/adb/modules/bagua.hidden /data/adb/modules_update/bagua /data/adb/bagua
sudo mkdir -p /data/adb/modules
sudo cp -a module-src /data/adb/modules/bagua
sudo chmod +x /data/adb/modules/bagua/scripts/*.sh
sudo sh /data/adb/modules/bagua/scripts/sync-runtime.sh > ../status-test.txt
grep -q '^RESULT=runtime_synced$' ../status-test.txt
sudo sh /data/adb/bagua/bridge.sh status >> ../status-test.txt
grep -q '^NAME=八卦$' ../status-test.txt
grep -q '^VERSION=0.7.0-alpha.7$' ../status-test.txt
sudo mv /data/adb/modules/bagua /data/adb/modules/bagua.hidden
sudo sh /data/adb/bagua/bridge.sh status > ../bridge-hidden-test.txt
grep -q '^NAME=八卦$' ../bridge-hidden-test.txt
grep -q '^VERSION=0.7.0-alpha.7$' ../bridge-hidden-test.txt
sudo sh /data/adb/bagua/bridge.sh set THEME_MODE dark >> ../bridge-hidden-test.txt
grep -q '^THEME_MODE=dark$' <(sudo sh /data/adb/bagua/bridge.sh status)

set -o pipefail
gradle --no-daemon :app:assembleDebug 2>&1 | tee build-alpha7.log
bash scripts/package-module.sh

ZIP=dist/BaGua-v0.7.0-alpha.7-AppOnly.zip
APK=module-src/app/bagua.apk
AAPT="$ANDROID_HOME/build-tools/36.0.0/aapt"
unzip -tq "$ZIP"
unzip -l "$ZIP" | grep -q 'app/bagua.apk'
unzip -l "$ZIP" | grep -q 'scripts/bagua.sh'
unzip -l "$ZIP" | grep -q 'scripts/sync-runtime.sh'
! unzip -l "$ZIP" | grep -Ei 'webroot|webui|/www/'
unzip -p "$ZIP" module.prop | grep -q '^version=0.7.0-alpha.7$'
unzip -p "$ZIP" module.prop | grep -q '^versionCode=110$'
"$AAPT" dump badging "$APK" | grep -q "package: name='io.github.xgl34222220.bagua'"
"$AAPT" dump badging "$APK" | grep -q "versionCode='110'"
"$AAPT" dump badging "$APK" | grep -q "versionName='0.7.0-alpha.7'"
"$AAPT" dump badging "$APK" | grep -q "application-label:'八卦'"
test -s dist/BaGua-v0.7.0-alpha.7-SHA256.txt

mkdir -p split
split -b 2M -d -a 2 "$ZIP" split/module.part.
count=$(find split -name 'module.part.*' -type f | wc -l)
test "$count" -le 12
for n in 00 01 02 03 04 05 06 07 08 09 10 11; do
  [ -f "split/module.part.$n" ] || : > "split/module.part.$n"
done
cat split/module.part.0* > split/reconstructed.zip
cmp -s split/reconstructed.zip "$ZIP"
rm split/reconstructed.zip
cp dist/BaGua-v0.7.0-alpha.7-AppOnly-Source.zip split/
cp dist/BaGua-v0.7.0-alpha.7-SHA256.txt split/
cp ../status-test.txt split/
cp ../bridge-hidden-test.txt split/
