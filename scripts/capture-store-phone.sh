#!/usr/bin/env bash
set -euo pipefail
mkdir -p dist/store-phone
adb shell wm size 1080x1920
adb shell wm density 360
adb shell settings put system font_scale 1.0
adb shell cmd uimode night no
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 2045
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
# The emulator is a new, disposable instance. App network access stays off during capture.
adb shell svc wifi disable
adb shell svc data disable
capture_result=0
./gradlew --no-daemon --no-configuration-cache :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.yosikou.hagemashi.StorePhoneScreenshotsTest -Pandroid.testInstrumentationRunnerArguments.storeScreenshots=true || capture_result=$?
# Retain already captured images for diagnosis even if the test fails; do not hide its result.
adb pull /sdcard/Pictures/hagemashi-store/. dist/store-phone/ || true
test "$capture_result" -eq 0
test "$(find dist/store-phone -name '*.png' | wc -l)" -ge 2
