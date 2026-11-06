#!/bin/bash
# Usage: run1.sh <workload: synth|self> <variant: base|digest|pf|both|ga> <headers: coh|nocoh> <tag>
S=${SCRATCH:?set SCRATCH to a work dir containing bin/bazel-dev, jdk-27, ib/<variant>, synth}
w=$1; v=$2; h=$3; tag=$4
OB=$S/obm/$w-$v-$h-$tag
mkdir -p $S/obm
if [ "$v" = ga ]; then
  STARTUP=(--output_base=$OB)
  [ "$h" = nocoh ] && STARTUP+=(--noexperimental_use_compact_object_headers)
else
  STARTUP=(--install_base=$S/ib/$v --output_base=$OB --server_javabase=$S/jdk-27 --host_jvm_args=--enable-preview)
  [ "$h" = coh ] && STARTUP+=(--host_jvm_args=-XX:+UseCompactObjectHeaders)
fi
if [ "$w" = synth ]; then
  cd $S/synth; CMD=(build --spawn_strategy=local --jobs=8 //...)
else
  cd /home/user/bazel; CMD=(build --nobuild //src:bazel-dev)
fi
out=$S/measure/$w-$v-$h-$tag
$S/bin/bazel-dev "${STARTUP[@]}" "${CMD[@]}" > $out.log 2>&1
echo "exit=$?" >> $out.log
$S/bin/bazel-dev "${STARTUP[@]}" info used-heap-size-after-gc > $out.heap 2>/dev/null
PID=$(pgrep -f -- "--output_base=$OB" | head -1)
[ -z "$PID" ] && PID=$(pgrep -f "$OB" | head -1)
jcmd $PID GC.class_histogram > $out.hist 2>/dev/null
$S/bin/bazel-dev "${STARTUP[@]}" shutdown > /dev/null 2>&1
echo "$w $v $h $tag: $(grep -o 'Elapsed time: [0-9.]*s' $out.log) heap=$(cat $out.heap) histTotal=$(tail -1 $out.hist | awk '{print $3}')"
