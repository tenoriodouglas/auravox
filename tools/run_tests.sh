#!/usr/bin/env bash
# Builds and runs the DSP tests on the host. No Android needed: the dsp/,
# track/ and karaoke/ headers depend on nothing but the standard library.
set -e
cd "$(dirname "$0")"
OUT="${TMPDIR:-/tmp}/auravox_test"
g++ -std=c++17 -O2 -Wall -Wextra -Wno-unused-parameter \
    -I../app/src/main/cpp dsp_test.cpp -o "$OUT" -lpthread
"$OUT"

# Second pass under the sanitizers. The separation code walks two spectra by
# index with per-channel frame counts, and a reader cannot see an overrun that
# only happens when the two channels differ in length -- the one that was
# there did not fail a single assertion, it just read past the end of a vector
# and got plausible numbers. Pass "fast" to skip this.
if [ "${1:-}" != "fast" ]; then
    echo
    echo "--- sob AddressSanitizer e UBSan ---"
    ASAN="${TMPDIR:-/tmp}/auravox_test_asan"
    g++ -std=c++17 -O1 -g -fsanitize=address,undefined -fno-omit-frame-pointer \
        -Wall -Wextra -Wno-unused-parameter \
        -I../app/src/main/cpp dsp_test.cpp -o "$ASAN" -lpthread
    "$ASAN" > /dev/null
    echo "nenhum erro de memoria"
fi
