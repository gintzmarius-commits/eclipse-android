#!/usr/bin/env python3
"""Prints the centre "x y" of the first on-screen element (from a
uiautomator dump) whose text or description contains the given text."""
import re
import sys
import xml.etree.ElementTree as ET

path, want = sys.argv[1], sys.argv[2].casefold()
for n in ET.parse(path).iter("node"):
    label = (n.get("text") or "") + " " + (n.get("content-desc") or "")
    if want in label.casefold():
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            if x2 > x1 and y2 > y1:
                print((x1 + x2) // 2, (y1 + y2) // 2)
                sys.exit(0)
sys.exit(1)
