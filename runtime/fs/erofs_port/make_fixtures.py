#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Generate synthetic EROFS images using user-specified upstream host tools.

Only Python's standard library is used. No system mounts, devices, downloads,
privileges, or host binaries are embedded by this script. Output is test data.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import struct
import subprocess
import tarfile

FORMATS = (
    ("uncompressed", ()),
    ("lz4-compact", ("-zlz4",)),
    ("lz4-full", ("-zlz4", "-Elegacy-compress")),
    ("lz4-bigpcluster", ("-zlz4", "-C65536")),
    ("lz4-ztail", ("-zlz4", "-Eztailpacking")),
    ("lz4-fragments", ("-zlz4", "-Efragments")),
    ("lz4-all-fragments", ("-zlz4", "-Eall-fragments")),
    ("lz4-mixed", ("-zlz4", "-C65536", "-Eztailpacking,fragments")),
    ("lz4hc", ("-zlz4hc,12",)),
    ("uncompressed-1k", ("-b1024",)),
    ("uncompressed-16k", ("-b16384",)),
)
FIXED_UUID = "00000000-0000-0000-0000-000000000001"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def source_files() -> dict[str, bytes]:
    files = {
        "hello.txt": b"Hello EROFS / KBL\n",
        "empty": b"",
        "tiny": b"T",
        "tail.txt": b"short-tail-123456789\n" * 73 + b"END",
        "dir/deep/large.txt": (b"0123456789ABCDEF KBL EROFS LZ4 test data\n" * 8192)[:262144] + b"TAIL" * 79 + b"!",
        "dir/deep/empty": b"",
        "目录/文件.txt": "你好，EROFS 挂载测试。\n第二行。\n".encode("utf-8"),
        "space name.txt": b"space filename\n",
        "long-" + "x" * 190 + ".txt": b"long filename\n",
        "zeros.bin": bytes(131071),
        "noise.bin": b"".join(hashlib.sha256(i.to_bytes(4, "little")).digest() for i in range(2200))[:70001],
    }
    for i in range(8):
        files[f"fragments/piece-{i}.txt"] = (f"fragment-{i}:".encode("ascii") + b"ABCDEF0123456789" * 300)[:113 + i * 377]
    return files


def create_tar(output: Path) -> dict:
    files = source_files()
    directories: set[str] = set()
    for name in files:
        parent = Path(name).parent
        while str(parent) != ".":
            directories.add(parent.as_posix())
            parent = parent.parent
    symlinks = {"hello-link": "hello.txt", "dir/deep/up-link": "../../hello.txt"}
    for index in range(24):symlinks[f'chain-{index:02d}']=f'chain-{index+1:02d}'if index<23 else 'hello.txt'
    symlinks.update({'root-link':'/','cycle-a':'cycle-b','cycle-b':'cycle-a','grow-link':'grow-link/x'})
    special={'/root-link':'root','/cycle-a':'cycle','/cycle-b':'cycle','/grow-link':'cycle'}
    archive = output / "source.tar"
    with tarfile.open(archive, "w", format=tarfile.PAX_FORMAT) as stream:
        for name in sorted(directories):
            info = tarfile.TarInfo(name + "/")
            info.type, info.mode, info.mtime = tarfile.DIRTYPE, 0o755, 0
            stream.addfile(info)
        for name, data in sorted(files.items()):
            info = tarfile.TarInfo(name)
            info.mode, info.mtime, info.size = 0o644, 0, len(data)
            stream.addfile(info, io.BytesIO(data))
        for name, target in symlinks.items():
            info = tarfile.TarInfo(name)
            info.type, info.mode, info.mtime, info.linkname = tarfile.SYMTYPE, 0o777, 0, target
            stream.addfile(info)
    expected = {
        "sourceTarSha256": sha256(archive.read_bytes()),
        "directories": ["/"] + ["/" + name for name in sorted(directories)],
        "files": {"/" + name: {"bytes": len(data), "sha256": sha256(data)} for name, data in sorted(files.items())},
        "symlinks": {"/" + name: target for name, target in symlinks.items()},
        "symlinkSpecial": special,
    }
    (output / "expected.json").write_text(json.dumps(expected, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for name, data in files.items():
        path = output / "file-content" / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    return expected


def invoke(command: list[str]) -> None:
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace")
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {command!r}\n{result.stdout}{result.stderr}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="Directory for generated synthetic test files; existing matching files are replaced")
    parser.add_argument("--mkfs", type=Path, help="User-built official mkfs.erofs executable")
    parser.add_argument("--fsck", type=Path, help="User-built official fsck.erofs executable")
    parser.add_argument("--tar-only", action="store_true", help="Generate input/expected files without invoking host tools")
    arguments = parser.parse_args()
    if not arguments.tar_only and (not arguments.mkfs or not arguments.fsck):
        parser.error("--mkfs and --fsck are required unless --tar-only is selected")
    for tool in (arguments.mkfs, arguments.fsck):
        if tool and not tool.is_file():
            parser.error(f"Tool does not exist: {tool}")
    output = arguments.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    expected = create_tar(output)
    images = {}
    if not arguments.tar_only:
        for name, options in FORMATS:
            image = output / (name + ".img")
            command = [str(arguments.mkfs.resolve()), "-b4096", "-T0", "-U" + FIXED_UUID, "--tar=f", *options, str(image), str(output / "source.tar")]
            invoke(command)
            # --extract without a directory validates all inode data, including
            # decompression, without creating platform-dependent host symlinks.
            invoke([str(arguments.fsck.resolve()), "--extract", str(image)])
            data = image.read_bytes()
            if len(data) < 1152 or struct.unpack_from("<I", data, 1024)[0] != 0xE0F5E1E2:
                raise RuntimeError(f"Invalid EROFS output: {image}")
            images[image.name] = {
                "bytes": len(data), "sha256": sha256(data), "blockBits": data[1036],
                "compat": struct.unpack_from("<I", data, 1032)[0],
                "incompat": struct.unpack_from("<I", data, 1104)[0],
                "officialFsckExtractPassed": True, "mkfsOptions": command[1:-2],
            }
            print(f"Verified {image.name}: {len(data)} bytes")
        (output / "images.json").write_text(json.dumps(images, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"files": len(expected["files"]), "directories": len(expected["directories"]), "symlinks": len(expected["symlinks"]), "images": len(images), "output": str(output)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
