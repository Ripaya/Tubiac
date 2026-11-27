#!/usr/bin/env python3
"""Monitor domains, links, DoH resolvers, and core services used by Tubiac."""

from __future__ import annotations

import argparse
import json
import re
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path

URL_RE = re.compile(r"https?://[^\s\"'<>`)\]}]+")

SKIP_PREFIXES = (
    "http://schemas.android.com",
    "https://schemas.android.com",
    "http://www.w3.org",
    "https://www.w3.org",
    "https://example.org",
    "http://example.org",
    "https://...",
    "http://...",
    "https://github.com/Ripaya/Tubiac",
)

CORE_PROBES = [
    {
        "name": "AniList GraphQL API",
        "url": "https://graphql.anilist.co",
        "method": "POST",
        "body": json.dumps({"query": "{ Page(perPage: 1) { media(type: MANGA) { id } } }"}),
        "headers": {"Content-Type": "application/json", "Accept": "application/json"},
    },
    {
        "name": "AniList OAuth Endpoint",
        "url": "https://anilist.co/api/v2/oauth/authorize",
        "method": "GET",
    },
    {
        "name": "Cloudflare DoH",
        "url": "https://cloudflare-dns.com/dns-query?name=anilist.co&type=A",
        "method": "GET",
        "headers": {"Accept": "application/dns-json"},
    },
    {
        "name": "Google DoH",
        "url": "https://dns.google/resolve?name=anilist.co&type=A",
        "method": "GET",
        "headers": {"Accept": "application/dns-json"},
    },
    {
        "name": "AdGuard DoH",
        "url": "https://dns.adguard-dns.com/resolve?name=anilist.co&type=A",
        "method": "GET",
        "headers": {"Accept": "application/dns-json"},
    },
    {
        "name": "Google Maven",
        "url": "https://dl.google.com/dl/android/maven2/androidx/core/core-ktx/maven-metadata.xml",
        "method": "GET",
    },
    {
        "name": "Maven Central",
        "url": "https://repo1.maven.org/maven2/com/squareup/okhttp3/okhttp/maven-metadata.xml",
        "method": "GET",
    },
    {
        "name": "Gradle Services",
        "url": "https://services.gradle.org/versions/current",
        "method": "GET",
    },
    {
        "name": "Mangitto (mangtto.com)",
        "url": "https://mangtto.com",
        "method": "GET",
        "advisory_only": True,
    },
]


@dataclass
class ProbeResult:
    name: str
    url: str
    status: str
    http_code: int
    latency_ms: int
    detail: str
    final_url: str


def classify_probe(
    name: str,
    url: str,
    method: str = "GET",
    body: str | None = None,
    extra_headers: dict[str, str] | None = None,
) -> ProbeResult:
    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/142.0.0.0 Mobile Safari/537.36",
        "Accept-Language": "en-US,en;q=0.9",
    }
    if extra_headers:
        headers.update(extra_headers)
    data = body.encode("utf-8") if body is not None else None
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            latency = int((time.monotonic() - start) * 1000)
            snippet = resp.read(2048).decode("utf-8", errors="ignore")
            detail = "OK"
            if name == "AniList GraphQL API" and '"media"' not in snippet:
                return ProbeResult(name, url, "DEGRADED", resp.status, latency, "Unexpected GraphQL response", resp.geturl())
            return ProbeResult(name, url, "HEALTHY", resp.status, latency, detail, resp.geturl())
    except urllib.error.HTTPError as exc:
        latency = int((time.monotonic() - start) * 1000)
        resp_body = exc.read(4096).decode("utf-8", errors="ignore").lower()
        server = (exc.headers.get("Server") or "").lower()
        cf_mitigated = (exc.headers.get("cf-mitigated") or "").lower()

        if "sorry, you have been blocked" in resp_body or "you are unable to access" in resp_body or "error 1020" in resp_body:
            return ProbeResult(
                name,
                url,
                "IP_BLOCKED",
                exc.code,
                latency,
                f"Server/Cloudflare WAF blocked client IP (HTTP {exc.code}, server={server or 'unknown'})",
                exc.geturl(),
            )
        if cf_mitigated == "challenge" or "just a moment" in resp_body or "challenge-platform" in resp_body:
            return ProbeResult(
                name,
                url,
                "CLOUDFLARE_CHALLENGE",
                exc.code,
                latency,
                f"Cloudflare bot challenge active (HTTP {exc.code})",
                exc.geturl(),
            )
        # OAuth authorize without client_id/redirect returns 400/302 which still proves endpoint is up
        if name == "AniList OAuth Endpoint" and exc.code in (400, 401):
            return ProbeResult(name, url, "HEALTHY", exc.code, latency, "OAuth endpoint reachable", exc.geturl())
        return ProbeResult(name, url, "ERROR", exc.code, latency, f"HTTP {exc.code}: {exc.reason}", exc.geturl())
    except Exception as exc:
        latency = int((time.monotonic() - start) * 1000)
        return ProbeResult(name, url, "UNREACHABLE", 0, latency, f"{type(exc).__name__}: {exc}", url)


def discover_repo_urls(root: Path) -> list[str]:
    found: set[str] = set()
    scan_dirs = [root / "core", root / "feature", root / "app", root / "README.md"]
    for target in scan_dirs:
        if target.is_file():
            files = [target]
        elif target.is_dir():
            files = [p for p in target.rglob("*") if p.suffix in {".kt", ".xml", ".md", ".kts"}]
        else:
            continue
        for f in files:
            text = f.read_text(encoding="utf-8", errors="ignore")
            for match in URL_RE.findall(text):
                cleaned = match.rstrip(".,;:")
                if "$" in cleaned or "{" in cleaned or "..." in cleaned:
                    continue
                if any(cleaned.startswith(p) for p in SKIP_PREFIXES):
                    continue
                # Skip DoH base endpoints already tested with DNS query params in CORE_PROBES
                if cleaned in {p["url"] for p in CORE_PROBES} or cleaned.endswith("/dns-query"):
                    continue
                found.add(cleaned)
    return sorted(found)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", default=".")
    parser.add_argument("--json-out", default="")
    parser.add_argument("--md-out", default="")
    args = parser.parse_args()

    results: list[ProbeResult] = []
    advisories: list[ProbeResult] = []
    for probe in CORE_PROBES:
        res = classify_probe(
            name=probe["name"],
            url=probe["url"],
            method=probe.get("method", "GET"),
            body=probe.get("body"),
            extra_headers=probe.get("headers"),
        )
        if probe.get("advisory_only"):
            advisories.append(res)
        else:
            results.append(res)

    for extra_url in discover_repo_urls(Path(args.root)):
        results.append(classify_probe(name=f"Codebase link ({extra_url})", url=extra_url))

    unhealthy = [r for r in results if r.status != "HEALTHY"]

    md_lines = [
        "### Domain, Link & Service Health Report",
        "",
        "| Service / Endpoint | Status | HTTP | Latency | Details |",
        "| --- | --- | --- | --- | --- |",
    ]
    for r in results:
        md_lines.append(f"| {r.name} | `{r.status}` | {r.http_code} | {r.latency_ms}ms | {r.detail} |")

    if advisories:
        md_lines.extend([
            "",
            "#### External Source Advisories (CI Datacenter Probe)",
            "",
            "| Source / Domain | Status | HTTP | Latency | Mitigation in Tubiac |",
            "| --- | --- | --- | --- | --- |",
        ])
        for a in advisories:
            mitigation = (
                "Handled client-side via DoH + Custom User-Agent + WebView Solver (`IpBlockedException`)"
                if a.status in ("IP_BLOCKED", "CLOUDFLARE_CHALLENGE")
                else a.detail
            )
            md_lines.append(f"| {a.name} | `{a.status}` | {a.http_code} | {a.latency_ms}ms | {mitigation} |")

    md_text = "\n".join(md_lines) + "\n"
    payload = {
        "healthy_count": len(results) - len(unhealthy),
        "unhealthy_count": len(unhealthy),
        "has_issues": bool(unhealthy),
        "results": [asdict(r) for r in results],
        "advisories": [asdict(a) for a in advisories],
    }

    if args.json_out:
        Path(args.json_out).write_text(json.dumps(payload, indent=2), encoding="utf-8")
    if args.md_out:
        Path(args.md_out).write_text(md_text, encoding="utf-8")

    print(md_text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
