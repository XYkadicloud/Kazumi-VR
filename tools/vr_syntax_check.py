#!/usr/bin/env python3
"""Delimiter/string balance sanity check for the VR Dart sources.

There is no Flutter SDK on the development workstation, so this catches the
class of mistake (unclosed brace / unterminated string) that would otherwise
only surface as a red CI run.
"""
import sys

FILES = [
    'lib/pages/settings/vr_settings_page.dart',
    'lib/services/platform/vr_service.dart',
    'lib/pages/player/vr/vr_settings.dart',
    'lib/pages/player/player_item.dart',
    'lib/pages/player/player_item_panel.dart',
    'lib/pages/my/my_space_view.dart',
    'lib/pages/my/my_page.dart',
    'lib/pages/settings/settings_module.dart',
    'lib/pages/settings/settings_page.dart',
    'lib/pages/settings/player_settings.dart',
]

PAIRS = {')': '(', ']': '[', '}': '{'}
QUOTES = ('"', "'")
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
        if c in QUOTES:
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
    bad = 0
    for path in FILES:
        result = check(path)
        print(result)
        if not result.startswith('OK'):
            bad += 1
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
