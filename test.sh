#!/bin/bash
set -e
mkdir -p build build-test
javac --release 21 -encoding UTF-8 -d build src/*.java
javac --release 21 -encoding UTF-8 -cp build -d build-test test/*.java
java -ea -cp "build:build-test" MHXXCharmAppTest
