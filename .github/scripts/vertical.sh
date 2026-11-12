#!/bin/bash
# Capturas del inicio en vertical (pantallas altas estilo Tesla). Uso: vertical.sh <ancho>x<alto> <carpeta>
PKG=com.stradaos.launcher.debug
ACT=$PKG/com.stradaos.launcher.Main
SIZE=${1:-768x1024}
OUT=${2:-capturas}; mkdir -p $OUT
adb wait-for-device
for i in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = "1" ] && break; sleep 3; done
sleep 10
adb shell input keyevent 82 || true
adb shell wm size $SIZE; adb shell wm density 160; sleep 5
adb install -r -g prueba.apk | tail -1
adb shell pm grant $PKG android.permission.ACCESS_FINE_LOCATION >/dev/null 2>&1 || true
( while true; do adb emu geo fix 31.2357 30.0444 >/dev/null 2>&1; adb emu geo fix 31.2357 30.0444 30 8 26 >/dev/null 2>&1; sleep 2; done ) &
GEO=$!
shot() { adb shell screencap -p /data/local/tmp/s.png && adb pull /data/local/tmp/s.png "$OUT/$1.png" >/dev/null; echo "captura $1"; }
go() { adb shell am start -n $ACT --es prueba "$1" >/dev/null; sleep "${2:-5}"; }
adb shell am start -n $ACT --es prueba saltar >/dev/null; sleep 15
go demo-aceite 6
go pista-demo 4
for L in es ar en; do
  go idioma:$L 8
  go tema:oscuro 6; go diseno:base 6; go pista-demo 3; sleep 15; shot $L-oscuro
  go tema:claro 6; go pista-demo 3; sleep 6; shot $L-claro
  go tema:oscuro 4; go diseno:sinauto 6; go pista-demo 3; sleep 6; shot $L-sin-vehiculo
  go diseno:base 6
done
go ajustes:2 6; shot ajustes-diseno
adb shell input keyevent 4; sleep 2
adb logcat -d | grep -iE "AndroidRuntime|FATAL|stradaos" | tail -80 > $OUT/logcat.txt || true
kill $GEO 2>/dev/null
ls -l $OUT
