#!/bin/sh
# Build (single javac invocation, Java SE 8, zero dependencies)
# and run the committed headless test suite.
set -e
cd "$(dirname "$0")"
rm -rf bin bin-test
mkdir -p bin bin-test
javac --release 8 -Xlint:-options -d bin src/*.java
javac --release 8 -Xlint:-options -cp bin -d bin-test test/*.java
java -Djava.awt.headless=true -cp bin:bin-test RunAllTests
