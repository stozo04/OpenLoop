#!/usr/bin/env python3
"""Turn an Android Studio "Inspect Code" HTML export into a pass/fail gate over TRACKED files.

Why: the IDE inspects whatever is on disk under the project — git worktrees under
`.claude/worktrees/`, gitignored vendor bundles, `docs/local/`, message-bus logs — and a single
export ran to 82,752 items of which 98% were copies of the repo. The only findings that can be
fixed in a PR are the ones in files git tracks, so that is the population this gate counts.

    python scripts/inspect-report.py build/inspect-export/index.html
    python scripts/inspect-report.py build/inspect-export/index.html --tsv build/inspect-problems.tsv

Exit 0 when no hard findings remain in tracked files; exit 1 otherwise. Advisory inspections
(version-freshness checks that self-invalidate whenever upstream publishes, and the Play Policy
insight whose "justification" lives in Play Console, not in code) are listed but never fail the
gate — see docs/STATIC_ANALYSIS.md.
"""
import html
import re
import subprocess
import sys
from collections import Counter, defaultdict
from pathlib import Path

ADVISORY = {
    "Obsolete Android Gradle Plugin Version",
    "Obsolete Gradle Dependency",
    "Latest Gradle minor version",
    "Newer Library Versions Available",
    "Foreground Services Insights",
}

INSPECTION = re.compile(r"<b>([^<]*)</b>&nbsp;inspection&nbsp;")
PROBLEM = re.compile(r'<li>(.*?)\s*\(at line <a href="file://([^"#]*)#\d*">(\d+)</a>\)')


def main(argv):
    if not argv:
        print(__doc__)
        return 2
    export = Path(argv[0])
    tsv = Path(argv[argv.index("--tsv") + 1]) if "--tsv" in argv else None
    root = Path(subprocess.check_output(["git", "rev-parse", "--show-toplevel"], text=True).strip())
    tracked = set(subprocess.check_output(["git", "ls-files"], text=True).split("\n"))
    root_uri = root.as_posix().rstrip("/") + "/"

    seen = set()
    hard = defaultdict(list)
    advisory = defaultdict(list)
    skipped = Counter()
    cur = None
    with export.open("r", encoding="utf-8", errors="replace") as f:
        for line in f:
            m = INSPECTION.search(line)
            if m:
                cur = html.unescape(m.group(1).replace("&nbsp;", " "))
                continue
            for m in PROBLEM.finditer(line):
                path = m.group(2)
                if not path.startswith(root_uri):
                    skipped["outside repo"] += 1
                    continue
                rel = path[len(root_uri):]
                if rel not in tracked:
                    skipped["untracked/ignored"] += 1
                    continue
                msg = html.unescape(re.sub(r"<[^>]+>", "", m.group(1))).replace("\xa0", " ").strip()
                key = (cur, rel, m.group(3), msg)
                if key in seen:
                    continue
                seen.add(key)
                (advisory if cur in ADVISORY else hard)[cur].append((rel, int(m.group(3)), msg))

    if tsv:
        tsv.parent.mkdir(parents=True, exist_ok=True)
        with tsv.open("w", encoding="utf-8") as out:
            for bucket, name in ((hard, "HARD"), (advisory, "ADVISORY")):
                for insp, items in sorted(bucket.items()):
                    for rel, ln, msg in sorted(items):
                        out.write(f"{name}\t{insp}\t{rel}\t{ln}\t{msg}\n")

    print(f"inspect-report: {export} — skipped {dict(skipped)} (not tracked by git)")
    for insp, items in sorted(hard.items(), key=lambda kv: -len(kv[1])):
        print(f"  HARD     {len(items):5d}  {insp}")
        for rel, ln, msg in sorted(items)[:8]:
            print(f"             {rel}:{ln}  {msg[:110]}")
        if len(items) > 8:
            print(f"             … {len(items) - 8} more (see --tsv)")
    for insp, items in sorted(advisory.items()):
        print(f"  advisory {len(items):5d}  {insp}")
    n_hard = sum(len(v) for v in hard.values())
    print(f"inspect-report: {n_hard} hard finding(s) in tracked files, "
          f"{sum(len(v) for v in advisory.values())} advisory")
    return 1 if n_hard else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
