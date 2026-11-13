set -euo pipefail
adb shell 'getconf PAGE_SIZE; getprop ro.build.version.sdk; getprop ro.product.cpu.abi; getprop ro.build.fingerprint; getprop dalvik.vm.usejit' | tee evidence/runtime.txt
test "$(adb shell getconf PAGE_SIZE | tr -d '\r')" = 16384
cp "$ANDROID_HOME/emulator/source.properties" evidence/emulator.txt
adb shell "su 0 setprop bionic.linker.16kb.app_compat.enabled false"
adb shell "su 0 setprop pm.16kb.app_compat.disabled true"
adb shell 'getprop bionic.linker.16kb.app_compat.enabled; getprop pm.16kb.app_compat.disabled' >> evidence/runtime.txt
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
status=0
for run in 1 2; do
  adb logcat -b main -b system -b crash -c
  timeout 240 adb shell am instrument -w -r com.p33nah.loopo.test/androidx.test.runner.AndroidJUnitRunner > "evidence/run-$run.txt" || status=1
  tail -n 28 "evidence/run-$run.txt"
  adb logcat -b main -b system -b crash -d -v threadtime | grep -E 'Oboe|AAudio|pcm_writei|linker|UnsatisfiedLinkError|SIGSEGV|Fatal signal|FATAL EXCEPTION|AndroidRuntime|TestRunner' > "evidence/run-$run.logcat.txt" || true
  grep -q '^OK (' "evidence/run-$run.txt" || status=1
done
exit "$status"
