#!/bin/bash
mkdir -p build
echo "Compiling..."
javac --release 21 -encoding UTF-8 -d build src/*.java || { echo "Compile failed."; exit 1; }
echo "Starting..."
java -cp build MHXXCharmApp
