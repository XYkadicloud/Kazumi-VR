#!/usr/bin/env python3
"""Rough delimiter balance check for the Kotlin VR sources.

Catches unclosed braces / unterminated strings before a CI round trip. It is
NOT a parser: Kotlin string templates and char literals are handled heuristically.
"""
import glob
import sys

PAIRS = {')': '(', ']': '[', '}': '{'}
BACKSLASH = chr(92)


def check(path):
    with open(path, encoding='utf-8') as fh:
        src = fh.read()
    stack = []
    i = 0
    line = 1
    n = len(src)
    while i < n:
        c = src[i]
        if c == '\n':
            line += 1
            i += 1
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '/':
            while i < n and src[i] != '\n':
                i += 1
            continue
        if c == '/' and i + 1 < n and src[i + 1] == '*':
            i += 2
            while i + 1 < n and not (src[i] == '*' and src[i + 1] == '/'):
                if src[i] == '\n':
                    line += 1
                i += 1
            i += 2
            continue
        if src.startswith('"""', i):
            i += 3
            while i + 2 < n and not src.startswith('"""', i):
                if src[i] == '\n':
                    line += 1
                i += 1
            i += 3
            continue
        if c in ('"', "'"):
            q = c
            i += 1
            while i < n:
                if src[i] == BACKSLASH:
                    i += 2
                    continue
                if src[i] == q:
                    i += 1
                    break
                if src[i] == '\n':
                    line += 1
                i += 1
            continue
        if c in '([{':
            stack.append((c, line))
        elif c in ')]}':
            if not stack or stack[-1][0] != PAIRS[c]:
                return 'UNBALANCED %s:%d %s stack=%s' % (path, line, c, stack[-3:])
            stack.pop()
        i += 1
    if stack:
        return 'UNCLOSED %s: %s' % (path, stack[-3:])
    return 'OK %s' % path


def main():
    files = sorted(
        glob.glob('android/app/src/main/kotlin/com/example/kazumi/vr/*.kt')
        + ['android/app/src/main/kotlin/com/example/kazumi/MainActivity.kt']
        + sorted(glob.glob('android/app/src/test/kotlin/com/example/kazumi/vr/*.kt'))
    )
    bad = 0
    for path in files:
        result = check(path)
        print(result)
        if not result.startswith('OK'):
            bad += 1
    print('checked %d files, %d problems' % (len(files), bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
