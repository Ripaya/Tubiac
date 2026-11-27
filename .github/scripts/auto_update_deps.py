#!/usr/bin/env python3
"""Check and optionally update Tubiac dependencies in gradle/libs.versions.toml and gradle-wrapper.properties."""

from __future__ import annotations

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

GOOGLE_MAVEN = "https://dl.google.com/dl/android/maven2"
MAVEN_CENTRAL = "https://repo1.maven.org/maven2"
GRADLE_CURRENT_URL = "https://services.gradle.org/versions/current"

# Representative artifact coordinates for each version key in gradle/libs.versions.toml
VERSION_ARTIFACTS: dict[str, tuple[str, str]] = {
    "agp": ("com.android.tools.build", "gradle"),
    "kotlin": ("org.jetbrains.kotlin", "kotlin-stdlib"),
    "ksp": ("com.google.devtools.ksp", "symbol-processing-gradle-plugin"),
    "composeBom": ("androidx.compose", "compose-bom"),
    "materialIcons": ("androidx.compose.material", "material-icons-core"),
    "hilt": ("com.google.dagger", "hilt-android"),
    "androidxHilt": ("androidx.hilt", "hilt-work"),
    "room": ("androidx.room", "room-runtime"),
    "coroutines": ("org.jetbrains.kotlinx", "kotlinx-coroutines-core"),
    "serialization": ("org.jetbrains.kotlinx", "kotlinx-serialization-json"),
    "navigationCompose": ("androidx.navigation", "navigation-compose"),
    "coreKtx": ("androidx.core", "core-ktx"),
    "lifecycle": ("androidx.lifecycle", "lifecycle-runtime-ktx"),
    "activityCompose": ("androidx.activity", "activity-compose"),
    "splashscreen": ("androidx.core", "core-splashscreen"),
    "datastore": ("androidx.datastore", "datastore-preferences"),
    "work": ("androidx.work", "work-runtime-ktx"),
    "biometric": ("androidx.biometric", "biometric"),
    "documentfile": ("androidx.documentfile", "documentfile"),
    "palette": ("androidx.palette", "palette-ktx"),
    "preference": ("androidx.preference", "preference-ktx"),
    "okhttp": ("com.squareup.okhttp3", "okhttp"),
    "coil": ("io.coil-kt.coil3", "coil-compose"),
    "telephoto": ("me.saket.telephoto", "zoomable-image-coil3"),
    "jsoup": ("org.jsoup", "jsoup"),
    "rxjava": ("io.reactivex", "rxjava"),
    "junit": ("junit", "junit"),
}

PRERELEASE_RE = re.compile(r"(alpha|beta|rc|dev|snapshot|-m\d+)", re.IGNORECASE)
VERSION_LINE_RE = re.compile(r'^([A-Za-z0-9_]+)\s*=\s*"([^"]+)"\s*$')
GRADLE_DIST_RE = re.compile(r"(distributionUrl=https\\://services\.gradle\.org/distributions/gradle-)([0-9.]+)(-bin\.zip)")


def parse_version_tuple(ver: str) -> tuple[int, ...]:
    nums = re.findall(r"\d+", ver)
    return tuple(int(n) for n in nums) if nums else (0,)


def is_stable(ver: str) -> bool:
    return PRERELEASE_RE.search(ver) is None


def fetch_maven_versions(group: str, artifact: str) -> list[str]:
    path = f"{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
    repos = (
        [GOOGLE_MAVEN, MAVEN_CENTRAL]
        if group.startswith(("androidx.", "com.android.", "com.google.android."))
        else [MAVEN_CENTRAL, GOOGLE_MAVEN]
    )
    for base in repos:
        url = f"{base}/{path}"
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "tubiac-dep-checker"})
            with urllib.request.urlopen(req, timeout=15) as resp:
                xml_data = resp.read()
            root = ET.fromstring(xml_data)
            versions = [el.text.strip() for el in root.findall("./versioning/versions/version") if el.text]
            if versions:
                return versions
        except (urllib.error.URLError, ET.ParseError, TimeoutError):
            continue
    return []


def fetch_latest_gradle() -> str | None:
    try:
        req = urllib.request.Request(GRADLE_CURRENT_URL, headers={"User-Agent": "tubiac-dep-checker"})
        with urllib.request.urlopen(req, timeout=15) as resp:
            data = json.loads(resp.read().decode("utf-8"))
        if data.get("final") and not data.get("broken"):
            return str(data.get("version") or "")
    except (urllib.error.URLError, json.JSONDecodeError, TimeoutError):
        return None
    return None


def read_toml_versions(toml_path: Path) -> dict[str, str]:
    versions: dict[str, str] = {}
    in_versions = False
    for raw_line in toml_path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line.startswith("["):
            in_versions = line == "[versions]"
            continue
        if in_versions and not line.startswith("#"):
            m = VERSION_LINE_RE.match(line)
            if m:
                versions[m.group(1)] = m.group(2)
    return versions


def find_updates(toml_path: Path, wrapper_path: Path) -> list[dict[str, str]]:
    current_versions = read_toml_versions(toml_path)
    updates: list[dict[str, str]] = []

    for key, current in current_versions.items():
        coords = VERSION_ARTIFACTS.get(key)
        if not coords:
            continue
        all_versions = fetch_maven_versions(*coords)
        allow_pre = not is_stable(current)
        candidates = [v for v in all_versions if allow_pre or is_stable(v)]
        if not candidates:
            continue
        best = max(candidates, key=parse_version_tuple)
        if parse_version_tuple(best) > parse_version_tuple(current):
            updates.append(
                {
                    "key": key,
                    "target": "toml",
                    "artifact": f"{coords[0]}:{coords[1]}",
                    "current": current,
                    "latest": best,
                }
            )

    if wrapper_path.exists():
        m = GRADLE_DIST_RE.search(wrapper_path.read_text(encoding="utf-8"))
        if m:
            cur_gradle = m.group(2)
            latest_gradle = fetch_latest_gradle()
            if latest_gradle and parse_version_tuple(latest_gradle) > parse_version_tuple(cur_gradle):
                updates.append(
                    {
                        "key": "gradle",
                        "target": "wrapper",
                        "artifact": "gradle-wrapper",
                        "current": cur_gradle,
                        "latest": latest_gradle,
                    }
                )

    return updates


def apply_updates(
    toml_path: Path,
    wrapper_path: Path,
    updates: list[dict[str, str]],
    include_keys: set[str] | None,
    exclude_keys: set[str],
) -> list[dict[str, str]]:
    selected = [
        u
        for u in updates
        if u["key"] not in exclude_keys and (include_keys is None or u["key"] in include_keys)
    ]
    if not selected:
        return []

    toml_updates = {u["key"]: u["latest"] for u in selected if u["target"] == "toml"}
    if toml_updates:
        new_lines: list[str] = []
        in_versions = False
        for raw_line in toml_path.read_text(encoding="utf-8").splitlines():
            stripped = raw_line.strip()
            if stripped.startswith("["):
                in_versions = stripped == "[versions]"
            elif in_versions:
                m = VERSION_LINE_RE.match(stripped)
                if m and m.group(1) in toml_updates:
                    raw_line = f'{m.group(1)} = "{toml_updates[m.group(1)]}"'
            new_lines.append(raw_line)
        toml_path.write_text("\n".join(new_lines) + "\n", encoding="utf-8")

    for u in selected:
        if u["target"] == "wrapper" and wrapper_path.exists():
            text = wrapper_path.read_text(encoding="utf-8")
            text = GRADLE_DIST_RE.sub(rf"\g<1>{u['latest']}\g<3>", text)
            wrapper_path.write_text(text, encoding="utf-8")

    return selected


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--toml", default="gradle/libs.versions.toml")
    parser.add_argument("--wrapper", default="gradle/wrapper/gradle-wrapper.properties")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--only", default="")
    parser.add_argument("--exclude", default="")
    parser.add_argument("--json-out", default="")
    args = parser.parse_args()

    toml_path = Path(args.toml)
    wrapper_path = Path(args.wrapper)
    updates = find_updates(toml_path, wrapper_path)

    include_keys = {k.strip() for k in args.only.split(",") if k.strip()} if args.only else None
    exclude_keys = {k.strip() for k in args.exclude.split(",") if k.strip()} if args.exclude else set()

    applied: list[dict[str, str]] = []
    if args.apply:
        applied = apply_updates(toml_path, wrapper_path, updates, include_keys, exclude_keys)

    payload = {"available_updates": updates, "applied_updates": applied}
    if args.json_out:
        Path(args.json_out).write_text(json.dumps(payload, indent=2), encoding="utf-8")

    print(json.dumps(payload, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
