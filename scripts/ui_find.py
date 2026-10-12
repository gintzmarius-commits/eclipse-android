#!/usr/bin/env python3
"""Prints the centre "x y" of the first on-screen element (from a
uiautomator dump) whose text or description contains the given text."""
import re
import sys
import xml.etree.ElementTree as ET

path, want = sys.argv[1], sys.argv[2].casefold()
exact = want.startswith("=")  # "=Update": the whole text, e.g. a button
if exact:
    want = want[1:]
for n in ET.parse(path).iter("node"):
    label = (n.get("text") or "") + " " + (n.get("content-desc") or "")
    if exact:
        hit = want in ((n.get("text") or "").casefold().strip(), (n.get("content-desc") or "").casefold().strip())
    else:
        hit = want in label.casefold()
    if hit:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            if x2 > x1 and y2 > y1:
                print((x1 + x2) // 2, (y1 + y2) // 2)
                sys.exit(0)
sys.exit(1)
