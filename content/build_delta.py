#!/usr/bin/env python3
"""Build a verified prefix/suffix delta between deterministic content bundles."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path


SCHEMA = "weibian-content-delta-v1"
DEFAULT_ORIGIN = "https://weibian.bdfz.net"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def common_prefix(left: bytes, right: bytes) -> int:
    limit = min(len(left), len(right))
    index = 0
    while index < limit and left[index] == right[index]:
        index += 1
    return index


def common_suffix(left: bytes, right: bytes, prefix: int) -> int:
    limit = min(len(left) - prefix, len(right) - prefix)
    count = 0
    while count < limit and left[-1 - count] == right[-1 - count]:
        count += 1
    return count


def build_patch(source: bytes, target: bytes) -> tuple[bytes, dict[str, object]]:
    source_hash = sha256(source)
    target_hash = sha256(target)
    if source_hash == target_hash:
        raise ValueError("source and target content are identical")

    prefix = common_prefix(source, target)
    suffix = common_suffix(source, target, prefix)
    replacement = target[prefix : len(target) - suffix if suffix else len(target)]
    patch = {
        "schema": SCHEMA,
        "fromSha256": source_hash,
        "toSha256": target_hash,
        "prefixBytes": prefix,
        "suffixBytes": suffix,
        "replacementBase64": base64.b64encode(replacement).decode("ascii"),
    }
    patch_bytes = json.dumps(
        patch,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")

    decoded = base64.b64decode(patch["replacementBase64"], validate=True)
    rebuilt = source[:prefix] + decoded + (source[len(source) - suffix :] if suffix else b"")
    if rebuilt != target or sha256(rebuilt) != target_hash:
        raise ValueError("delta self-check failed")
    return patch_bytes, patch


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--from-content", type=Path, required=True)
    parser.add_argument("--to-content", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--origin", default=DEFAULT_ORIGIN)
    parser.add_argument("--update-manifest", type=Path)
    parser.add_argument("--allow-larger-than-full", action="store_true")
    args = parser.parse_args()

    source = args.from_content.read_bytes()
    target = args.to_content.read_bytes()
    patch_bytes, patch = build_patch(source, target)
    if len(patch_bytes) >= len(target) and not args.allow_larger_than_full:
        raise SystemExit("delta is not smaller than the full target; publish the full bundle only")

    from_hash = str(patch["fromSha256"])
    to_hash = str(patch["toSha256"])
    filename = f"{from_hash[:8]}-{to_hash[:8]}.json"
    args.output_dir.mkdir(parents=True, exist_ok=True)
    output = args.output_dir / filename
    output.write_bytes(patch_bytes)
    descriptor = {
        "fromSha256": from_hash,
        "toSha256": to_hash,
        "sha256": sha256(patch_bytes),
        "size": len(patch_bytes),
        "url": f"{args.origin.rstrip('/')}/api/content/deltas/{filename}",
    }

    if args.update_manifest:
        manifest = json.loads(args.update_manifest.read_text(encoding="utf-8"))
        if manifest.get("sha256") != to_hash or manifest.get("size") != len(target):
            raise SystemExit("target manifest does not match --to-content")
        deltas = [
            item
            for item in manifest.get("deltas", [])
            if item.get("fromSha256") != from_hash
        ]
        deltas.append(descriptor)
        manifest["deltas"] = deltas
        args.update_manifest.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )

    print(json.dumps({"output": str(output), "descriptor": descriptor}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
