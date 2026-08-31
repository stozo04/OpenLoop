#!/usr/bin/env python3
"""Check (or --fix) that every GFM table has its pipes vertically aligned.

Android Studio's Markdown "Incorrect table formatting" inspection flags any table whose `|`
characters don't line up across rows (it measures in UTF-16 code units, as does markdownlint's
MD060 `aligned` style — this script counts the same way). markdownlint can *detect* the aligned
style but cannot auto-fix it, hence this tool.

    python scripts/md-table-align.py            # check every tracked *.md, exit 1 on misaligned
    python scripts/md-table-align.py --fix      # rewrite misaligned tables in place
    python scripts/md-table-align.py --fix a.md # only these files
"""
import re
import subprocess
import sys
from pathlib import Path

PIPE = re.compile(r"(?<!\\)\|")
DELIM = re.compile(r"^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$")


def width(s: str) -> int:
    return len(s.encode("utf-16-le")) // 2


def split_cells(row: str):
    body = row.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|") and not body.endswith("\\|"):
        body = body[:-1]
    return [c.strip() for c in PIPE.split(body)]


def is_table_start(lines, i):
    return (
        lines[i].lstrip().startswith("|")
        and i + 1 < len(lines)
        and lines[i + 1].lstrip().startswith("|")
        and DELIM.match(lines[i + 1]) is not None
    )


def format_table(rows):
    indent = re.match(r"^\s*", rows[0]).group(0)
    cells = [split_cells(r) for r in rows]
    columns = max(len(cells[0]), max(len(c) for c in cells))
    cells = [c + [""] * (columns - len(c)) for c in cells]
    delim = cells[1]
    widths = [max(3, max(width(r[i]) for r in cells)) for i in range(columns)]
    out = []
    for ri, row in enumerate(cells):
        parts = []
        for i, cell in enumerate(row):
            if ri == 1:
                d = delim[i]
                left = d.startswith(":")
                right = d.endswith(":")
                dashes = "-" * (widths[i] - left - right)
                parts.append((":" if left else "") + dashes + (":" if right else ""))
            else:
                parts.append(cell + " " * (widths[i] - width(cell)))
        out.append(indent + "| " + " | ".join(parts) + " |")
    return out


def aligned(rows):
    pipes = [[width(r[: m.start()]) for m in PIPE.finditer(r)] for r in rows]
    return all(p == pipes[0] for p in pipes)


def process(path: Path, fix: bool):
    raw = path.read_bytes()
    crlf = b"\r\n" in raw
    text = raw.decode("utf-8").replace("\r\n", "\n")
    lines = text.split("\n")
    bad = []
    i = 0
    in_fence = False
    while i < len(lines):
        stripped = lines[i].strip()
        if stripped.startswith("```") or stripped.startswith("~~~"):
            in_fence = not in_fence
            i += 1
            continue
        if not in_fence and is_table_start(lines, i):
            start = i
            while i < len(lines) and lines[i].lstrip().startswith("|"):
                i += 1
            rows = lines[start:i]
            if not aligned(rows):
                bad.append(start + 1)
                if fix:
                    lines[start:i] = format_table(rows)
            continue
        i += 1
    if fix and bad:
        new = "\n".join(lines)
        if crlf:
            new = new.replace("\n", "\r\n")
        path.write_bytes(new.encode("utf-8"))
    return bad


def main(argv):
    fix = "--fix" in argv
    files = [a for a in argv if not a.startswith("--")]
    if not files:
        # `git ls-files` still lists a tracked file that has been deleted on disk, so a tree in the
        # middle of a rename would crash this gate instead of reporting on it — and a gate that
        # dies on someone else's half-staged move blocks everyone. Skip what is not there.
        files = [f for f in subprocess.check_output(["git", "ls-files", "*.md"], text=True).split()
                 if Path(f).exists()]
    total = 0
    for f in files:
        bad = process(Path(f), fix)
        for line in bad:
            total += 1
            print(f"{f}:{line}: table pipes not aligned{' (fixed)' if fix else ''}")
    print(f"md-table-align: {total} misaligned table(s) in {len(files)} file(s){' — fixed' if fix and total else ''}")
    return 0 if (fix or total == 0) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
