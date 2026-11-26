#!/usr/bin/env python3
"""Compute the next Tubiac alpha versionName, tag_name, and monotonic versionCode.

Rules:
- Only non-draft GitHub releases count as successful releases.
- If no published releases exist yet -> 0.1.0-alpha (tag v0.1.0-alpha), build 1.
- Otherwise pick the highest semantic version among published releases
  (e.g., 0.1.0-alpha, 0.2.0, 0.2.0-beta) and increment PATCH by 1 ->
  MAJOR.MINOR.(PATCH+1)-alpha (e.g., 0.2.0-beta -> 0.2.1-alpha -> 0.2.2-alpha).
- Build number (versionCode) increments by 1 over the highest recorded build number
  (tracked via `<!-- tubiac-build: N -->` in release bodies, with release count fallback).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from typing import Any

SEMVER_RE = re.compile(r"^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?$")
BUILD_MARKER_RE = re.compile(r"<!--\s*tubiac-build:\s*(\d+)\s*-->")
BUILD_FALLBACK_RE = re.compile(r"\bbuild\s*#?\s*(\d+)\b", re.IGNORECASE)


def prerelease_rank(pre: str | None) -> tuple[int, str]:
    if not pre:
        return (3, "")
    low = pre.lower()
    if low.startswith("rc"):
        return (2, low)
    if low.startswith("beta"):
        return (1, low)
    return (0, low)


def parse_semver(tag: str) -> tuple[int, int, int, tuple[int, str]] | None:
    match = SEMVER_RE.match(tag.strip())
    if not match:
        return None
    major, minor, patch = int(match.group(1)), int(match.group(2)), int(match.group(3))
    return (major, minor, patch, prerelease_rank(match.group(4)))


def fetch_releases(repo: str, token: str | None) -> list[dict[str, Any]]:
    releases: list[dict[str, Any]] = []
    page = 1
    while True:
        url = f"https://api.github.com/repos/{repo}/releases?per_page=100&page={page}"
        headers = {
            "Accept": "application/vnd.github+json",
            "User-Agent": "tubiac-release-versioner",
        }
        if token:
            headers["Authorization"] = f"Bearer {token}"
        req = urllib.request.Request(url, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=20) as resp:
                batch = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            if exc.code == 404:
                return []
            raise
        if not batch:
            break
        releases.extend(batch)
        if len(batch) < 100:
            break
        page += 1
    return releases


def extract_build_number(release: dict[str, Any]) -> int:
    body = release.get("body") or ""
    name = release.get("name") or ""
    marker = BUILD_MARKER_RE.search(body)
    if marker:
        return int(marker.group(1))
    fallback = BUILD_FALLBACK_RE.search(f"{name}\n{body}")
    if fallback:
        return int(fallback.group(1))
    return 0


def compute_next(
    releases: list[dict[str, Any]],
    explicit_tag: str = "",
) -> dict[str, str]:
    published = [r for r in releases if not r.get("draft", False)]
    builds = [extract_build_number(r) for r in published]
    max_build = max(builds, default=0)
    if published and max_build == 0:
        max_build = len(published)
    next_build = max_build + 1

    cleaned_explicit = explicit_tag.strip()
    if cleaned_explicit and cleaned_explicit.lower() != "auto":
        tag_name = cleaned_explicit if cleaned_explicit.startswith("v") else f"v{cleaned_explicit}"
        version_name = tag_name[1:]
        return {
            "version_name": version_name,
            "tag_name": tag_name,
            "version_code": str(next_build),
        }

    parsed_versions: list[tuple[tuple[int, int, int, tuple[int, str]], str]] = []
    for rel in published:
        tag = rel.get("tag_name") or ""
        parsed = parse_semver(tag)
        if parsed is not None:
            parsed_versions.append((parsed, tag))

    if not parsed_versions:
        version_name = "0.1.0-alpha"
    else:
        latest_semver, _ = max(parsed_versions, key=lambda item: item[0])
        major, minor, patch, _ = latest_semver
        version_name = f"{major}.{minor}.{patch + 1}-alpha"

    return {
        "version_name": version_name,
        "tag_name": f"v{version_name}",
        "version_code": str(next_build),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY", ""))
    parser.add_argument("--token", default=os.environ.get("GITHUB_TOKEN", ""))
    parser.add_argument("--explicit-tag", default="")
    parser.add_argument("--releases-json", default="")
    args = parser.parse_args()

    if args.releases_json:
        with open(args.releases_json, "r", encoding="utf-8") as fh:
            releases = json.load(fh)
    else:
        if not args.repo:
            print("Missing --repo or GITHUB_REPOSITORY", file=sys.stderr)
            return 1
        releases = fetch_releases(args.repo, args.token or None)

    result = compute_next(releases, explicit_tag=args.explicit_tag)
    for k, v in result.items():
        print(f"{k}={v}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
