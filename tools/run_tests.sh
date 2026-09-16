#!/usr/bin/env bash
# Builds and runs the DSP tests on the host. No Android needed: the dsp/,
# track/ and karaoke/ headers depend on nothing but the standard library.
set -e
cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/auravox_test"
g++ -std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter \
    -I../app/src/main/cpp dsp_test.cpp -o "$OUT" -lpthread
"$OUT"
