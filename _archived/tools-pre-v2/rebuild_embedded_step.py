#!/usr/bin/env python3
"""Rebuild one deterministic gzip/base64 payload in CoreAlign.groovy."""

from __future__ import annotations

import base64
import gzip
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "workflow" / "CoreAlign.groovy"
SOURCE = ROOT / "workflow" / "embedded" / "01_build_tma_grid.groovy.src"


def main() -> None:
    runner = RUNNER.read_text(encoding="utf-8")
    source = SOURCE.read_bytes()
    payload = base64.encodebytes(gzip.compress(source, mtime=0)).decode("ascii").rstrip()
    pattern = re.compile(
        r"(def step1 = new EmbeddedWorkflowScript\(name: '01_build_tma_grid\.groovy', payload: '''\n)"
        r".*?"
        r"(\n'''\))",
        re.DOTALL,
    )
    updated, replacements = pattern.subn(
        lambda match: match.group(1) + payload + match.group(2), runner, count=1
    )
    if replacements != 1:
        raise SystemExit("Could not locate the embedded Step 1 payload")
    RUNNER.write_text(updated, encoding="utf-8")


if __name__ == "__main__":
    main()
