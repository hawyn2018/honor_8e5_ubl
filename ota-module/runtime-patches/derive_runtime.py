#!/usr/bin/env python3
"""Derive the pinned hnubl KSU-provider Frida variant using only the stdlib.

The original download remains an upstream artifact. This recipe fails closed
on any input change and has no fallback to global SELinux policy rewriting.
"""
from __future__ import annotations

import hashlib
import struct

VARIANT_ID = "hnubl-ksu-provider-no-global-policy-v1"
UPSTREAM_BYTES = 84601832
UPSTREAM_ELF_SHA256 = "1d3374cdbb73f3fe4737f7e7a77e6d17e19914f16d1bffbd6c42999800f8dbeb"
UPSTREAM_XZ_SHA256 = "987d0a900cf1bc3878ba8b34b00ad2889e55e4ba26a8da7140a881f652982a73"
DERIVED_ELF_SHA256 = "3db476d1cfb390f4feab17ca4463c047c2007b71d1f62873f539a5e6523a802b"
PROVIDER_CONTEXT = "u:object_r:ksu_file:s0"

INIT_OFFSET = 0x9181C4
INIT_BYTES = bytes.fromhex("fd7bbfa9fd030091e0031f2aed730795fd7bc1a84b760715")
INIT_SHA256 = "8b98c8ef5cc55b2c5eed8603c54897c19b00c309c869887907192f44da65a8e1"
TAIL_OFFSET = INIT_OFFSET + 20
TAIL_BEFORE = bytes.fromhex("4b760715")
TAIL_AFTER = bytes.fromhex("c0035fd6")
POLICY_OFFSET = 0x4AF5B04
POLICY_BYTES = 1204
POLICY_SHA256 = "f6f88c187787219f7e08963ae6a19e02353a6ae118b333f747585de52a2387ea"
LABEL_OFFSET = 0x15D4D4
LABEL_BEFORE = b"u:object_r:frida_memfd:s0\0"
LABEL_AFTER = PROVIDER_CONTEXT.encode("ascii") + b"\0"
LABEL_AFTER += b"\0" * (len(LABEL_BEFORE) - len(LABEL_AFTER))
SETTER_OFFSET = 0x452C18C
SETTER_BYTES = bytes.fromhex("002040b981e1fdb021501391d5532914")
SETTER_SHA256 = "89c602c29c5ab3080232ebf52d8de2cb6b774704192f02fa77f547d55cc23f66"
OTHER_CONTEXT = b"u:object_r:frida_file:s0\0"


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def need(value, message):
    if not value:
        raise RuntimeError("Frida runtime variant rejected: " + message)


def _sections(data):
    need(data[:6] == b"\x7fELF\x02\x01", "expected ELF64 little-endian")
    need(struct.unpack_from("<HHI", data, 16) == (3, 183, 1), "ELF type, architecture or version changed")
    section_offset = struct.unpack_from("<Q", data, 40)[0]
    entry_bytes, count, strings_index = struct.unpack_from("<HHH", data, 58)
    need(entry_bytes == 64 and count > 0 and strings_index < count, "section table shape changed")
    need(section_offset + count * entry_bytes <= len(data), "section table out of bounds")
    records = [struct.unpack_from("<IIQQQQIIQQ", data, section_offset + i * entry_bytes) for i in range(count)]
    strings = records[strings_index]
    need(strings[4] + strings[5] <= len(data), "section names out of bounds")
    names = data[strings[4]:strings[4] + strings[5]]
    result = {}
    for rec in records:
        need(rec[0] < len(names), "bad section name offset")
        end = names.find(b"\0", rec[0])
        need(end >= 0, "unterminated section name")
        name = names[rec[0]:end].decode("ascii")
        if name in (".text", ".rodata"):
            need(name not in result and rec[4] + rec[5] <= len(data), "duplicate or truncated target section")
            result[name] = {"flags": rec[2], "address": rec[3], "offset": rec[4], "bytes": rec[5]}
    need(set(result) == {".text", ".rodata"}, "target sections missing")
    return result


def _validate_structure(data):
    """Secondary guards, also exercised independently by structural negatives."""
    need(len(data) == UPSTREAM_BYTES, "ELF length changed")
    sections = _sections(data)
    text, rodata = sections[".text"], sections[".rodata"]
    need(text["address"] == text["offset"] == 0x912E00 and text["bytes"] == 74207632, "text layout changed")
    need(text["flags"] & 4 and not (rodata["flags"] & 4), "section permissions changed")
    need(rodata["address"] == rodata["offset"] == 0x131000 and rodata["bytes"] == 6279308, "rodata layout changed")
    need(rodata["offset"] <= LABEL_OFFSET and LABEL_OFFSET + len(LABEL_BEFORE) <= rodata["offset"] + rodata["bytes"], "context outside rodata")
    need(data[INIT_OFFSET:INIT_OFFSET + len(INIT_BYTES)] == INIT_BYTES, "initializer instructions changed")
    need(sha256(data[INIT_OFFSET:INIT_OFFSET + len(INIT_BYTES)]) == INIT_SHA256, "initializer identity changed")
    need(sha256(data[POLICY_OFFSET:POLICY_OFFSET + POLICY_BYTES]) == POLICY_SHA256, "policy function identity changed")
    need(data[SETTER_OFFSET:SETTER_OFFSET + len(SETTER_BYTES)] == SETTER_BYTES and
         sha256(data[SETTER_OFFSET:SETTER_OFFSET + len(SETTER_BYTES)]) == SETTER_SHA256, "memfd label setter changed")
    need(data.count(LABEL_BEFORE) == 1 and data[LABEL_OFFSET:LABEL_OFFSET + len(LABEL_BEFORE)] == LABEL_BEFORE, "memfd context is not the unique expected literal")
    need(data.count(OTHER_CONTEXT) == 3, "other file-context literals changed")
    callers = []
    words = memoryview(data)[text["offset"]:text["offset"] + text["bytes"]]
    for i, (word,) in enumerate(struct.iter_unpack("<I", words)):
        if word & 0x7C000000 == 0x14000000:
            immediate = word & 0x03FFFFFF
            if immediate & 0x02000000:
                immediate -= 0x04000000
            pc = text["address"] + i * 4
            if pc + immediate * 4 == POLICY_OFFSET:
                callers.append(pc)
    need(callers == [TAIL_OFFSET], "global policy function caller is not the unique initializer tail")
    return sections


def derive(upstream):
    """Return (derived ELF bytes, portable provenance) or raise; never original ELF."""
    need(len(upstream) == UPSTREAM_BYTES and sha256(upstream) == UPSTREAM_ELF_SHA256,
         "full official input SHA-256 mismatch")
    sections = _validate_structure(upstream)
    output = bytearray(upstream)
    output[TAIL_OFFSET:TAIL_OFFSET + len(TAIL_AFTER)] = TAIL_AFTER
    output[LABEL_OFFSET:LABEL_OFFSET + len(LABEL_AFTER)] = LABEL_AFTER
    changes = [{"offset": i, "before": a, "after": b}
               for i, (a, b) in enumerate(zip(upstream, output)) if a != b]
    allowed = set(range(TAIL_OFFSET, TAIL_OFFSET + 4)) | set(range(LABEL_OFFSET, LABEL_OFFSET + len(LABEL_BEFORE)))
    need(len(changes) == 17 and all(c["offset"] in allowed for c in changes), "unexpected output byte differences")
    need(len(output) == len(upstream) and sha256(output) == DERIVED_ELF_SHA256, "derived ELF identity mismatch")
    need(output.count(OTHER_CONTEXT) == 3 and
         output[SETTER_OFFSET:SETTER_OFFSET + len(SETTER_BYTES)] == SETTER_BYTES, "unrelated label path changed")
    proof = {
        "schema": 1, "id": VARIANT_ID, "upstreamVersion": "17.25.0",
        "upstreamUrl": "https://github.com/frida/frida/releases/download/17.25.0/frida-inject-17.25.0-android-arm64.xz",
        "upstreamElfSha256": UPSTREAM_ELF_SHA256, "upstreamXzSha256": UPSTREAM_XZ_SHA256,
        "derivedElfSha256": DERIVED_ELF_SHA256, "elfBytes": len(output),
        "requiredProviderContext": PROVIDER_CONTEXT,
        "globalPolicyReloadEnabled": False, "originalRuntimeFallbackEnabled": False,
        "sections": sections, "initializerSha256": INIT_SHA256,
        "policyFunctionSha256": POLICY_SHA256, "memfdLabelSetterSha256": SETTER_SHA256,
        "uniqueDirectPolicyCallers": [TAIL_OFFSET], "changes": changes,
        "sourceUrls": [
            "https://github.com/frida/frida-core/blob/17.25.0/inject/inject-glue.c",
            "https://github.com/frida/frida-core/blob/17.25.0/src/linux/linjector.vala",
            "https://github.com/frida/frida-core/blob/17.25.0/lib/selinux/patch.c"],
    }
    return bytes(output), proof
