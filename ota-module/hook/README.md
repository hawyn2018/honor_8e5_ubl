# OUC process agent

Build `src/agent.js` with the pinned `frida-java-bridge` dependency as an IIFE
for Frida's JavaScript runtime. `rpc.exports.init(stage, {mapping})` installs a
schema-1 discovery mapping. The target package and `/proc/self/cmdline` are checked
before any hook is installed. No other application's process is modified.

The root controller receives `send({event:'ready', ...})` only after all discovered
critical entries have been installed, required feature roles have coverage and
a read-only STATE request to the private root broker succeeds. Failures use
`event:'error'`. Optional unused payload ABI variants can fail to bind while an
actual alternative payload-reset path remains available. A controller must not
interpret a partially installed agent as ready, and should stop/restart the OUC
process when activation fails rather than leaving a claimed protected session.

The broker socket is the OUC-private filesystem endpoint
`/data/user_de/0/com.hihonor.ouc/files/hnubl-module/ota.sock`. The agent accepts only
a UID-0 server. STATE is read-only; RESET starts a new update cycle at zero;
CONSUME is the sole atomic permission consumer. Replies are JSON objects with
strict boolean `ok`, numeric `state`, and `decision:'ALLOW'|'DENY'`.

An automatic or unclassified reboot reason never consumes permission. At the
early manual entry, DENY skips the entire original function and opens hnubl's
OTA page via its explicit `open_ota=true` intent. ALLOW calls the saved original
overload once after the broker has durably replaced 1 with 0. Synchronous nested
entries on that same original call path do not consume twice. No synthetic
reboot command replaces the updater's original behavior.

The async continuation can call a saved Java overload outside its original ART
replacement frame. Virtual dispatch may then enter the hook again. A synchronous
scope matches that immediate self-entry by thread, overload, actual Java receiver
and arguments (a static overload has no instance receiver), and calls the original from inside the replacement frame. The
scope is consumed once and removed on return or exception; it stores no file
permission and cannot authorize a later click or another thread. This preserves
the single final CONSUME and the official slot operation while allowing the real
PowerManager reboot method to execute after background preparation.

Payload callbacks do not reset permission merely because the completed page is
shown again. A reset failure keeps the manual gate closed and reports the error;
the next manual click retries the reset and redirects to hnubl for preservation.

`test/policy.test.mjs` exercises side-effect-free permission decisions. Runtime
inspection is read-only through `rpc.exports.inspect`. Neither inspection nor
initialization invokes an update, applies a payload or calls the reboot entry.
