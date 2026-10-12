#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Deterministic, allowlisted static build. Never loads relay or local workflow state."""
import argparse
import gzip
import hashlib
import html
from html.parser import HTMLParser
import json
import math
from pathlib import Path
import re
import stat
from urllib.parse import unquote, urlsplit
import xml.etree.ElementTree as ET

SITE = Path(__file__).resolve().parents[1]
REPOSITORY = SITE.parent
SOURCE_FILES = ("index.html", "content/features.json", "assets/site.css", "assets/demo.js",
                "assets/orbit.svg", "assets/social.svg", "assets/provenance.json")
ASSETS = tuple(name for name in SOURCE_FILES if name.startswith("assets/"))
MAX_GZIP_BYTES = 1572864
MAX_RAW_BYTES = 8 * 1024 * 1024
MAX_JSON_BYTES = 65536
REPO_URL = "https://github.com/worxbend/twitch-screen/"
# Change only after independently reviewed acceptance for the specific feature.
FEATURE_STATES = {"notifications": "existing", "twitch": "existing", "cpu": "planned",
                  "weather": "planned", "orbits": "planned", "admin": "planned",
                  "companion": "planned"}


def base_path(value):
    if not re.fullmatch(r"/(?:[A-Za-z0-9_-]+/)*", value):
        raise ValueError("base path must be / or slash-delimited simple path segments")
    return value


def strict_json(text):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate JSON key")
            result[key] = value
        return result
    if not isinstance(text, str) or len(text.encode('utf-8')) > MAX_JSON_BYTES:
        raise ValueError('JSON exceeds 64 KiB byte bound')

    # Bound depth before stdlib allocates nested containers; ignore brackets in strings.
    depth = 0
    quoted = False
    escaped = False
    for char in text:
        if quoted:
            if escaped:
                escaped = False
            elif char == '\\':
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in '[{':
            depth += 1
            if depth > 64:
                raise ValueError('JSON nesting exceeds 64 levels')
        elif char in ']}':
            depth -= 1

    def invalid_constant(value):
        raise ValueError('non-finite JSON number forbidden')

    def finite_float(value):
        number = float(value)
        if not math.isfinite(number):
            raise ValueError('non-finite JSON number forbidden')
        return number

    try:
        return json.loads(text, object_pairs_hook=pairs, parse_constant=invalid_constant,
                          parse_float=finite_float)
    except RecursionError as error:
        raise ValueError('JSON nesting exceeds parser bound') from error


def read_sources(source):
    source = Path(source)
    for name in SOURCE_FILES:
        path = source / name
        if any(part.is_symlink() for part in (path, *path.parents)):
            raise ValueError("source symlinks are not admitted")
        if not path.is_file():
            raise ValueError(f"missing allowlisted source: {name}")
    if sum((source / name).stat().st_size for name in SOURCE_FILES) > MAX_RAW_BYTES:
        raise ValueError('uncompressed source exceeds 8 MiB bound')
    return {name: (source / name).read_text(encoding="utf-8") for name in SOURCE_FILES}


def check_reference(path):
    if not re.fullmatch(r"[A-Za-z0-9_./-]+", path) or ".." in Path(path).parts:
        raise ValueError("invalid repository reference")
    target = REPOSITORY / path
    if not target.is_file():
        raise ValueError(f"missing repository reference: {path}")


def markdown_anchors(text):
    """GitHub-style IDs for ATX headings outside fenced code; explicit HTML IDs too."""
    anchors = set()
    fence = None
    for line in text.splitlines():
        marker = re.match(r"^\s{0,3}(`{3,}|~{3,})", line)
        if marker:
            token = marker[1]
            if fence is None:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence):
                fence = None
            continue
        if fence is not None:
            continue
        anchors.update(re.findall(r'\bid=["\']([^"\']+)["\']', line))
        heading = re.match(r"^\s{0,3}#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not heading:
            continue
        label = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", heading[1])
        label = html.unescape(re.sub(r"<[^>]*>", "", label)).lower()
        slug = "".join(char for char in label if char.isalnum() or char in " _-").replace(" ", "-")
        candidate = slug
        suffix = 0
        while candidate in anchors:
            suffix += 1
            candidate = f"{slug}-{suffix}"
        anchors.add(candidate)
    return anchors


def render_features(text):
    features = strict_json(text)
    if not isinstance(features, list) or len(features) != 7:
        raise ValueError("feature ledger must contain seven scoped features")
    seen = set()
    rendered = []
    for item in features:
        if not isinstance(item, dict) or set(item) != {"id", "title", "state", "scope", "description", "reference"}:
            raise ValueError("unexpected feature fields")
        if not all(isinstance(value, str) and value.strip() for value in item.values()):
            raise ValueError("feature strings must be nonempty")
        if item["state"] not in {"existing", "planned"}:
            raise ValueError("feature promotion requires a reviewed build-contract change")
        if item["id"] not in FEATURE_STATES or item["id"] in seen:
            raise ValueError("invalid or duplicate feature identity")
        if item["state"] != FEATURE_STATES[item["id"]]:
            raise ValueError("feature availability requires a reviewed build-contract change")
        seen.add(item["id"])
        if item["state"] == "planned" and ("planned" not in item["scope"].lower() or
                re.search(r"\b(shipped|available now|fully verified)\b", item["description"], re.I)):
            raise ValueError("planned feature must not claim availability")
        check_reference(item["reference"])
        escaped = {key: html.escape(value, quote=True) for key, value in item.items()}
        rendered.append(f'<li id="feature-{escaped["id"]}" data-state="{escaped["state"]}">'
                        f'<span class="scope">{escaped["scope"]}</span>'
                        f'<h3>{escaped["title"]}</h3><p>{escaped["description"]}</p>'
                        f'<a href="{REPO_URL}blob/main/{escaped["reference"]}">'
                        f'Read {escaped["title"].lower()} reference</a></li>')
    return "\n".join(rendered)


class Document(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ids = set()
        self.refs = []
        self.headings = 0
        self.title = False
        self.description = False
        self.canonicals = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        identity = attrs.get("id")
        if identity:
            if identity in self.ids:
                raise ValueError(f"duplicate HTML ID: {identity}")
            self.ids.add(identity)
        if tag == "h1":
            self.headings += 1
        if tag == "title":
            self.title = True
        if tag == "link" and attrs.get("rel") == "canonical":
            self.canonicals.append(attrs.get("href"))
        if tag == "meta" and attrs.get("name") == "description":
            self.description = bool(attrs.get("content"))
        if tag in {"iframe", "form", "object", "embed", "base"}:
            raise ValueError(f"forbidden public-site element: {tag}")
        if any(key.startswith("on") or key == "style" for key in attrs):
            raise ValueError("inline handlers/styles forbidden")
        if tag == "img" and not all(key in attrs for key in ("alt", "width", "height")):
            raise ValueError("images require alt and dimensions")
        if tag == "script" and not attrs.get("src"):
            raise ValueError("inline script forbidden")
        for key in ("href", "src"):
            if key in attrs:
                resource = tag != "a" and not (tag == "link" and attrs.get("rel") == "canonical")
                self.refs.append((attrs[key], resource))


def validate_provenance(text):
    provenance = strict_json(text)
    if (not isinstance(provenance, dict) or set(provenance) != {"assets", "codeLicense", "scope"}
            or provenance["codeLicense"] != "MIT" or not isinstance(provenance["scope"], str)
            or not provenance["scope"].strip() or not isinstance(provenance["assets"], list)
            or len(provenance["assets"]) != 2):
        raise ValueError("invalid illustration provenance")
    paths = set()
    for item in provenance["assets"]:
        if (not isinstance(item, dict) or set(item) != {"path", "creator", "license", "kind", "physicalEvidence"}
                or not all(isinstance(item[key], str) and item[key].strip()
                           for key in ("path", "creator", "license", "kind"))):
            raise ValueError("invalid illustration provenance fields")
        if item["physicalEvidence"] is not False or item["license"] != "CC-BY-4.0":
            raise ValueError("illustrations cannot be physical evidence or claim unreviewed licensing")
        paths.add(item["path"])
    if paths != {"assets/orbit.svg", "assets/social.svg"}:
        raise ValueError("every illustrated asset needs explicit provenance")


def validate_output(files, base):
    if sum(len(data) for data in files.values()) > MAX_RAW_BYTES:
        raise ValueError('uncompressed artifact exceeds 8 MiB bound')
    validate_provenance(files["assets/provenance.json"].decode("utf-8"))
    document = Document()
    document.feed(files["index.html"].decode("utf-8"))
    if document.headings != 1 or not document.title or not document.description:
        raise ValueError("document needs one h1, title and description")
    for ref, resource in document.refs:
        parts = urlsplit(ref)
        if parts.scheme or parts.netloc:
            if parts.scheme != "https" or parts.username or resource:
                raise ValueError(f"invalid or remote resource: {ref}")
            if parts.hostname == "github.com":
                if ref != REPO_URL.rstrip("/") and not ref.startswith(REPO_URL):
                    raise ValueError("unexpected repository link")
                relative = parts.path.split("/", 3)[-1]
                if relative.startswith(("blob/main/", "tree/main/")):
                    target = relative.split("/", 2)[2]
                    if not re.fullmatch(r"[A-Za-z0-9_./-]+", target) or ".." in Path(target).parts:
                        raise ValueError("unsafe repository target")
                    local = REPOSITORY / target
                    if not local.exists():
                        raise ValueError(f"missing repository link: {target}")
                    if parts.fragment:
                        if not local.is_file() or local.suffix.lower() != ".md":
                            raise ValueError("repository fragment requires a Markdown file")
                        if unquote(parts.fragment) not in markdown_anchors(local.read_text(encoding="utf-8")):
                            raise ValueError(f"missing repository fragment: {target}#{parts.fragment}")
            continue
        if parts.query or "%" in parts.path or ".." in parts.path.split("/"):
            raise ValueError("unsafe local reference")
        if parts.path:
            if not parts.path.startswith(base):
                raise ValueError(f"reference outside base: {ref}")
            target = parts.path[len(base):] or "index.html"
            if target not in files:
                raise ValueError(f"missing local asset: {ref}")
        if parts.fragment and (not parts.path or parts.path == base) and parts.fragment not in document.ids:
            raise ValueError(f"missing fragment: {ref}")
    css = files["assets/site.css"].decode()
    js = files["assets/demo.js"].decode()
    if re.search(r"@import|url\s*\(", css, re.I):
        raise ValueError("CSS resources are not admitted")
    if re.search(r"\b(fetch|XMLHttpRequest|WebSocket|EventSource|setInterval|import)\b", js):
        raise ValueError("demo must not import, poll or access the network")
    for name in ("assets/orbit.svg", "assets/social.svg"):
        root = ET.fromstring(files[name])
        for element in root.iter():
            if element.tag.rsplit("}", 1)[-1] in {"script", "foreignObject", "image", "use"}:
                raise ValueError("SVG must contain only original local vector content")
            if any(key.endswith("href") or key.startswith("on") for key in element.attrib):
                raise ValueError("SVG references/handlers forbidden")
    total = sum(len(gzip.compress(data, mtime=0)) for data in files.values())
    if total > MAX_GZIP_BYTES:
        raise ValueError("compressed payload exceeds 1.5 MiB")
    return total


def build(output, base="/twitch-screen/", canonical=None, source=SITE):
    base = base_path(base)
    sources = read_sources(source)
    metadata = ""
    if canonical:
        parts = urlsplit(canonical)
        if (parts.scheme != "https" or parts.username or parts.password or not parts.hostname
                or parts.query or parts.fragment or parts.path != base):
            raise ValueError("canonical must be an HTTPS origin plus the selected base path")
        escaped = html.escape(canonical, quote=True)
        metadata = (f'<link rel="canonical" href="{escaped}">\n'
                    f'  <meta property="og:url" content="{escaped}">\n'
                    f'  <meta property="og:image" content="{escaped}assets/social.svg">\n'
                    '  <meta property="og:image:alt" content="Original orbital illustration, not a device photograph">')
    # This bodyless example is shared with A1's notification_bodyless fixture.
    example = {"type": "message", "title": "Build finished", "body": ""}
    replacements = {"base": base, "canonical": metadata,
                    "features": render_features(sources["content/features.json"]),
                    "example": html.escape(json.dumps(example, indent=2))}
    document = sources["index.html"]
    for key, value in replacements.items():
        document = document.replace("{{" + key + "}}", value)
    if "{{" in document:
        raise ValueError("unresolved template token")
    files = {name: sources[name].encode("utf-8") for name in ASSETS}
    files["index.html"] = document.encode("utf-8")
    files[".nojekyll"] = b""
    compressed = validate_output(files, base)
    manifest = {"schemaVersion": 1, "basePath": base, "canonical": canonical,
                "gzipBytes": compressed, "files": {name: {"bytes": len(data),
                "sha256": hashlib.sha256(data).hexdigest()} for name, data in sorted(files.items())}}
    files["build-manifest.json"] = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    output = Path(output).absolute()
    if any(part.is_symlink() for part in (output, *output.parents)):
        raise ValueError("output symlinks forbidden")
    # Normalize parent segments only after rejecting symlink traversal.
    output = output.resolve()
    source_root = Path(source).resolve()
    if output == SITE or output in SITE.parents or output == source_root or output in source_root.parents:
        raise ValueError("output cannot overwrite source or repository")
    if output.exists():
        expected_directories = {parent.as_posix() for name in files
                                for parent in Path(name).parents if parent != Path('.')}
        for path in output.rglob("*"):
            mode = path.lstat().st_mode
            name = path.relative_to(output).as_posix()
            owned_file = stat.S_ISREG(mode) and name in files
            owned_directory = stat.S_ISDIR(mode) and name in expected_directories
            if not (owned_file or owned_directory):
                raise ValueError("output contains unowned members; use an empty directory")
    for name, data in sorted(files.items()):
        target = output / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=SITE / "dist")
    parser.add_argument("--base-path", default="/twitch-screen/")
    parser.add_argument("--canonical")
    args = parser.parse_args()
    try:
        manifest = build(args.output, args.base_path, args.canonical)
    except (ValueError, OSError) as error:
        parser.exit(1, f"build failed: {error}\n")
    print(json.dumps(manifest, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
