#!/usr/bin/env bash
# Colonist Errands 3.x - plain javac against the real mod jars, then jar. No Gradle.
set -e
cd "$(dirname "$0")"
VERSION=$(grep -m1 '^version=' resources/META-INF/neoforge.mods.toml | cut -d'"' -f2)
rm -rf build2 && mkdir -p build2 out
javac -encoding UTF-8 --release 21 -proc:none -nowarn \
      -cp "stubs:/root/voyager/stubs:libs/*" -d build2 $(find src -name '*.java')
rm -rf build && mv build2 build
rm -f "out/colonist_errands-${VERSION}.jar"
jar cf "out/colonist_errands-${VERSION}.jar" -C build . -C resources .
echo "built out/colonist_errands-${VERSION}.jar"
