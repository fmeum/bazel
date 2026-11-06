#!/bin/bash
# Usage: VALHALLA_JDK=... BASELINE_JDK=... JMH_LIB=<dir with jmh-core, jmh-generator-annprocess,
#        jopt-simple, commons-math3 jars> ./run.sh
set -euo pipefail
cd "$(dirname "$0")"
LIB=$(ls "$JMH_LIB"/*.jar | tr '\n' ':')
rm -rf out-common out-valhalla && mkdir out-common out-valhalla
"$BASELINE_JDK/bin/javac" --release 21 -proc:full -cp "$LIB" -d out-common src/common/bench/*.java
"$VALHALLA_JDK/bin/javac" --enable-preview --source 27 -proc:full \
  --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -cp "$LIB" -d out-valhalla src/common/bench/*.java src/valhalla/bench/*.java
"$VALHALLA_JDK/bin/java" --enable-preview --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -cp "out-valhalla:$LIB" org.openjdk.jmh.Main -prof gc
"$VALHALLA_JDK/bin/java" --enable-preview --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
  -cp "out-valhalla:$LIB" org.openjdk.jmh.Main -prof gc \
  -jvmArgsAppend '-Xms4g -Xmx4g -XX:+UseG1GC -XX:+UseCompactObjectHeaders'
"$BASELINE_JDK/bin/java" -cp "out-common:$LIB" org.openjdk.jmh.Main -prof gc
