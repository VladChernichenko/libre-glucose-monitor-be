#!/usr/bin/env python3
"""Fail if any text source/doc under the repo is not valid UTF-8.

Usage:
  ./scripts/check-utf8.py [path ...]
Default: scan workspace root (this script's ../).
"""

from __future__ import annotations

import sys
from pathlib import Path

SKIP_DIRS = {
    ".git",
    "node_modules",
    "DerivedData",
    "build",
    ".gradle",
    "target",
    "Pods",
    ".build",
    "dist",
    "coverage",
    "__pycache__",
    ".idea",
    "xcuserdata",
    "runs",
    "datasets",
    "physionet.org",
    "CGMacros",
    "best.mlpackage",
    "yolo11n-seg.mlpackage",
}

TEXT_SUFFIXES = {
    ".swift",
    ".java",
    ".kt",
    ".kts",
    ".ts",
    ".tsx",
    ".js",
    ".jsx",
    ".mjs",
    ".cjs",
    ".py",
    ".rb",
    ".go",
    ".rs",
    ".c",
    ".h",
    ".cpp",
    ".hpp",
    ".m",
    ".mm",
    ".yml",
    ".yaml",
    ".json",
    ".toml",
    ".xml",
    ".plist",
    ".gradle",
    ".properties",
    ".md",
    ".txt",
    ".sql",
    ".sh",
    ".bash",
    ".zsh",
    ".html",
    ".css",
    ".scss",
    ".pbxproj",
    ".xcconfig",
    ".entitlements",
    ".gitignore",
    ".gitattributes",
    ".csv",
    ".svg",
    ".graphql",
    ".proto",
    ".editorconfig",
}

SPECIAL_NAMES = {
    "Dockerfile",
    "Makefile",
    "LICENSE",
    "Jenkinsfile",
    "Procfile",
    "Gemfile",
}


def should_check(path: Path) -> bool:
    if any(p in SKIP_DIRS for p in path.parts):
        return False
    if path.name in SPECIAL_NAMES:
        return True
    return path.suffix.lower() in TEXT_SUFFIXES


def main(argv: list[str]) -> int:
    roots = [Path(a) for a in argv[1:]] or [Path(__file__).resolve().parent.parent]
    bad: list[tuple[str, str]] = []
    checked = 0
    for root in roots:
        root = root.resolve()
        paths = [root] if root.is_file() else root.rglob("*")
        for path in paths:
            if not path.is_file() or not should_check(path):
                continue
            try:
                data = path.read_bytes()
            except OSError:
                continue
            if b"\x00" in data[:4096]:
                continue
            checked += 1
            try:
                data.decode("utf-8")
            except UnicodeDecodeError as e:
                bad.append((str(path), f"byte {e.start}: {e.reason}"))

    if bad:
        print(f"UTF-8 check failed ({len(bad)}/{checked} files):", file=sys.stderr)
        for p, reason in bad:
            print(f"  {p}: {reason}", file=sys.stderr)
        return 1
    print(f"UTF-8 check OK ({checked} files)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
