#!/usr/bin/env python3
"""
Licence gate for the MSA 6.6B open-source warranty (SB-2204).

The warranty promises that nothing Sensor Bio ships is under a licence that
would oblige a Customer to disclose the source of their own application, or
that would restrict what they charge for it. This matters more here than in
the backend: customers link this code into their own apps, so they inherit
our transitive licences directly.

Classification is on the DECLARED licence identifier, never on licence body
text. MPL-2.0 names GPL/LGPL/AGPL in its "Secondary License" definition and
GPL-3.0 section 13 references the AGPL, so grepping licence text produces
false copyleft findings on components that are not copyleft at all. This is
the same reasoning as go-tools/licensescan/classify.go in the backend and
scripts/check-licenses.mjs in web_platform.

Fails closed: a licence the policy does not recognise is a finding, not a
pass, so a newly encountered licence has to be classified deliberately below.

Python 3 standard library only, so it runs on a bare runner with no install
step and no toolchain of its own.
"""

from __future__ import annotations

import csv
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request

# --------------------------------------------------------------------------
# Policy
# --------------------------------------------------------------------------

LIC_NONE = "NONE"
LIC_UNKNOWN = "UNKNOWN"

# Licences that fail the gate, with the reason a reviewer needs to see.
FORBIDDEN = {
    "AGPL-1.0": "network copyleft",
    "AGPL-3.0": "network copyleft: section 13 turns remote interaction into a source-disclosure trigger",
    "GPL": "strong copyleft",
    "GPL-2.0": "strong copyleft",
    "GPL-3.0": "strong copyleft",
    "LGPL": "weak copyleft, but relinking obligations we do not want to carry",
    "LGPL-2.0": "weak copyleft, but relinking obligations we do not want to carry",
    "LGPL-2.1": "weak copyleft, but relinking obligations we do not want to carry",
    "LGPL-3.0": "weak copyleft, but relinking obligations we do not want to carry",
    "SSPL": "service-side copyleft",
    "SSPL-1.0": "service-side copyleft",
    "EPL": "reciprocal copyleft on modification",
    "EPL-1.0": "reciprocal copyleft on modification",
    "EPL-2.0": "reciprocal copyleft on modification",
    "CDDL": "file-level copyleft with patent terms we have not reviewed",
    "CDDL-1.0": "file-level copyleft with patent terms we have not reviewed",
    "CDDL-1.1": "file-level copyleft with patent terms we have not reviewed",
    "CC-BY-SA-3.0": "share-alike: derivative works must be relicensed",
    "CC-BY-SA-4.0": "share-alike: derivative works must be relicensed",
    "OSL-3.0": "reciprocal copyleft with a network clause",
    "EUPL-1.2": "reciprocal copyleft, EU equivalent of the AGPL",
    "CPAL-1.0": "reciprocal copyleft with an attribution clause",
    "RPL-1.5": "reciprocal public licence",
    "Sleepycat": "strong copyleft",
    LIC_NONE: "no licence grant at all: we cannot show we have the right to ship it",
    LIC_UNKNOWN: "licence present but unrecognised: needs a human read",
}

# Licences that pass. Anything absent from BOTH maps is a finding, so a newly
# encountered licence must be classified deliberately rather than slipping by.
ALLOWED = {
    "Apache-2.0",
    "MIT",
    "MIT-0",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "0BSD",
    "ISC",
    "Unlicense",
    "CC0-1.0",
    "CC-BY-3.0",
    "CC-BY-4.0",
    "OFL-1.1",
    "Zlib",
    "BlueOak-1.0.0",
    "Python-2.0",
    "WTFPL",
    # Weak, file-scoped copyleft. Triggers only on distributing MODIFIED
    # versions of the dependency's own files and imposes nothing on
    # surrounding code. We consume these unmodified. Same call as the backend
    # (go-tools/licensescan/policy.go); web_platform refuses MPL-2.0 outright
    # because its tree happened to be clean, which is a stricter position than
    # the warranty requires.
    "MPL-2.0",
    # BSD-style with an advertising clause.
    "FTL",
    # First-party code. Not open source, carries no inbound obligation.
    "PROPRIETARY-SENSORBIO",
    # Google's proprietary terms for the Android SDK and the Play Core SDK,
    # declared by every Play Services, Firebase and Play artifact. Not open
    # source and not copyleft: they impose no source-disclosure duty on a
    # Customer Application and no restriction on what a customer charges for
    # it, which is what 6.6B actually warrants. Any Android app that uses
    # Google Play services is subject to them, so refusing them would mean
    # refusing to ship an Android app at all.
    #
    # Allowed on that reasoning rather than because they are permissive
    # licences. Flagged for legal to confirm once, so the position is on the
    # record rather than implied by CI passing (SB-2204).
    "Android-SDK-ToS",
    "Play-Core-ToS",
    # GPL-2.0 carrying the GNU Classpath Exception, as shipped by the Java EE
    # and OpenJDK artifacts. The exception exists precisely to stop the
    # copyleft reaching a work that merely LINKS the library: independent
    # modules can be combined and the result distributed under the linker's own
    # terms. So it does not oblige a customer to disclose their application,
    # which is what 6.6B warrants. Plain GPL-2.0 stays forbidden above.
    "GPL-2.0-with-classpath-exception",
}

# Licences whose text must be reproduced in THIRD_PARTY_NOTICES.md.
ATTRIBUTION_REQUIRED = {
    "Apache-2.0", "MIT", "MIT-0", "BSD-2-Clause", "BSD-3-Clause", "0BSD",
    "ISC", "MPL-2.0", "CC-BY-3.0", "CC-BY-4.0", "FTL", "OFL-1.1", "Zlib",
}

# --------------------------------------------------------------------------
# Normalisation
# --------------------------------------------------------------------------

# Spellings seen in the wild, mapped onto the SPDX identifier we classify on.
# Keys are compared after upper-casing and collapsing whitespace/punctuation.
_ALIASES = {
    "APACHE 2.0": "Apache-2.0",
    "APACHE-2": "Apache-2.0",
    "APACHE2": "Apache-2.0",
    "APACHE LICENSE 2.0": "Apache-2.0",
    "APACHE LICENSE VERSION 2.0": "Apache-2.0",
    "THE APACHE SOFTWARE LICENSE VERSION 2.0": "Apache-2.0",
    "THE APACHE LICENSE VERSION 2.0": "Apache-2.0",
    "MIT LICENSE": "MIT",
    "THE MIT LICENSE": "MIT",
    "MIT LICENCE": "MIT",
    "BSD": "BSD-3-Clause",
    "BSD 3-CLAUSE": "BSD-3-Clause",
    "NEW BSD LICENSE": "BSD-3-Clause",
    "BSD 2-CLAUSE": "BSD-2-Clause",
    "SIMPLIFIED BSD LICENSE": "BSD-2-Clause",
    "ISC LICENSE": "ISC",
    "ZLIB": "Zlib",
    "PUBLIC DOMAIN": "Unlicense",
    "GNU GENERAL PUBLIC LICENSE": "GPL",
    "GNU LESSER GENERAL PUBLIC LICENSE": "LGPL",
    "GNU AFFERO GENERAL PUBLIC LICENSE": "AGPL-3.0",
    "ECLIPSE PUBLIC LICENSE": "EPL",
    "ECLIPSE PUBLIC LICENSE 1.0": "EPL-1.0",
    "ECLIPSE PUBLIC LICENSE 2.0": "EPL-2.0",
    "MOZILLA PUBLIC LICENSE 2.0": "MPL-2.0",
    "MOZILLA PUBLIC LICENSE VERSION 2.0": "MPL-2.0",
    "COMMON DEVELOPMENT AND DISTRIBUTION LICENSE": "CDDL",
    "PROPRIETARY": "PROPRIETARY-SENSORBIO",
    "COPYRIGHT SENSR-BIO": "PROPRIETARY-SENSORBIO",
    # Google's proprietary terms, declared by every Play Services, Firebase and
    # Play artifact. Named rather than left as UNKNOWN so this reads as one
    # classification decision instead of eighteen mystery findings. See the
    # reasoning on the ALLOWED entries above.
    "ANDROID SOFTWARE DEVELOPMENT KIT LICENSE": "Android-SDK-ToS",
    "ANDROID SDK LICENSE": "Android-SDK-ToS",
    "ANDROID SOFTWARE DEVELOPMENT KIT LICENSE AGREEMENT": "Android-SDK-ToS",
    "PLAY CORE SOFTWARE DEVELOPMENT KIT TERMS OF SERVICE": "Play-Core-ToS",
    "PLAY CORE SDK TERMS OF SERVICE": "Play-Core-ToS",
}

# SPDX identifiers we already understand, used to recognise a bare id quickly.
_KNOWN = set(FORBIDDEN) | set(ALLOWED)


def normalise(raw: str | None) -> str:
    """Map a declared licence string onto an identifier the policy knows.

    Returns LIC_NONE when nothing was declared and LIC_UNKNOWN when something
    was declared that we do not recognise. Both are findings, by design.
    """
    if raw is None:
        return LIC_NONE
    text = raw.strip()
    if not text or text.upper() in {"NONE", "NULL", "NOASSERTION", "UNKNOWN", "N/A"}:
        return LIC_NONE

    # An SPDX expression such as "MIT OR Apache-2.0" is a CHOICE. If any branch
    # is allowed we may elect it, so the component passes on the best branch.
    # "AND" is a conjunction: every branch binds, so the worst branch governs.
    #
    # Only split when every branch is a bare, space-free identifier. Licence
    # NAMES contain these words as ordinary English -- "Common Development and
    # Distribution License" is one licence, not a conjunction of two -- and
    # splitting those destroys the name before it can be classified.
    def _is_expression(sep: str) -> list[str] | None:
        parts = re.split(rf"\b{sep}\b", text, flags=re.I)
        if len(parts) < 2:
            return None
        cleaned = [p.strip().strip("()").strip() for p in parts]
        if all(p and re.fullmatch(r"[A-Za-z0-9.+-]+", p) for p in cleaned):
            return cleaned
        return None

    or_parts = _is_expression("OR")
    if or_parts and not re.search(r"\bAND\b", text, re.I):
        branches = [normalise(p) for p in or_parts]
        for b in branches:
            if b in ALLOWED:
                return b
        return branches[0]
    and_parts = _is_expression("AND")
    if and_parts:
        branches = [normalise(p) for p in and_parts]
        for b in branches:
            if b in FORBIDDEN:
                return b
        return branches[0]

    cleaned = text.strip().strip("()").strip()

    # Exact SPDX id, case-insensitively.
    for known in _KNOWN:
        if cleaned.lower() == known.lower():
            return known

    # "-only" / "-or-later" suffixes carry no classification weight.
    stripped = re.sub(r"-(only|or-later)$", "", cleaned, flags=re.I)
    for known in _KNOWN:
        if stripped.lower() == known.lower():
            return known

    key = re.sub(r"[^A-Z0-9. ]+", " ", cleaned.upper())
    key = re.sub(r"\s+", " ", key).strip()
    if key in _ALIASES:
        return _ALIASES[key]

    return _family_match(key)


def _version_in(key: str) -> str:
    """First version-looking number in a licence name, e.g. '2.0' or '3'.

    Matches a glued "GPLV3" as readily as a spaced "VERSION 2.0"; a number
    anywhere in a licence name is its version in every spelling seen so far.
    """
    m = re.search(r"(\d+(?:\.\d+)?)", key)
    return m.group(1) if m else ""


def _spdx(family: str, ver: str, default: str = "") -> str:
    """Join a family and a version into an SPDX id the policy might know."""
    if not ver:
        return default or family
    if "." not in ver:
        ver = f"{ver}.0"
    candidate = f"{family}-{ver}"
    return candidate if candidate in _KNOWN else (default or family)


def _family_match(key: str) -> str:
    """Classify a free-text licence NAME by family.

    Maven POM <licenses><name> and CocoaPods podspec licence fields are free
    text, not SPDX ids: "Eclipse Public License - Version 2.0", "The Apache
    Software License, Version 2.0", "BSD 3-Clause License". This maps those
    onto an SPDX id by looking for the family keyword and a version number.

    This still reads a DECLARED licence name, never licence body text, so the
    MPL/GPL cross-reference problem that defeats body-text scanners does not
    arise: "Mozilla Public License 2.0" contains the word Mozilla, not GPL.
    """
    ver = _version_in(key)

    # Checked before the GPL families: the classpath exception is what decides
    # whether the copyleft reaches a linking application, so a name carrying it
    # must not be classified as plain GPL. Covers the usual spelling,
    # "CDDL + GPLv2 with classpath exception".
    if "CLASSPATH EXCEPTION" in key:
        return "GPL-2.0-with-classpath-exception"

    # Order matters: AGPL and LGPL both contain "GPL", so they go first.
    if "AFFERO" in key or "AGPL" in key:
        return "AGPL-3.0"
    if "LESSER" in key or "LGPL" in key:
        return _spdx("LGPL", ver, "LGPL")
    if "GPL" in key or "GNU GENERAL" in key:
        return _spdx("GPL", ver, "GPL")
    if "SSPL" in key or "SERVER SIDE PUBLIC" in key:
        return "SSPL-1.0"
    if "MOZILLA" in key or re.search(r"\bMPL\b", key):
        return _spdx("MPL", ver or "2.0", "MPL-2.0")
    if "ECLIPSE" in key or re.search(r"\bEPL\b", key):
        return _spdx("EPL", ver, "EPL")
    if "CDDL" in key or "COMMON DEVELOPMENT AND DISTRIBUTION" in key:
        return _spdx("CDDL", ver, "CDDL")
    if "EUPL" in key:
        return "EUPL-1.2"
    if "SLEEPYCAT" in key:
        return "Sleepycat"

    if "APACHE" in key:
        # Apache-1.0/1.1 are materially different and not on the allowed list;
        # leave them UNKNOWN so a human classifies them.
        return "Apache-2.0" if ver in ("2.0", "2") else LIC_UNKNOWN
    if re.search(r"\bBSD\b", key):
        if "2" in ver or "2 CLAUSE" in key or "SIMPLIFIED" in key:
            return "BSD-2-Clause"
        if ver == "0" or "0BSD" in key:
            return "0BSD"
        return "BSD-3-Clause"
    if re.search(r"\bMIT\b", key):
        return "MIT"
    if re.search(r"\bISC\b", key):
        return "ISC"
    if "ZLIB" in key:
        return "Zlib"
    if "CC0" in key:
        return "CC0-1.0"
    if "CC BY SA" in key or "ATTRIBUTION SHARE" in key:
        return _spdx("CC-BY-SA", ver, "CC-BY-SA-4.0")
    if "CC BY" in key or "CREATIVE COMMONS ATTRIBUTION" in key:
        return _spdx("CC-BY", ver, "CC-BY-4.0")
    if "OPEN FONT" in key or re.search(r"\bOFL\b", key):
        return "OFL-1.1"
    if "UNLICENSE" in key or "PUBLIC DOMAIN" in key:
        return "Unlicense"
    if "WTFPL" in key:
        return "WTFPL"
    if "PROPRIETARY" in key or "ALL RIGHTS RESERVED" in key or "SENSR" in key:
        return "PROPRIETARY-SENSORBIO"

    return LIC_UNKNOWN


# --------------------------------------------------------------------------
# Components and verdicts
# --------------------------------------------------------------------------

# Whether a component reaches a customer. The warranty is about what we SHIP:
# a test-only or annotation-processor dependency is never linked into the
# Customer Application, so its licence imposes nothing on them. Those are
# still inventoried -- an auditor asks what is in the tree -- but they do not
# fail the gate.
SHIPPED = "shipped"
BUILD_ONLY = "build-only"
UNREFERENCED = "unreferenced"


class Component:
    __slots__ = ("name", "version", "declared", "source", "url", "scope")

    def __init__(self, name, version=None, declared=None, source="", url="",
                 scope=SHIPPED):
        self.name = name
        self.version = version or ""
        self.declared = declared
        self.source = source
        self.url = url
        self.scope = scope

    @property
    def licence(self) -> str:
        """The licence this component is taken under.

        A recorded exception may ELECT a licence -- a dual-licensed dependency
        where we take the permissive branch, or one whose registry metadata
        omits a licence its source plainly states. The election replaces the
        declared value so the component register shows what we actually rely
        on rather than what the registry happened to publish.
        """
        elected = (EXCEPTIONS.get(self.name) or {}).get("license")
        if elected:
            return normalise(elected)
        return normalise(self.declared)

    def as_row(self):
        return {
            "component": self.name,
            "version": self.version,
            "declared": (self.declared or "").strip(),
            "classified": self.licence,
            "verdict": verdict(self),
            "scope": self.scope,
            "ecosystem": self.source,
            "url": self.url,
        }


def verdict(c: "Component") -> str:
    lic = c.licence
    if c.name in EXCEPTIONS:
        # An exception may only elect a licence the policy already allows. It
        # records a decision about WHICH licence applies; it is not a way to
        # wave copyleft through, so an election that lands on a forbidden or
        # unrecognised licence still fails.
        return "allowed (exception)" if lic in ALLOWED else "FORBIDDEN"
    if c.scope != SHIPPED:
        # Inventoried, not gated: it never reaches the Customer Application.
        return f"not shipped ({c.scope})" if lic in FORBIDDEN or lic not in ALLOWED else "allowed"
    if lic in FORBIDDEN:
        return "FORBIDDEN"
    if lic in ALLOWED:
        return "allowed"
    return "FORBIDDEN"


# Deliberate, documented overrides of the classifier for a specific component.
# Every entry needs a reason a reviewer can check. Keyed by component name.
EXCEPTIONS: dict[str, dict] = {}


def load_exceptions(path: str) -> None:
    """Load per-repo exceptions from a JSON file, if present.

    Each entry is {"license": <elected SPDX id>, "reason": <why>, "source":
    <where that was verified>}. `license` is optional; without it the entry
    only vouches for the declared value.
    """
    if not path or not os.path.exists(path):
        return
    with open(path, encoding="utf-8") as fh:
        data = json.load(fh)
    for name, entry in (data.get("exceptions") or {}).items():
        if not isinstance(entry, dict):
            entry = {"reason": str(entry)}
        if not entry.get("reason"):
            raise SystemExit(
                f"exception for {name!r} has no reason; every override needs one "
                f"a reviewer can check")
        EXCEPTIONS[name] = entry


# --------------------------------------------------------------------------
# HTTP, shared by the collectors
# --------------------------------------------------------------------------

_UA = "sensorbio-licence-gate/1.0 (+SB-2204)"

# Retries matter once the gate is blocking. A registry answering "404, no such
# artifact" is a real answer and is not retried; a timeout or a 5xx is not an
# answer at all, and without retries it would degrade to "no licence found",
# which reads as a finding and fails the build for a reason that has nothing to
# do with licensing. A gate that goes red at random gets switched off, so this
# separates "the registry said no" from "we could not ask".
_RETRIES = 3
_BACKOFF = 1.5

# Set when a lookup exhausted its retries, so the caller can tell the
# difference between a definitive answer and an unreachable registry.
NETWORK_FAILURES: list[str] = []


def _fetch(url: str, token: str | None, timeout: int, ua: str | None,
           accept: str | None) -> str | None:
    headers = {"User-Agent": ua or _UA}
    if accept:
        headers["Accept"] = accept
    req = urllib.request.Request(url, headers=headers)
    if token:
        req.add_header("Authorization", f"Bearer {token}")

    for attempt in range(_RETRIES):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as exc:
            # 404/410 are definitive: the artifact is not there. 429 and 5xx
            # are transient and worth another go.
            if exc.code in (404, 410):
                return None
            if exc.code not in (429, 500, 502, 503, 504) or attempt == _RETRIES - 1:
                if exc.code not in (404, 410):
                    NETWORK_FAILURES.append(f"{url} (HTTP {exc.code})")
                return None
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            if attempt == _RETRIES - 1:
                NETWORK_FAILURES.append(f"{url} ({type(exc).__name__})")
                return None
        time.sleep(_BACKOFF * (2 ** attempt))
    return None


def http_json(url: str, token: str | None = None, timeout: int = 30,
              ua: str | None = None):
    """GET a JSON document, or None if it is genuinely absent or unreachable.

    The collectors treat None as 'licence not determined', which normalises to
    NONE and surfaces as a finding rather than a silent pass. Unreachable
    registries are additionally recorded in NETWORK_FAILURES so a blocking run
    can refuse to draw conclusions from data it never received.
    """
    body = _fetch(url, token, timeout, ua, "application/json")
    if body is None:
        return None
    try:
        return json.loads(body)
    except ValueError:
        return None


def http_text(url: str, token: str | None = None, timeout: int = 30,
              ua: str | None = None) -> str | None:
    return _fetch(url, token, timeout, ua, None)


def github_license(owner: str, repo: str, token: str | None) -> str | None:
    """Declared licence of a GitHub repo, as an SPDX id, via the licenses API."""
    data = http_json(f"https://api.github.com/repos/{owner}/{repo}/license", token)
    if not data:
        return None
    spdx = (data.get("license") or {}).get("spdx_id")
    if spdx in (None, "NOASSERTION"):
        return None
    return spdx


# --------------------------------------------------------------------------
# Reporting
# --------------------------------------------------------------------------

def report(components: list[Component], repo_name: str, advisory: bool,
           json_out: bool = False, csv_path: str | None = None) -> int:
    fields = ["component", "version", "declared", "classified", "verdict",
              "scope", "ecosystem", "url"]
    components = sorted(components, key=lambda c: (c.source, c.name.lower()))
    rows = [c.as_row() for c in components]
    bad = [r for r in rows if r["verdict"] == "FORBIDDEN"]
    shipped = [r for r in rows if r["scope"] == SHIPPED]

    if csv_path:
        with open(csv_path, "w", newline="", encoding="utf-8") as fh:
            w = csv.DictWriter(fh, fieldnames=fields)
            w.writeheader()
            w.writerows(rows)

    if json_out:
        json.dump({"repo": repo_name, "advisory": advisory, "total": len(rows),
                   "shipped": len(shipped), "violations": len(bad),
                   "components": rows}, sys.stdout, indent=2)
        sys.stdout.write("\n")
    else:
        print(f"Licence gate (MSA 6.6B) - {repo_name}")
        print(f"  {len(rows)} component(s) inspected, {len(shipped)} of them shipped\n")
        for r in rows:
            if r["verdict"] == "FORBIDDEN":
                mark = "FAIL"
            elif r["scope"] != SHIPPED:
                mark = "  - "
            else:
                mark = " ok "
            ver = f" {r['version']}" if r["version"] else ""
            declared = r["declared"]
            suffix = "" if declared in ("", r["classified"]) else f"  (declared: {declared})"
            print(f"  [{mark}] {r['component']}{ver}  ->  {r['classified']}{suffix}")

        if bad:
            print(f"\n  {len(bad)} finding(s) in shipped components:")
            for r in bad:
                why = FORBIDDEN.get(r["classified"], "not on the allowed list, so unclassified")
                print(f"    - {r['component']} {r['version']}: {r['classified']} - {why}")
                if r["url"]:
                    print(f"        {r['url']}")

    # A registry we could not reach is not evidence of anything. In blocking
    # mode, drawing a conclusion from data that never arrived would either fail
    # someone's PR over a network blip or, worse, pass a component whose licence
    # we simply did not read.
    if NETWORK_FAILURES:
        uniq = sorted(set(NETWORK_FAILURES))
        print(f"\n{len(uniq)} lookup(s) could not be completed after retries:",
              file=sys.stderr)
        for u in uniq[:10]:
            print(f"    {u}", file=sys.stderr)
        if len(uniq) > 10:
            print(f"    ... and {len(uniq) - 10} more", file=sys.stderr)
        if not advisory:
            print("Refusing to report a verdict from incomplete data. "
                  "Re-run; if it persists, the registry or the network is the problem.",
                  file=sys.stderr)
            return 2

    if not bad:
        print("\nNo forbidden or unclassified licences.", file=sys.stderr)
        return 0

    if advisory:
        print(f"\nADVISORY: {len(bad)} finding(s). Not failing the build "
              f"(SB-2204 rollout: advisory first, blocking once the backlog clears).",
              file=sys.stderr)
        return 0
    print(f"\n{len(bad)} finding(s). Failing the build.", file=sys.stderr)
    return 1
