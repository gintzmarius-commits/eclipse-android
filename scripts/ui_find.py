#!/usr/bin/env python3
"""Prints the centre "x y" of the first on-screen element (from a
uiautomator dump) whose text or description contains the given text."""
import re
import sys
import xml.etree.ElementTree as ET

path, want = sys.argv[1], sys.argv[2].casefold()
# optional "exact": an element showing exactly that text wins over one
# where it is only part of a sentence
nodes = list(ET.parse(path).iter("node"))
if len(sys.argv) > 3 and sys.argv[3] == "exact":
    nodes.sort(key=lambda n: 0 if want in ((n.get("text") or "").strip().casefold(), (n.get("content-desc") or "").strip().casefold()) else 1)
for n in nodes:
    label = (n.get("text") or "") + " " + (n.get("content-desc") or "")
    if want in label.casefold():
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds") or "")
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            if x2 > x1 and y2 > y1:
                print((x1 + x2) // 2, (y1 + y2) // 2)
                sys.exit(0)
sys.exit(1)
