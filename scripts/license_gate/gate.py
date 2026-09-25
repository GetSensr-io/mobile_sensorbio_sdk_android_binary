#!/usr/bin/env python3
"""
Licence gate entry point for the Android repos (SB-2204).

    python3 scripts/license_gate/gate.py                 # human-readable
    python3 scripts/license_gate/gate.py --json          # machine-readable
    python3 scripts/license_gate/gate.py --csv out.csv   # component register
    python3 scripts/license_gate/gate.py --blocking      # fail on a finding

Advisory by default, per the SB-2204 rollout: report findings without failing
the build until the existing backlog is cleared, then flip CI to --blocking.
"""

from __future__ import annotations

import argparse
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import policy  # noqa: E402
from collect_gradle import collect  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def main() -> int:
    ap = argparse.ArgumentParser(description="MSA 6.6B licence gate (Gradle)")
    ap.add_argument("--catalog", default=os.path.join(REPO_ROOT, "gradle", "libs.versions.toml"))
    ap.add_argument("--json", action="store_true", help="machine-readable output")
    ap.add_argument("--csv", metavar="PATH", help="write the component register")
    ap.add_argument("--blocking", action="store_true",
                    help="exit non-zero on a finding (default: advisory)")
    ap.add_argument("--no-transitive", action="store_true",
                    help="only the coordinates declared in the catalogue")
    args = ap.parse_args()

    # A missing catalogue is not an error: plenty of modules name coordinates
    # inline instead, and the collector reads those too. Finding NEITHER is the
    # error, because it means the gate inspected nothing and would otherwise
    # report a clean tree it never actually looked at.
    if not os.path.exists(args.catalog):
        print(f"no version catalogue at {args.catalog}; "
              f"reading coordinates from the build scripts instead", file=sys.stderr)

    policy.load_exceptions(os.path.join(HERE, "exceptions.json"))
    components = collect(REPO_ROOT, args.catalog, transitive=not args.no_transitive)
    if not components:
        print("no dependencies found: no version catalogue and no inline "
              "coordinates in any build script. If that is wrong, the parser "
              "needs updating rather than the gate passing.", file=sys.stderr)
        return 2
    return policy.report(
        components,
        repo_name=os.path.basename(REPO_ROOT),
        advisory=not args.blocking,
        json_out=args.json,
        csv_path=args.csv,
    )


if __name__ == "__main__":
    raise SystemExit(main())
