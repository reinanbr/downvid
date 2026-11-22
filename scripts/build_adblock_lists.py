#!/usr/bin/env python3
"""Builds the ad blocker's engine the way Brave Shields does.

Lists: every default-enabled list of Brave's catalog
(brave/adblock-resources filter_lists/list_catalog.json; lists for other
platforms excluded) plus the regional lists for LANGS, each with the
scriptlet permissions the catalog grants it.
Resources: uBlock Origin's redirect resources and scriptlets plus Brave's
resources.json, assembled as brave-core-crx-packager does.
Compiled with adblock-rust (adblock/, dvadblock-compile) into:

  android/app/src/main/assets/adblock/engine.dat      serialized engine
  android/app/src/main/assets/adblock/resources.json  scriptlets + redirects

Usage: scripts/build_adblock_lists.py [--lists-only | --resources-only]
       (needs cargo, node >= 22, git; network)
"""
import json
import os
import subprocess
import sys
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "android", "app", "src", "main", "assets", "adblock")
CATALOG = "https://raw.githubusercontent.com/brave/adblock-resources/master/filter_lists/list_catalog.json"
BRAVE_RESOURCES = "https://raw.githubusercontent.com/brave/adblock-resources/master/dist/resources.json"
UBLOCK = "https://github.com/gorhill/uBlock.git"
LANGS = {"pt"}


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "dvadblock-lists/1"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def wanted(entry: dict) -> bool:
    platforms = entry.get("platforms")
    if platforms and "ANDROID" not in platforms:
        return False
    return bool(entry.get("default_enabled")) or bool(LANGS & set(entry.get("langs") or []))


def compile_tool(*args: str) -> None:
    subprocess.run(
        ["cargo", "run", "--quiet", "--release", "--features", "tools", "--bin", "dvadblock-compile", "--", *args],
        cwd=os.path.join(ROOT, "adblock"), check=True,
    )


def lists(tmp: str) -> list:
    d = os.path.join(tmp, "lists")
    os.makedirs(d)
    args = []
    for entry in filter(wanted, json.loads(fetch(CATALOG))):
        mask = int(entry.get("permission_mask") or 0)
        print(f"{entry['title']} (permissions {mask})")
        for src in entry["sources"]:
            data = fetch(src["url"])
            p = os.path.join(d, f"{len(args):02d}.txt")
            with open(p, "wb") as f:
                f.write(data)
            args.append(f"{mask}:{p}")
            print(f"  {len(data):>9} {src['url']}")
    return args


def ubo_scriptlets(ubo: str, out: str) -> None:
    script = os.path.join(ROOT, "scripts", "ubo_scriptlets.mjs")
    entry = os.path.join(ubo, "src", "js", "resources", "scriptlets.js")
    # uBO's modules run sandboxed: read-only, no network, no subprocesses.
    res = subprocess.run(
        ["node", "--permission", f"--allow-fs-read={ubo}", f"--allow-fs-read={script}", script, entry],
        check=True, capture_output=True,
    )
    with open(out, "wb") as f:
        f.write(res.stdout)


def main() -> int:
    os.makedirs(OUT, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="dvadblock-") as tmp:
        if "--resources-only" not in sys.argv:
            compile_tool("engine", os.path.join(OUT, "engine.dat"), *lists(tmp))
        if "--lists-only" in sys.argv:
            return 0

        ubo = os.path.join(tmp, "uBlock")
        subprocess.run(["git", "clone", "--quiet", "--depth", "1", "--filter=blob:none", "--sparse", UBLOCK, ubo], check=True)
        subprocess.run(["git", "-C", ubo, "sparse-checkout", "set", "--no-cone",
                        "/src/js/resources/", "/src/js/redirect-resources.js", "/src/web_accessible_resources/"],
                       check=True)
        scriptlets = os.path.join(tmp, "ubo-scriptlets.json")
        ubo_scriptlets(ubo, scriptlets)
        brave = os.path.join(tmp, "brave-resources.json")
        with open(brave, "wb") as f:
            f.write(fetch(BRAVE_RESOURCES))
        compile_tool("resources", os.path.join(OUT, "resources.json"),
                     os.path.join(ubo, "src", "web_accessible_resources"),
                     os.path.join(ubo, "src", "js", "redirect-resources.js"), brave, scriptlets)
    return 0


if __name__ == "__main__":
    sys.exit(main())
