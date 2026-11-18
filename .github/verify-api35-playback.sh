set -euo pipefail

adb shell 'getconf PAGE_SIZE; getprop ro.build.version.sdk; getprop ro.product.cpu.abi; getprop ro.build.fingerprint' |
  tr -d '\r' | tee evidence/runtime.txt
test "$(sed -n '1p' evidence/runtime.txt)" = 16384
test "$(sed -n '2p' evidence/runtime.txt)" = 35
test "$(sed -n '3p' evidence/runtime.txt)" = x86_64

cp "$ANDROID_HOME/emulator/source.properties" evidence/emulator.txt
if adb shell 'su 0 id' 2>/dev/null | grep -q 'uid=0'; then
  adb shell 'su 0 setprop bionic.linker.16kb.app_compat.enabled false'
  adb shell 'su 0 setprop pm.16kb.app_compat.disabled true'
fi
adb shell 'getprop ro.build.type; getprop bionic.linker.16kb.app_compat.enabled; getprop pm.16kb.app_compat.disabled' >> evidence/runtime.txt

adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
timeout 60 adb install -r app/build/outputs/apk/debug/app-debug.apk

result=0
for attempt in 1 2; do
  adb shell am force-stop com.p33nah.loopo
  adb logcat -b main -b system -b crash -c
  if ! timeout 40 adb shell am start -W -n com.p33nah.loopo/.data.audio.PhysicalPlaybackActivity | tee "evidence/start-$attempt.txt"; then
    result=1
  fi

  for poll in {1..20}; do
    adb logcat -d -v threadtime -s LoopoPlaybackCheck > "evidence/playback-$attempt.txt"
    if grep -Eq '2 A/B wraps|LoopoPlaybackCheck: FAIL' "evidence/playback-$attempt.txt"; then
      break
    fi
    sleep 3
  done
  cat "evidence/playback-$attempt.txt"
  adb logcat -b main -b system -b crash -d -v threadtime |
    grep -E 'LoopoPlaybackCheck|Oboe|AAudio|pcm_writei|libloopo_native|UnsatisfiedLinkError|SIGSEGV|Fatal signal|FATAL EXCEPTION|AndroidRuntime' > "evidence/audio-$attempt.txt" || true
  if ! grep -q 'PASS seek to A, 73% speed, 2 A/B wraps, then pause' "evidence/playback-$attempt.txt"; then
    result=1
  fi
done
exit "$result"
