#!/usr/bin/env python3
"""Extract the DECODED capability table from qr-paulmillr test/decode.test.ts into Kotlin.

Usage: python3 tools/extract_decoded.py <path-to-decode.test.ts> <js-commit-sha> > src/test/kotlin/qr/ImageVectors.kt

Parses three JS literals (stdlib only, small recursive-descent parser for the JS literal subset used):
  export const DECODED = { category: { 'imageNNN.jpg': ['text', ...], ... }, ... };
  const DECODED_ALL_ONLY = new Set([ '...', ... ]);
  const expected = [ '...', ... ].join('');   (the high-density PNG payload)
"""
import sys

ESCAPES = {'n': '\n', 't': '\t', 'r': '\r', '\\': '\\', "'": "'", '"': '"', '0': '\0', 'b': '\b', 'f': '\f', 'v': '\v'}


class Parser:
    def __init__(self, src, pos):
        self.s = src
        self.i = pos

    def fail(self, msg):
        line = self.s.count('\n', 0, self.i) + 1
        raise SystemExit(f'extract_decoded: {msg} at line {line}')

    def ws(self):
        s = self.s
        while self.i < len(s):
            c = s[self.i]
            if c in ' \t\r\n':
                self.i += 1
            elif s.startswith('//', self.i):
                nl = s.find('\n', self.i)
                self.i = len(s) if nl < 0 else nl + 1
            elif s.startswith('/*', self.i):
                end = s.find('*/', self.i + 2)
                if end < 0:
                    self.fail('unterminated comment')
                self.i = end + 2
            else:
                break

    def peek(self):
        self.ws()
        return self.s[self.i] if self.i < len(self.s) else ''

    def expect(self, tok):
        self.ws()
        if not self.s.startswith(tok, self.i):
            self.fail(f'expected {tok!r}')
        self.i += len(tok)

    def string(self):
        q = self.s[self.i]
        if q not in '\'"':
            self.fail('expected string')
        self.i += 1
        out = []
        while True:
            if self.i >= len(self.s):
                self.fail('unterminated string')
            c = self.s[self.i]
            if c == q:
                self.i += 1
                return ''.join(out)
            if c == '\n':
                self.fail('newline in string')
            if c == '\\':
                e = self.s[self.i + 1]
                if e in ESCAPES:
                    out.append(ESCAPES[e])
                    self.i += 2
                elif e == 'u':
                    out.append(chr(int(self.s[self.i + 2:self.i + 6], 16)))
                    self.i += 6
                elif e == 'x':
                    out.append(chr(int(self.s[self.i + 2:self.i + 4], 16)))
                    self.i += 4
                else:
                    self.fail(f'unsupported escape \\{e}')
            else:
                out.append(c)
                self.i += 1

    def ident(self):
        start = self.i
        while self.i < len(self.s) and (self.s[self.i].isalnum() or self.s[self.i] in '_$'):
            self.i += 1
        if start == self.i:
            self.fail('expected identifier')
        return self.s[start:self.i]

    def value(self):
        c = self.peek()
        if c == '{':
            return self.obj()
        if c == '[':
            return self.arr()
        if c in '\'"':
            return self.string()
        self.fail(f'unexpected {c!r}')

    def obj(self):
        # JS semantics for duplicate keys: first position, last value (dict assignment does the same).
        self.expect('{')
        out = {}
        while self.peek() != '}':
            key = self.string() if self.peek() in '\'"' else self.ident()
            self.expect(':')
            out[key] = self.value()
            if self.peek() == ',':
                self.i += 1
            elif self.peek() != '}':
                self.fail('expected , or }')
        self.i += 1
        return out

    def arr(self):
        self.expect('[')
        out = []
        while self.peek() != ']':
            out.append(self.value())
            if self.peek() == ',':
                self.i += 1
            elif self.peek() != ']':
                self.fail('expected , or ]')
        self.i += 1
        return out


def parse_after(src, marker):
    """Parse the literal starting at the end of `marker`; a trailing '[' / '{' in marker is part of the literal."""
    pos = src.find(marker)
    if pos < 0 or src.find(marker, pos + 1) >= 0:
        raise SystemExit(f'extract_decoded: marker {marker!r} not found exactly once')
    start = pos + len(marker)
    if marker[-1] in '[{':
        start -= 1
    p = Parser(src, start)
    return p, p.value()


def kt(s):
    out = []
    for c in s:
        if c == '\\':
            out.append('\\\\')
        elif c == '"':
            out.append('\\"')
        elif c == '$':
            out.append('\\$')
        elif c == '\n':
            out.append('\\n')
        elif c == '\r':
            out.append('\\r')
        elif c == '\t':
            out.append('\\t')
        elif ord(c) < 0x20:
            out.append('\\u%04x' % ord(c))
        else:
            out.append(c)
    return '"' + ''.join(out) + '"'


def fn_name(category):
    return 'category_' + ''.join(ch if ch.isalnum() else '_' for ch in category)


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    path, sha = sys.argv[1], sys.argv[2]
    with open(path, encoding='utf-8') as f:
        src = f.read()

    p, decoded = parse_after(src, 'export const DECODED =')
    p.expect(';')
    p, all_only = parse_after(src, 'const DECODED_ALL_ONLY = new Set(')
    p.expect(')')
    p, expected = parse_after(src, 'const expected = [')
    p.expect(".join('')")
    assert isinstance(decoded, dict) and all(isinstance(v, dict) for v in decoded.values())
    assert all(isinstance(t, list) and all(isinstance(x, str) for x in t) for c in decoded.values() for t in c.values())
    assert isinstance(all_only, list) and all(isinstance(x, str) for x in all_only)
    assert isinstance(expected, list) and all(isinstance(x, str) for x in expected)

    w = sys.stdout.write
    w(f'// GENERATED by tools/extract_decoded.py from qr-paulmillr test/decode.test.ts @ {sha}. Do not edit.\n')
    w('package qr\n\n')
    w('object ImageVectors {\n')
    w(f'    const val SOURCE_COMMIT = {kt(sha)}\n\n')
    w('    /** category -> image file name -> every payload decoded from it (JS DECODED). */\n')
    w('    val DECODED: Map<String, Map<String, List<String>>> = mapOf(\n')
    for cat in decoded:
        w(f'        {kt(cat)} to {fn_name(cat)}(),\n')
    w('    )\n\n')
    w('    /** Images decoded only in max-effort/all mode (JS DECODED_ALL_ONLY), "detection/<category>/<file>". */\n')
    w('    val DECODED_ALL_ONLY: Set<String> = setOf(\n')
    for item in all_only:
        w(f'        {kt(item)},\n')
    w('    )\n\n')
    w('    /** Payload of misc/user-images-53473345.png (decode.test.ts high-density test). */\n')
    w('    const val HIGH_DENSITY_EXPECTED: String =\n')
    w('        ' + ' +\n            '.join(kt(x) for x in expected) + '\n')
    # One function per category keeps each JVM method far below the 64 KB bytecode limit.
    for cat, images in decoded.items():
        w(f'\n    private fun {fn_name(cat)}(): Map<String, List<String>> = mapOf(\n')
        for name, texts in images.items():
            w(f'        {kt(name)} to listOf({", ".join(kt(t) for t in texts)}),\n')
        w('    )\n')
    w('}\n')


if __name__ == '__main__':
    main()
