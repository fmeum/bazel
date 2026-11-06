#!/bin/bash
# Builds patched copies of a Bazel HEAD install base (from `bazel-dev info install_base`):
#   ib/base   - unmodified
#   ib/digest - digest256_value_class.patch
#   ib/pf     - pathfragment_value_class.patch
#   ib/both   - both patches
# Usage: SCRATCH=<dir> VALHALLA_JDK=<jdk-27-jep401ea3> INSTALL_BASE=<dir> ./build_jars.sh
# Run from a clean Bazel checkout at the commit the install base was built from.
set -euo pipefail
B=src/main/java/com/google/devtools/build/lib
PF_SRCS="$B/vfs/PathFragment.java $B/actions/Artifact.java $B/vfs/RootedPath.java $B/vfs/Path.java $B/cmdline/PackageIdentifier.java $B/actions/ArtifactRoot.java"
DG_SRCS="$B/vfs/Digest256.java $B/actions/FileArtifactValue.java $B/vfs/DigestUtils.java"
OLD_PF_SUBCLASSES=(
  'com/google/devtools/build/lib/vfs/PathFragment$RelativePathFragment.class'
  'com/google/devtools/build/lib/vfs/PathFragment$UnixStyleAbsolutePathFragment.class'
  'com/google/devtools/build/lib/vfs/PathFragment$WindowsStyleAbsolutePathFragment.class')
HERE=$(dirname "$(realpath "$0")")
mkdir -p "$SCRATCH/ib" "$SCRATCH/proto"
cp -a "$INSTALL_BASE" "$SCRATCH/ib/base"
git apply "$HERE/pathfragment_value_class.patch" "$HERE/digest256_value_class.patch"
compile() { # <out> <srcs...>
  local out=$1; shift
  mkdir -p "$out"
  "$VALHALLA_JDK/bin/javac" --enable-preview --source 27 -proc:none -nowarn \
    --add-exports java.base/jdk.internal.vm.annotation=ALL-UNNAMED \
    -cp "$SCRATCH/ib/base/A-server.jar" -d "$out" "$@"
}
compile "$SCRATCH/proto/pf" $PF_SRCS
compile "$SCRATCH/proto/digest" $DG_SRCS
compile "$SCRATCH/proto/both" $PF_SRCS $DG_SRCS
git apply -R "$HERE/pathfragment_value_class.patch" "$HERE/digest256_value_class.patch"
for v in pf digest both; do
  cp -a "$SCRATCH/ib/base" "$SCRATCH/ib/$v"
  jar="$SCRATCH/ib/$v/A-server.jar"
  chmod u+w "$jar"
  if [ "$v" != digest ]; then zip -q -d "$jar" "${OLD_PF_SUBCLASSES[@]}"; fi
  (cd "$SCRATCH/proto/$v" && zip -q "$jar" $(find . -name '*.class' | sed 's|^\./||'))
  # The client rejects install bases whose files don't have a far-future mtime.
  touch -r "$SCRATCH/ib/base/A-server.jar" "$jar"
  chmod --reference="$SCRATCH/ib/base/A-server.jar" "$jar"
done
