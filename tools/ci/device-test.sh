#!/bin/bash
# Runs the on-device tests on the booted emulator, then collects the report, screenshots and log.
OUT=device-out
rm -rf $OUT && mkdir -p $OUT
adb wait-for-device
adb shell getprop ro.product.cpu.abilist
adb install -r -g app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -2
adb install -r -g app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk 2>&1 | tail -2
# Let the tests drive GPS, and give Rodeo the music access a rider grants in Settings.
adb shell appops set com.rodeo.hud android:mock_location allow
adb shell cmd notification allow_listener com.rodeo.hud/com.rodeo.hud.media.MediaListenerService
adb shell settings put secure location_mode 3
adb shell media volume --stream 3 --set 7 > /dev/null 2>&1 || true
adb logcat -c
adb logcat -v time > $OUT/logcat.txt 2>&1 &
LOGCAT=$!
timeout 1500 adb shell am instrument -w -r com.rodeo.hud.test/androidx.test.runner.AndroidJUnitRunner > $OUT/instrument.txt 2>&1
kill $LOGCAT 2>/dev/null
adb pull /sdcard/Android/data/com.rodeo.hud/files/e2e $OUT/ > /dev/null 2>&1 || echo "nothing to pull"
mv $OUT/e2e/* $OUT/ 2>/dev/null; rmdir $OUT/e2e 2>/dev/null
grep -E "RodeoE2E|AndroidRuntime|FATAL|rodeo" $OUT/logcat.txt | grep -v "say\[" | tail -400 > $OUT/logcat-rodeo.txt
grep -E "INSTRUMENTATION_STATUS: (test|stack)=|INSTRUMENTATION_STATUS_CODE: -2|^OK|FAILURES|Tests run" $OUT/instrument.txt | head -80
cat $OUT/report.txt 2>/dev/null | head -150
ls -la $OUT
exit 0
