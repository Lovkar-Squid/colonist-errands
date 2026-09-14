#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
rm -rf build && mkdir -p build
javac -encoding UTF-8 --release 21 -proc:none -nowarn \
      -cp "/root/errands3/stubs:/root/voyager/stubs:/root/errands3/libs/*:/root/errands3/build" \
      -d build ErrandsTest.java
jar cf errandstest-1.0.0.jar -C build . -C res .
echo "built errandstest-1.0.0.jar"
