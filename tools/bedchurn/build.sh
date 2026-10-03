#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
rm -rf build && mkdir -p build
javac -encoding UTF-8 --release 21 -proc:none -nowarn \
      -cp "/root/errands3/stubs:/root/voyager/stubs:/root/errands3/libs/*:/root/errands3/build:/root/nfserver/libraries/org/apache/maven/maven-artifact/3.8.5/maven-artifact-3.8.5.jar" \
      -d build BedChurn.java
jar cf bedchurn-1.0.0.jar -C build . -C res .
echo "built bedchurn-1.0.0.jar"
