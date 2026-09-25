#!/usr/bin/env python3
"""
Gradle collector for the licence gate (SB-2204).

CI-only by design: this reads gradle/libs.versions.toml and resolves each
coordinate's POM straight from the repositories, so it needs no Gradle run,
no Android SDK, and no credentials for the private Maven mirrors. That keeps
the gate entirely outside the build of a shipping SDK -- applying a licence
plugin inside build.gradle.kts would put our CI tooling into the dependency
graph that customers link against.

The trade-off is that this sees the DECLARED dependency set plus whatever
transitive closure the POMs expose, not Gradle's fully resolved graph with
conflict resolution applied. For the advisory phase that is the right
trade: it catches a forbidden licence entering the catalogue, which is how
one would actually arrive.
"""

from __future__ import annotations

import os
import re
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

from policy import BUILD_ONLY, SHIPPED, UNREFERENCED, Component, http_text

# Gradle configurations whose contents are linked into the shipped artifact.
# Everything else -- tests, annotation processors, compile-only stubs, debug
# variants -- is inventoried but not gated, because it never reaches a
# Customer Application and so imposes nothing under MSA 6.6B.
SHIPPING_CONFIGS = {
    "implementation", "api", "runtimeOnly", "releaseImplementation",
    "releaseApi", "releaseRuntimeOnly",
}

# Repositories are tried in order. These mirror the settings.gradle.kts
# repository list, minus the private mirrors that need credentials -- a
# coordinate only served privately resolves to "no POM found", which the
# policy treats as a finding rather than a pass.
REPOS = [
    "https://dl.google.com/android/maven2",
    "https://repo1.maven.org/maven2",
    "https://jitpack.io",
    "https://oss.sonatype.org/content/repositories/snapshots",
]

_POM_NS = "{http://maven.apache.org/POM/4.0.0}"
_MAX_DEPTH = 2  # catalogue entry -> its deps -> theirs. Deeper adds noise.


# --------------------------------------------------------------------------
# Version catalogue
# --------------------------------------------------------------------------

def _strip_comment(line: str) -> str:
    """Drop a trailing # comment that is not inside a quoted string."""
    out, quote = [], None
    for ch in line:
        if quote:
            out.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in "\"'":
            quote = ch
            out.append(ch)
            continue
        if ch == "#":
            break
        out.append(ch)
    return "".join(out)


def parse_catalog(path: str) -> list[tuple[str, str, str, str]]:
    """Return (alias, group, artifact, version) for every catalogue library.

    Entries whose version is supplied by a BOM carry an empty version; the
    caller resolves those against the repository metadata.
    """
    versions: dict[str, str] = {}
    libraries: list[tuple[str, str, str, str]] = []
    section = None

    with open(path, encoding="utf-8") as fh:
        raw_lines = fh.readlines()

    for raw in raw_lines:
        line = _strip_comment(raw).strip()
        if not line:
            continue
        if line.startswith("[") and line.endswith("]"):
            section = line[1:-1]
            continue

        if section == "versions":
            m = re.match(r'^([\w.-]+)\s*=\s*"([^"]*)"', line)
            if m:
                versions[m.group(1)] = m.group(2)
            continue

        if section == "libraries":
            m = re.match(r"^([\w.-]+)\s*=\s*(.+)$", line)
            if not m:
                continue
            alias, body = m.group(1), m.group(2)

            mod = re.search(r'module\s*=\s*"([^"]+)"', body)
            if mod and ":" in mod.group(1):
                group, artifact = mod.group(1).split(":", 1)
            else:
                g = re.search(r'group\s*=\s*"([^"]+)"', body)
                a = re.search(r'name\s*=\s*"([^"]+)"', body)
                if not (g and a):
                    continue
                group, artifact = g.group(1), a.group(1)

            ref = re.search(r'version\.ref\s*=\s*"([^"]+)"', body)
            lit = re.search(r'version\s*=\s*"([^"]+)"', body)
            if ref:
                version = versions.get(ref.group(1), "")
            elif lit:
                version = lit.group(1)
            else:
                version = ""  # BOM-managed
            libraries.append((alias, group, artifact, version))

    # Deduplicate on coordinate, preserving order.
    seen, out = set(), []
    for item in libraries:
        if item[1:3] not in seen:
            seen.add(item[1:3])
            out.append(item)
    return out


# --------------------------------------------------------------------------
# Dependency scope, read from the build files
# --------------------------------------------------------------------------

def _alias_to_accessor(alias: str) -> str:
    """Catalogue key -> the accessor a build file uses.

    Gradle exposes `paho-mqtt` as `libs.paho.mqtt`: both '-' and '_' become
    '.' in the generated accessor.
    """
    return "libs." + alias.replace("-", ".").replace("_", ".")


def parse_literal_deps(repo_root: str) -> list[tuple[str, str, str, str]]:
    """Return (group, artifact, version, configuration) for inline coordinates.

    Not every module uses a version catalogue. Where there is none, the build
    script names the coordinate directly:

        implementation("com.google.code.gson:gson:2.14.0")

    Reading those is the difference between gating such a repo and inventing a
    catalogue for it. The configuration is captured from the same line, so
    scope needs no second pass the way catalogue aliases do.
    """
    decl = re.compile(
        r"""(?P<config>[A-Za-z][A-Za-z0-9]*)\s*[\(\s]\s*["']"""
        r"""(?P<group>[A-Za-z0-9_.\-]+):(?P<artifact>[A-Za-z0-9_.\-]+):(?P<version>[A-Za-z0-9_.\-+]+)["']"""
    )
    out: list[tuple[str, str, str, str]] = []
    seen: set[tuple[str, str]] = set()

    for dirpath, dirnames, filenames in os.walk(repo_root):
        dirnames[:] = [
            d for d in dirnames
            if d not in {".git", ".claude", "build", "buildSrc", "node_modules", ".gradle"}
        ]
        for fn in filenames:
            if fn not in ("build.gradle.kts", "build.gradle"):
                continue
            try:
                with open(os.path.join(dirpath, fn), encoding="utf-8", errors="replace") as fh:
                    body = fh.read()
            except OSError:
                continue
            body = re.sub(r"//[^\n]*", "", body)
            body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
            for m in decl.finditer(body):
                # A version that is still a Gradle interpolation cannot be
                # resolved from the text alone; report it rather than guess.
                version = m.group("version")
                if version.startswith("$"):
                    version = ""
                key = (m.group("group"), m.group("artifact"))
                if key in seen:
                    continue
                seen.add(key)
                out.append((key[0], key[1], version, m.group("config")))
    return out


def scan_scopes(repo_root: str, aliases: list[str]) -> dict[str, str]:
    """Map each catalogue alias to SHIPPED / BUILD_ONLY / UNREFERENCED.

    Reads the configuration each `libs.x` accessor is declared under across
    every build script in the repo. buildSrc is excluded: it builds the build,
    and nothing in it is packaged into the artifact a customer receives.
    """
    accessor_of = {a: _alias_to_accessor(a) for a in aliases}
    configs: dict[str, set[str]] = {a: set() for a in aliases}

    scripts = []
    for dirpath, dirnames, filenames in os.walk(repo_root):
        dirnames[:] = [
            d for d in dirnames
            if d not in {".git", ".claude", "build", "buildSrc", "node_modules", ".gradle"}
        ]
        for fn in filenames:
            if fn in ("build.gradle.kts", "build.gradle"):
                scripts.append(os.path.join(dirpath, fn))

    decl = re.compile(
        r"(?P<config>[A-Za-z][A-Za-z0-9]*)\s*\(\s*(?P<accessor>libs(?:\.[A-Za-z0-9]+)+)"
    )
    for path in scripts:
        try:
            with open(path, encoding="utf-8", errors="replace") as fh:
                body = fh.read()
        except OSError:
            continue
        # Strip line comments so a commented-out dependency is not counted.
        body = re.sub(r"//[^\n]*", "", body)
        body = re.sub(r"/\*.*?\*/", "", body, flags=re.S)
        for m in decl.finditer(body):
            accessor, config = m.group("accessor"), m.group("config")
            for alias, acc in accessor_of.items():
                if accessor == acc:
                    configs[alias].add(config)

    scopes = {}
    for alias, found in configs.items():
        if not found:
            scopes[alias] = UNREFERENCED
        elif found & SHIPPING_CONFIGS:
            scopes[alias] = SHIPPED
        else:
            scopes[alias] = BUILD_ONLY
    return scopes


# --------------------------------------------------------------------------
# POM resolution
# --------------------------------------------------------------------------

def clean_version(raw: str) -> str:
    """Reduce a Maven version SPEC to a single fetchable version.

    POMs may pin with range syntax: "[2.8.7]" means exactly 2.8.7, and a true
    range like "[1.0,2.0)" means a resolver picks one. Left as-is these build
    a URL that 404s, and the component would then be reported as having no
    licence at all -- a false finding on a perfectly ordinary library.
    Returns "" for a real range, which tells the caller to resolve it against
    the repository metadata instead.
    """
    v = (raw or "").strip()
    m = re.fullmatch(r"\[\s*([^,\[\]()\s]+)\s*\]", v)
    if m:
        return m.group(1)
    if v[:1] in ("[", "("):
        return ""
    return v


def _pom_url(repo: str, group: str, artifact: str, version: str) -> str:
    return f"{repo}/{group.replace('.', '/')}/{artifact}/{version}/{artifact}-{version}.pom"


def _latest_release(group: str, artifact: str) -> str:
    """Latest release version from maven-metadata.xml, for BOM-managed entries."""
    for repo in REPOS:
        url = f"{repo}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
        body = http_text(url)
        if not body:
            continue
        try:
            root = ET.fromstring(body)
        except ET.ParseError:
            continue
        for tag in ("release", "latest"):
            node = root.find(f"./versioning/{tag}")
            if node is not None and node.text:
                return node.text.strip()
        versions = [v.text for v in root.findall("./versioning/versions/version") if v.text]
        if versions:
            return versions[-1]
    return ""


def fetch_pom(group: str, artifact: str, version: str) -> tuple[str | None, str]:
    """Return (pom_xml, resolved_url). Tries each repository in turn."""
    for repo in REPOS:
        url = _pom_url(repo, group, artifact, version)
        body = http_text(url)
        if body and body.lstrip().startswith("<"):
            return body, url
    return None, ""


def _text(node) -> str:
    return (node.text or "").strip() if node is not None else ""


def pom_licences(pom: str) -> tuple[list[str], tuple[str, str, str] | None,
                                    list[tuple[str, str, str]]]:
    """Extract (licence names, parent coordinate, compile/runtime deps) from a POM."""
    try:
        root = ET.fromstring(pom)
    except ET.ParseError:
        return [], None, []

    def find(parent, tag):
        return parent.find(f"{_POM_NS}{tag}") if parent is not None else None

    names = []
    lic_block = find(root, "licenses")
    if lic_block is not None:
        for lic in lic_block.findall(f"{_POM_NS}license"):
            name = _text(find(lic, "name")) or _text(find(lic, "url"))
            if name:
                names.append(name)

    parent = None
    p = find(root, "parent")
    if p is not None:
        parent = (_text(find(p, "groupId")), _text(find(p, "artifactId")),
                  _text(find(p, "version")))
        if not all(parent):
            parent = None

    deps = []
    props = {}
    prop_block = find(root, "properties")
    if prop_block is not None:
        for child in prop_block:
            props[child.tag.replace(_POM_NS, "")] = (child.text or "").strip()

    dep_block = find(root, "dependencies")
    if dep_block is not None:
        for d in dep_block.findall(f"{_POM_NS}dependency"):
            scope = _text(find(d, "scope")) or "compile"
            optional = _text(find(d, "optional")).lower() == "true"
            if scope not in ("compile", "runtime") or optional:
                continue
            g, a, v = _text(find(d, "groupId")), _text(find(d, "artifactId")), _text(find(d, "version"))
            # Resolve ${...} against this POM's own properties only.
            pm = re.fullmatch(r"\$\{([^}]+)\}", v or "")
            if pm:
                v = props.get(pm.group(1), "")
            if g and a and v and not v.startswith("$"):
                deps.append((g, a, clean_version(v)))
    return names, parent, deps


def resolve_licence(group: str, artifact: str, version: str,
                    depth: int = 0) -> tuple[str | None, str, list[tuple[str, str, str]]]:
    """Declared licence for a coordinate, following parent POMs when absent."""
    pom, url = fetch_pom(group, artifact, version)
    if not pom:
        return None, "", []
    names, parent, deps = pom_licences(pom)
    if names:
        return " AND ".join(names) if len(names) > 1 else names[0], url, deps
    # No <licenses> here: Maven inherits it from the parent POM.
    if parent and depth < 4:
        inherited, _, _ = resolve_licence(parent[0], parent[1], parent[2], depth + 1)
        if inherited:
            return inherited, url, deps
    return None, url, deps


# --------------------------------------------------------------------------
# Entry point
# --------------------------------------------------------------------------

def collect(repo_root: str, catalog_path: str, transitive: bool = True) -> list[Component]:
    roots = parse_catalog(catalog_path) if os.path.exists(catalog_path) else []
    scopes = scan_scopes(repo_root, [alias for alias, _, _, _ in roots])

    resolved: dict[tuple[str, str], str] = {}
    scope_of: dict[tuple[str, str], str] = {}
    for alias, group, artifact, version in roots:
        resolved[(group, artifact)] = version
        scope_of[(group, artifact)] = scopes.get(alias, UNREFERENCED)

    # Coordinates written inline in a build script, for modules with no
    # catalogue entry. The catalogue wins where both name the same artifact:
    # that is what Gradle itself resolves.
    for group, artifact, version, config in parse_literal_deps(repo_root):
        key = (group, artifact)
        if key in resolved:
            continue
        resolved[key] = version
        scope_of[key] = SHIPPED if config in SHIPPING_CONFIGS else BUILD_ONLY

    # BOM-managed entries carry no version in the catalogue.
    missing = [(g, a) for (g, a), v in resolved.items() if not v]
    if missing:
        with ThreadPoolExecutor(max_workers=8) as pool:
            for (g, a), v in zip(missing, pool.map(lambda ga: _latest_release(*ga), missing)):
                resolved[(g, a)] = v

    components: dict[tuple[str, str], Component] = {}
    frontier = [(g, a, v) for (g, a), v in resolved.items() if v]
    seen: set[tuple[str, str]] = set()

    for depth in range(_MAX_DEPTH):
        batch = [c for c in frontier if (c[0], c[1]) not in seen]
        if not batch:
            break
        for c in batch:
            seen.add((c[0], c[1]))

        # A dependency pinned with an open range carries no single version;
        # resolve those against the repository metadata before fetching POMs.
        unpinned = [i for i, c in enumerate(batch) if not c[2]]
        if unpinned:
            with ThreadPoolExecutor(max_workers=8) as pool:
                fixed = list(pool.map(lambda i: _latest_release(batch[i][0], batch[i][1]),
                                      unpinned))
            for i, v in zip(unpinned, fixed):
                batch[i] = (batch[i][0], batch[i][1], v)

        with ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(
                lambda c: resolve_licence(*c) if c[2] else (None, "", []), batch))

        next_frontier = []
        for (group, artifact, version), (licence, url, deps) in zip(batch, results):
            key = (group, artifact)
            # A transitive dependency inherits the scope of whatever pulled it
            # in; at depth 0 that is the catalogue entry's own scope.
            scope = scope_of.get(key, SHIPPED if depth else UNREFERENCED)
            components[key] = Component(
                name=f"{group}:{artifact}", version=version, declared=licence,
                source="gradle" if depth == 0 else "gradle (transitive)",
                url=url, scope=scope,
            )
            if transitive and depth + 1 < _MAX_DEPTH and scope == SHIPPED:
                for d in deps:
                    if (d[0], d[1]) not in seen:
                        scope_of.setdefault((d[0], d[1]), SHIPPED)
                        next_frontier.append(d)
        frontier = next_frontier

    # Entries we could not resolve at all still have to be reported: an
    # unresolvable coordinate is "licence not determined", which is a finding.
    for (group, artifact), version in resolved.items():
        if (group, artifact) not in components:
            components[(group, artifact)] = Component(
                name=f"{group}:{artifact}", version=version or "(unresolved)",
                declared=None, source="gradle", url="",
                scope=scope_of.get((group, artifact), UNREFERENCED),
            )
    return list(components.values())
