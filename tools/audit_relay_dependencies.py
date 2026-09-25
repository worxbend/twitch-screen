#!/usr/bin/env python3
"""Scan Mill's resolved runtime Maven artifacts against OSV, failing closed."""

import argparse
from datetime import date, datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
API = "https://api.osv.dev/v1/querybatch"
MAX_RESPONSE = 4 * 1024 * 1024


class AuditError(Exception):
    pass


def coordinates(references):
    """Accept the pinned Mill/Coursier Maven Central layout, never skip a jar."""
    if not isinstance(references, list) or not 1 <= len(references) <= 2000:
        raise AuditError("Expected 1..2000 resolved runtime artifact references")
    result = set()
    for reference in references:
        if not isinstance(reference, str):
            raise AuditError("Artifact reference is not a string")
        match = re.fullmatch(r"(?:qref|ref):v1:[0-9a-f]+:(/[^\n]+)", reference)
        if not match:
            raise AuditError(f"Unrecognized Mill reference: {reference!r}")
        path = match[1]
        layout = re.fullmatch(
            r".+/https/(?:repo1\.maven\.org|repo\.maven\.apache\.org)/maven2/(.+)", path
        )
        if not layout:
            raise AuditError(f"Unrecognized Maven repository path: {path!r}")
        parts = layout[1].split("/")
        if len(parts) < 4 or any(not re.fullmatch(r"[A-Za-z0-9_.+\-]+", p) or p in (".", "..") for p in parts):
            raise AuditError(f"Invalid Maven path: {path!r}")
        *group, artifact, version, filename = parts
        stem = re.escape(f"{artifact}-{version}")
        if not re.fullmatch(stem + r"(?:-[A-Za-z0-9_.+\-]+)?\.jar", filename):
            raise AuditError(f"Artifact filename does not match coordinate: {path!r}")
        result.add((".".join(group) + ":" + artifact, version))
    return sorted(result)


def request_osv(queries):
    request = urllib.request.Request(
        API, data=json.dumps({"queries": queries}).encode(),
        headers={"Content-Type": "application/json", "User-Agent": "twitch-screen-dependency-audit"},
    )
    with urllib.request.urlopen(request, timeout=20) as response:
        raw = response.read(MAX_RESPONSE + 1)
    if len(raw) > MAX_RESPONSE:
        raise AuditError("OSV response exceeded size limit")
    return json.loads(raw)


def scan(packages, request=request_osv):
    """Preserve query/result pairing and follow each query's own page token."""
    findings = set()
    deadline = time.monotonic() + 300
    for start in range(0, len(packages), 100):
        pending = [(package, version, None) for package, version in packages[start:start + 100]]
        seen_tokens = set()
        for _ in range(20):
            if time.monotonic() >= deadline:
                raise AuditError("OSV scan exceeded five minutes")
            queries = []
            for package, version, token in pending:
                query = {"package": {"ecosystem": "Maven", "name": package}, "version": version}
                if token is not None:
                    query["page_token"] = token
                queries.append(query)
            response = request(queries)
            results = response.get("results") if isinstance(response, dict) else None
            if not isinstance(response, dict) or set(response) != {"results"} or not isinstance(results, list) or len(results) != len(pending):
                raise AuditError("OSV returned an incomplete result batch")
            next_queries = []
            for (package, version, _), result in zip(pending, results):
                if not isinstance(result, dict) or set(result) - {"vulns", "next_page_token"}:
                    raise AuditError("OSV returned an error or unknown result shape")
                vulnerabilities = result.get("vulns", [])
                if not isinstance(vulnerabilities, list):
                    raise AuditError("OSV vulnerabilities are not a list")
                for vulnerability in vulnerabilities:
                    identifier = vulnerability.get("id") if isinstance(vulnerability, dict) else None
                    if not isinstance(identifier, str) or not re.fullmatch(r"[A-Za-z0-9_.-]{1,200}", identifier):
                        raise AuditError("OSV returned an invalid advisory ID")
                    findings.add((package, version, identifier))
                token = result.get("next_page_token")
                if token is not None:
                    if not isinstance(token, str) or not token or len(token) > 8192:
                        raise AuditError("OSV returned an invalid pagination token")
                    page = (package, version, token)
                    if page in seen_tokens:
                        raise AuditError("OSV repeated a pagination token")
                    seen_tokens.add(page)
                    next_queries.append(page)
            if not next_queries:
                break
            pending = next_queries
        else:
            raise AuditError("OSV pagination exceeded 20 pages; scan incomplete")
    return sorted(findings)


def apply_exceptions(findings, exceptions, today):
    if not isinstance(exceptions, list):
        raise AuditError("Exceptions must be a list")
    allowed = {}
    fields = {"package", "version", "advisory", "reviewed", "expires", "owner", "reason"}
    for exception in exceptions:
        if not isinstance(exception, dict) or set(exception) != fields or any(
            not isinstance(value, str) or not value.strip() for value in exception.values()
        ):
            raise AuditError("Invalid dependency exception")
        key = (exception["package"], exception["version"], exception["advisory"])
        if key in allowed:
            raise AuditError(f"Duplicate exception: {key}")
        if date.fromisoformat(exception["reviewed"]) > today:
            raise AuditError(f"Exception review date is in the future: {key}")
        if date.fromisoformat(exception["expires"]) <= today:
            raise AuditError(f"Expired exception: {key}")
        allowed[key] = exception
    stale = allowed.keys() - set(findings)
    if stale:
        raise AuditError(f"Remove exceptions no longer returned by OSV: {sorted(stale)}")
    return [finding for finding in findings if finding not in allowed]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--resolved", type=Path, help="JSON from ./mill show resolvedRunMvnDeps")
    parser.add_argument("--exceptions", type=Path, default=ROOT / "tools/relay_dependency_exceptions.json")
    parser.add_argument("--output", type=Path, help="Write the complete coordinate/finding report")
    args = parser.parse_args()
    try:
        raw = args.resolved.read_text() if args.resolved else subprocess.check_output(
            ["./mill", "show", "resolvedRunMvnDeps"], cwd=ROOT / "twitch-screen-relay", text=True, timeout=300
        )
        packages = coordinates(json.loads(raw))
        findings = scan(packages)
        exceptions = json.loads(args.exceptions.read_text())
        unexpected = apply_exceptions(findings, exceptions, datetime.now(timezone.utc).date())
        report = {
            "scanned_at": datetime.now(timezone.utc).isoformat(), "source": API,
            "packages": [{"package": p, "version": v} for p, v in packages],
            "findings": [{"package": p, "version": v, "advisory": a} for p, v, a in findings],
            "exceptions": exceptions, "unexpected": [list(f) for f in unexpected],
        }
        if args.output:
            args.output.write_text(json.dumps(report, indent=2) + "\n")
        for package, version, advisory in findings:
            status = "FAIL" if (package, version, advisory) in unexpected else "EXCEPTION"
            suffix = ""
            if status == "EXCEPTION":
                exception = next(e for e in exceptions if (e["package"], e["version"], e["advisory"]) == (package, version, advisory))
                suffix = f" (reviewed {exception['reviewed']}, expires {exception['expires']}; {exception['owner']})"
            print(f"{status}: {package}:{version} {advisory}{suffix}")
        print(f"Scanned {len(packages)} runtime coordinates: {len(findings)} advisories, {len(unexpected)} unexcepted.")
        return 1 if unexpected else 0
    except (AuditError, OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Dependency audit incomplete: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
