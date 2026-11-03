# Offline discovery compatibility regression

`DiscoveryCompatibilityTest APK NEW_OUTPUT_DIRECTORY` derives mutation targets
from the locator's own successful output. It renames opaque application target
classes and eligible non-override methods, uses DexRewriter to update definitions
and invoke/type references, reverses source class enumeration before valid DEX
serialization, and reverses the APK ZIP's DEX-entry order. Android/framework
types and protocol/override method names are preserved.

It then runs the unmodified production locator against the original and mutated
containers, checking exact role counts, rediscovery under the new names,
per-method invoke-reference counts and original-APK SHA-256 integrity. Rewritten
DEX class tables remain canonically sorted as the format requires; renamed types
nevertheless change their actual ordering and indexes.

The output APK is an **unsigned offline fixture** with original signatures
removed, labelled `TEST-ONLY-...-UNSIGNED.apk`. Do not install it. It is not an
earlier vendor updater and is not a runtime or instruction-flow compatibility
test. It establishes that the tested locator rules do not depend on those
original obfuscated target names or DEX ZIP-entry order.

Compile the test and production `OucDiscovery.java` with dexlib2 plus Gson on
the host class path. The repository's local JADX all-in-one JAR contains both.
The recorded regression used a host JVM with `-Xms64m -Xmx512m`; this is only a
test process budget and does not change device or application memory settings.
