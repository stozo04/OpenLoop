#!/usr/bin/env python3
"""Regenerate .idea/dictionaries/project.xml from cspell.json so the IDE and cspell share ONE
word list. `cspell.json` is the source of truth; the XML is derived and committed so Android
Studio's spellchecker (Inspect Code → "Typo") accepts the same project terms on every clone.

    python scripts/sync-ide-dictionary.py          # rewrite the XML
    python scripts/sync-ide-dictionary.py --check  # exit 1 if the committed XML is stale
"""
import json
import re
import sys
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "cspell.json"
DST = ROOT / ".idea" / "dictionaries" / "project.xml"


def main(argv):
    text = SRC.read_text(encoding="utf-8")
    text = re.sub(r"^\s*//.*$", "", text, flags=re.M)  # cspell.json tolerates // comments
    words = json.loads(text)["words"]
    # IntelliJ looks words up case-insensitively and stores them lowercased.
    words = sorted({w.lower() for w in words})
    body = "\n".join(f"      <w>{escape(w)}</w>" for w in words)
    xml = (
        "<component name=\"ProjectDictionaryState\">\n"
        "  <dictionary name=\"project\">\n"
        "    <words>\n"
        f"{body}\n"
        "    </words>\n"
        "  </dictionary>\n"
        "</component>\n"
    )
    if "--check" in argv:
        current = DST.read_text(encoding="utf-8").replace("\r\n", "\n") if DST.exists() else ""
        if current != xml:
            print(f"{DST.relative_to(ROOT)} is stale — run: python scripts/sync-ide-dictionary.py")
            return 1
        print(f"{DST.relative_to(ROOT)} in sync ({len(words)} words)")
        return 0
    DST.parent.mkdir(parents=True, exist_ok=True)
    DST.write_text(xml, encoding="utf-8", newline="\n")
    print(f"wrote {DST.relative_to(ROOT)} ({len(words)} words)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
