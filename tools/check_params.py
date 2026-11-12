#!/usr/bin/env python3
"""Holds the two sides of the parameter contract together.

The ids in Params.h and Param.kt are the whole interface between the engine
and the screens, and nothing in either language notices when they drift: a
parameter added natively and forgotten in Kotlin is simply unreachable, and a
number that moves silently points a slider at the wrong control. That happened
once, with the vibrato and attack parameters, which is why this exists.

Run from anywhere. Non-zero exit means the two files disagree.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CPP = ROOT / "app/src/main/cpp/Params.h"
KT = ROOT / "app/src/main/java/com/auravox/audio/Param.kt"

# Named in C++ for the engine's own bookkeeping, with no control behind them
CPP_ONLY = {"kLastVocalParam", "kParamCount"}


def cpp_ids(text):
    """Walks the enum the way the compiler does, including the explicit jumps."""
    body = text[text.index("enum ParamId {"):text.index("kParamCount")]
    ids, nxt = {}, 0
    for raw in body.splitlines():
        line = raw.split("//")[0].strip().rstrip(",")
        m = re.fullmatch(r"(k\w+)\s*=\s*(\d+)", line)
        if m:
            nxt = int(m.group(2))
            ids[m.group(1)] = nxt
            nxt += 1
            continue
        m = re.fullmatch(r"(k\w+)\s*=\s*(k\w+)", line)
        if m:
            if m.group(2) not in ids:
                raise SystemExit(f"{m.group(1)} aliases {m.group(2)}, which is not defined yet")
            ids[m.group(1)] = ids[m.group(2)]
            continue
        m = re.fullmatch(r"(k\w+)", line)
        if m:
            ids[m.group(1)] = nxt
            nxt += 1
    return ids


def kotlin_ids(text):
    return {m.group(1): int(m.group(2))
            for m in re.finditer(r"const val (\w+) = (\d+)", text)}


def main():
    cpp = cpp_ids(CPP.read_text())
    kt = kotlin_ids(KT.read_text())

    by_value = {}
    for name, value in kt.items():
        by_value.setdefault(value, []).append(name)

    problems = []

    for name, value in sorted(cpp.items(), key=lambda kv: kv[1]):
        if name in CPP_ONLY:
            continue
        if value not in by_value:
            problems.append(f"{name} = {value} nao tem equivalente em Param.kt")

    cpp_values = set(v for k, v in cpp.items() if k not in CPP_ONLY)
    for value, names in sorted(by_value.items()):
        if value not in cpp_values:
            problems.append(f"{', '.join(names)} = {value} nao existe em Params.h")
        if len(names) > 1:
            problems.append(f"ids repetidos em Param.kt: {', '.join(names)} = {value}")

    # Preset files are keyed on names, so a missing name silently drops a
    # control from everything anyone saves or shares
    named = set(re.findall(r'"(\w+)" to (\w+)', KT.read_text()))
    named_consts = {const for _, const in named}
    for name in kt:
        if name not in named_consts:
            problems.append(f"{name} nao esta em Param.byName, entao nao entra num preset")
    for text, const in named:
        if const not in kt:
            problems.append(f"Param.byName aponta para {const}, que nao existe")
        elif text != const.lower():
            problems.append(f'Param.byName usa "{text}" para {const}; esperado "{const.lower()}"')

    count = re.search(r"kParamCount = (\d+)", CPP.read_text())
    if count:
        limit = int(count.group(1))
        for name, value in cpp.items():
            if name not in CPP_ONLY and value >= limit:
                problems.append(f"{name} = {value} esta fora de kParamCount = {limit}")

    if problems:
        print("\033[31mcontrato de parametros quebrado\033[0m")
        for p in problems:
            print(f"  {p}")
        return 1

    print(f"\033[32mok\033[0m   {len(cpp) - len(CPP_ONLY & set(cpp))} parametros "
          f"em sincronia entre Params.h e Param.kt, {len(named)} com nome para presets")
    return 0


if __name__ == "__main__":
    sys.exit(main())
