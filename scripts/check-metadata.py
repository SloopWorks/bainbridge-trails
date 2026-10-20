#!/usr/bin/env python3
"""Validate store listing text in metadata/ against Play Console / App Store Connect limits.

Usage: scripts/check-metadata.py [metadata_dir]   (default: metadata/ next to this script's parent)
Exit 0 when every file exists, is non-empty and within its limit; 1 otherwise.
Lengths count Unicode characters with the trailing newline stripped (what the consoles count).
"""
import pathlib
import re
import sys

# (glob relative to metadata/, max chars or None for no limit)
LIMITS = [
    ("android/{loc}/title.txt", 30),
    ("android/{loc}/short_description.txt", 80),
    ("android/{loc}/full_description.txt", 4000),
    ("android/{loc}/changelogs/*.txt", 500),
    ("ios/{loc}/name.txt", 30),
    ("ios/{loc}/subtitle.txt", 30),
    ("ios/{loc}/description.txt", 4000),
    ("ios/{loc}/keywords.txt", 100),
    ("ios/{loc}/promotional_text.txt", 170),
    ("ios/{loc}/release_notes.txt", 4000),
    ("ios/{loc}/privacy_url.txt", None),
    ("ios/{loc}/support_url.txt", None),
]


def text(p: pathlib.Path) -> str:
    return p.read_text(encoding="utf-8").rstrip("\n")


def main() -> int:
    root = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else pathlib.Path(__file__).resolve().parent.parent / "metadata"
    locales = sorted({p.name for platform in ("android", "ios") for p in (root / platform).glob("*") if p.is_dir()})
    errors: list[str] = []
    for loc in locales:
        for pattern, limit in LIMITS:
            rel = pattern.format(loc=loc)
            files = sorted(root.glob(rel))
            if not files:
                errors.append(f"{rel}: missing")
                continue
            for f in files:
                t = text(f)
                name = f.relative_to(root)
                if not t.strip():
                    errors.append(f"{name}: empty")
                elif limit is not None and len(t) > limit:
                    errors.append(f"{name}: {len(t)} chars > {limit}")
                if f.name.endswith("_url.txt") and not re.fullmatch(r"https://\S+", t):
                    errors.append(f"{name}: not a single https URL")
                if f.name == "keywords.txt" and re.search(r",\s", t):
                    errors.append(f"{name}: no spaces after commas (they count toward the 100)")
                print(f"{'ok ' if limit is None or len(t) <= limit else 'BAD'} {str(name):48} {len(t):5}" + (f" / {limit}" if limit else ""))
    for e in errors:
        print(f"error: {e}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
