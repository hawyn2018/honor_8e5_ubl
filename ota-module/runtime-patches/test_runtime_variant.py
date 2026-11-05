#!/usr/bin/env python3
"""Offline build guards for the pinned runtime variant; no device operations."""
import hashlib
import lzma
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
import derive_runtime as variant

ROOT = Path(__file__).resolve().parents[1]


class RuntimeVariantGuards(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        compressed = (ROOT / "vendor/frida-inject-17.25.0-android-arm64.xz").read_bytes()
        if hashlib.sha256(compressed).hexdigest() != variant.UPSTREAM_XZ_SHA256:
            raise RuntimeError("Official compressed input changed")
        cls.upstream = lzma.decompress(compressed)

    def test_exact_output_and_reproducibility(self):
        first, proof = variant.derive(self.upstream)
        second, again = variant.derive(self.upstream)
        self.assertEqual(first, second)
        self.assertEqual(proof, again)
        self.assertEqual(hashlib.sha256(first).hexdigest(), variant.DERIVED_ELF_SHA256)
        self.assertEqual(len(proof["changes"]), 17)
        self.assertFalse(proof["globalPolicyReloadEnabled"])
        self.assertFalse(proof["originalRuntimeFallbackEnabled"])
        with self.assertRaisesRegex(RuntimeError, "full official input"):
            variant.derive(first)

    def test_changed_or_truncated_input_rejected(self):
        for data in (self.upstream[:-1], bytes([self.upstream[0] ^ 1]) + self.upstream[1:]):
            with self.assertRaisesRegex(RuntimeError, "full official input"):
                variant.derive(data)

    def test_structural_guard_negatives(self):
        # Independently exercise local guards after bypassing only the full-file
        # hash gate in this test. The production derive() never bypasses it.
        for offset, diagnostic in (
            (variant.INIT_OFFSET + 8, "initializer"),
            (variant.TAIL_OFFSET, "initializer"),
            (variant.POLICY_OFFSET + 80, "policy function"),
            (variant.SETTER_OFFSET + 8, "label setter"),
            (variant.LABEL_OFFSET + 11, "unique expected literal"),
        ):
            with self.subTest(offset=hex(offset)):
                damaged = bytearray(self.upstream)
                damaged[offset] ^= 1
                with self.assertRaisesRegex(RuntimeError, diagnostic):
                    variant._validate_structure(damaged)

    def test_additional_policy_caller_rejected(self):
        import struct
        damaged = bytearray(self.upstream)
        caller = 0x912E00
        word = 0x14000000 | (((variant.POLICY_OFFSET - caller) // 4) & 0x03FFFFFF)
        struct.pack_into("<I", damaged, caller, word)
        with self.assertRaisesRegex(RuntimeError, "unique initializer tail"):
            variant._validate_structure(damaged)


if __name__ == "__main__":
    unittest.main(verbosity=2)
