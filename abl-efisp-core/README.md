# abl-efisp-core

This release derives from the paired Freedom 1.2.5 / fakebl 1.2.7 core. It adds a separately registered `oem superunlock` command following BLTools' one-way fresh-read/modify/write behavior, plus independent readback. Existing ordinary-flashing-unlock patch bytes and mode policies are retained for the validated original layouts. Functional discovery derives the command registration, native protocol wrapper, buffer size and flag offsets from each image.

Existing mapped RX space is preferred. When it is insufficient, a new aligned RX section is appended without moving old bytes/RVAs; the section count and image/code sizes are updated. This preserves old text-bearing images and supports re-import. Firmware matching uses no model/version/address table and does not reinstate the removed reference-risk audit.

See ../INTEGRATION.md for API examples and SOURCE-PROVENANCE.json for current production/test hashes and baseline differences. LegacyFeatureView exists only in src/test; it does not alter the actual public API or APK output. DeviceInfo protocol success does not establish OEMINFO authentication-lock changes, TEE key state or a complete hardware boot.
