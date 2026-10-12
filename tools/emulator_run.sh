#!/bin/bash
# Run on the CI emulator: install the app and its tests, then
#   1. set up (fresh install)  2. force-stop: a real restart  3. check settings survived  4. the chat flow.
# Screenshots are pulled to ./shots. Exit status is non-zero if any step failed.
set -u
exec > >(tee ui.log) 2>&1
APP=app.gemma.android
RUNNER=$APP.test/androidx.test.runner.AndroidJUnitRunner
adb uninstall $APP >/dev/null 2>&1
adb install -r app/build/outputs/apk/debug/app-debug.apk || exit 1
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk || exit 1
adb shell pm grant $APP android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
fail=0
run() {
  echo "=== $1"
  out=$(adb shell am instrument -w -e class "app.gemma.android.$1" $RUNNER 2>&1)
  echo "$out" | tail -25
  if ! echo "$out" | grep -q "^OK ("; then fail=1; echo "!!! $1 FAILED"; fi
}
run "PersistenceTest#firstRunSavesWhenTheTestSucceeds"
adb shell am force-stop $APP
echo "process after force-stop: $(adb shell pidof $APP || echo none)"
run "PersistenceTest#settingsSurviveARealRestart"
run "ChatFlowTest"
mkdir -p shots
adb pull /sdcard/Android/data/$APP/files/shots shots/ >/dev/null 2>&1 || true
ls -R shots
exit $fail
