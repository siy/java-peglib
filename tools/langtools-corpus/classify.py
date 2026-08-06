#!/usr/bin/env python3
"""
Split the OpenJDK langtools javac tests into positive and negative sets.

A file is NEGATIVE (javac is expected to reject it) if it carries an @compile/fail
directive, has a sibling .out golden file, or contains a compiler.err. marker.
Everything else is POSITIVE and should parse cleanly.

Usage: classify.py <langtools/tools/javac dir> <out-dir>
Writes positive.txt and negative.txt (absolute paths, one per line).
"""
import os
import re
import sys
from collections import Counter


def main() -> int:
    if len(sys.argv) != 3:
        print(__doc__)
        return 2

    base, outdir = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])
    positive, negative = [], {}

    for root, _, files in os.walk(base):
        goldens = {f[:-4] for f in files if f.endswith('.out')}

        for f in files:
            if not f.endswith('.java'):
                continue

            path = os.path.join(root, f)

            try:
                text = open(path, encoding='utf-8', errors='replace').read()
            except OSError:
                continue

            if re.search(r'@compile/fail', text):
                negative[path] = '@compile/fail'
            elif f[:-5] in goldens:
                negative[path] = 'has .out golden'
            elif 'compiler.err.' in text:
                negative[path] = 'compiler.err marker'
            elif re.search(r'^\s*//\s*(error|ERROR)\b', text, re.M):
                negative[path] = 'error marker'
            else:
                positive.append(path)

    os.makedirs(outdir, exist_ok=True)
    open(os.path.join(outdir, 'positive.txt'), 'w').write('\n'.join(sorted(positive)))
    open(os.path.join(outdir, 'negative.txt'), 'w').write('\n'.join(sorted(negative)))

    print(f'positive (should parse clean): {len(positive)}')
    print(f'negative (javac rejects):      {len(negative)}')

    for reason, n in Counter(negative.values()).most_common():
        print(f'    {n:5d}  {reason}')

    return 0


if __name__ == '__main__':
    raise SystemExit(main())
