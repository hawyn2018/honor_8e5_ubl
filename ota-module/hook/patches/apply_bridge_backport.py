"""Apply the official API-37 backport to a fresh dependency working copy only."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import difflib

HERE = Path(__file__).resolve().parent
COMMIT = "67f1b1116e8b827014825a46693929221136b0c8"
BASE = "ee119401893ab7353525fe181c99554cd13776c10a47e24ebf29c2d0de28a11c"
AFTER = "f9807d3357fe7743e411fdea5e93313004edf50372f055c2ab86eb513680060f"
UPSTREAM_PATCH = "ff67d52d60fe9024dbd2d36f376c29b3ad2e21b7c7e41e989f2a1849899328e7"
LOCAL_DIFF = "c9a6936687db7410e540dd34b62db55439b4d2406294b01344c3505c26053d95"
POLICY_DIFF = "a9723d77f1f1a2d9a8bc5b5517469271b493e05b1fb09f01876130be782bc739"
POLICY_FILES = {
    "lib/android.js": (AFTER, "656f42dab10a2a21bef590a26b7cc333edb44601c276591fcf0e0b232451d7db"),
    "lib/class-model.js": ("48f6b9da88ead79b109358fb7866aa5d10be1711c8a4a7693f876397b22b5010",
                           "395aa28978b17a10a5b6996365e9e40a172a89bf552bf2eba6601663127dab4a")
}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def need(value, message):
    if not value:
        raise RuntimeError(message)


def tree(directory):
    result = {}
    for file in sorted(Path(directory).rglob("*")):
        need(not file.is_symlink(), "Symlink rejected in bridge dependency")
        if file.is_file():
            result[file.relative_to(directory).as_posix()] = sha(file.read_bytes())
    return result


def apply_hunks(original, patch):
    text = original.decode("utf-8")
    hunks = []
    current = None
    expected = None
    for line in patch.decode("utf-8").splitlines(keepends=True):
        if line.startswith("@@ "):
            match = re.match(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", line)
            need(match is not None, "Malformed bridge patch hunk")
            current = []
            expected = (int(match.group(2) or 1), int(match.group(4) or 1))
            hunks.append((current, expected))
        elif current is not None and line[:1] in (" ", "+", "-") and not line.startswith(("---", "+++")):
            current.append(line)
        elif current is not None:
            need(not line.strip(), "Unexpected trailing bridge patch content")
    need(len(hunks) == 3, "Expected exactly three hunks for two upstream fixes")
    for lines, (old_count, new_count) in hunks:
        old = "".join(line[1:] for line in lines if line[0] in " -")
        new = "".join(line[1:] for line in lines if line[0] in " +")
        need(sum(line[0] in " -" for line in lines) == old_count, "Old hunk length mismatch")
        need(sum(line[0] in " +" for line in lines) == new_count, "New hunk length mismatch")
        need(old and text.count(old) == 1, "Bridge patch context must match exactly once")
        text = text.replace(old, new, 1)
    return text.encode("utf-8")


def prepare(bridge_package, destination):
    bridge_package = Path(bridge_package).resolve()
    destination = Path(destination).resolve()
    need(not destination.exists(), "Bridge working-copy destination must be new")
    metadata = json.loads((HERE / "bridge-backport.json").read_text(encoding="utf-8"))
    need(metadata.get("upstreamCommit") == COMMIT and metadata.get("baseVersion") == "7.0.13" and
         metadata.get("runtimeVersionUnchanged") == "17.25.0" and metadata.get("originalSha256") == BASE and
         metadata.get("patchedSha256") == AFTER and metadata.get("upstreamPatchSha256") == UPSTREAM_PATCH and
         metadata.get("localDiffSha256") == LOCAL_DIFF, "Bridge backport metadata identity mismatch")
    package = json.loads((bridge_package / "package.json").read_text(encoding="utf-8"))
    need(package.get("version") == "7.0.13" and package.get("license") == metadata["license"], "Bridge package identity/license mismatch")
    source_file = bridge_package / "lib/android.js"
    original = source_file.read_bytes()
    need(sha(original) == BASE, "Original bridge differs from the pinned npm package")
    upstream = (HERE / "upstream-67f1b111.patch").read_bytes()
    local = (HERE / "bridge-7.0.13-api37.diff").read_bytes()
    need(sha(upstream) == UPSTREAM_PATCH and sha(local) == LOCAL_DIFF, "Official/local bridge patch hash mismatch")
    patched = apply_hunks(original, upstream)
    need(patched == apply_hunks(original, local) and sha(patched) == AFTER, "Official and local bridge patch results differ")
    need(b"deoptimizeEverything" not in local and b"deoptimizeBootImage" not in local, "Global deoptimization is not part of this backport")
    original_tree = tree(bridge_package)
    shutil.copytree(bridge_package, destination)
    (destination / "lib/android.js").write_bytes(patched)
    expected_tree = dict(original_tree)
    expected_tree["lib/android.js"] = AFTER
    official_patched_tree = dict(expected_tree)
    policy_path = HERE / "api37-reflection-policy.json"
    policy = json.loads(policy_path.read_text(encoding="utf-8"))
    policy_diff = (HERE / "api37-reflection-policy.diff").read_bytes()
    need(policy.get("schema") == 1 and policy.get("id") == "hnubl-ota-api37-reflection-only-v1" and
         policy.get("minimumApi") == 37 and policy.get("origin") == "local-scoped-policy" and
         policy.get("runtimeVersionUnchanged") == "17.25.0" and policy.get("baseVersion") == "7.0.13" and
         policy.get("nativeWorkaroundImported") is False and policy.get("noGlobalDeoptimizationAdded") is True,
         "Local API-37 bridge policy identity mismatch")
    need(sha(policy_diff) == policy.get("diffSha256") == POLICY_DIFF, "Local API-37 policy diff hash mismatch")
    need({entry["path"]: (entry["beforeSha256"], entry["afterSha256"]) for entry in policy["files"]} == POLICY_FILES,
         "Local API-37 bridge policy file chain mismatch")
    reproduced_diff = ""
    for entry in policy["files"]:
        target = destination / entry["path"]
        before = target.read_bytes()
        need(sha(before) == entry["beforeSha256"], "Local bridge policy before hash mismatch")
        after = before.decode("utf-8")
        for replacement in entry["replacements"]:
            old, new = replacement["old"], replacement["new"]
            need(old and after.count(old) == 1, "Local bridge policy context must match exactly once")
            after = after.replace(old, new, 1)
        raw = after.encode("utf-8")
        need(sha(raw) == entry["afterSha256"], "Local bridge policy after hash mismatch")
        reproduced_diff += "".join(difflib.unified_diff(before.decode("utf-8").splitlines(keepends=True),
            after.splitlines(keepends=True), fromfile="a/" + entry["path"], tofile="b/" + entry["path"]))
        target.write_bytes(raw)
        expected_tree[entry["path"]] = entry["afterSha256"]
    need(reproduced_diff.encode("utf-8") == policy_diff, "Local bridge policy diff does not reproduce source changes")
    need(tree(destination) == expected_tree, "Unexpected change in bridge working copy")
    need(tree(bridge_package) == original_tree, "Original bridge package changed")
    return {"metadata": metadata, "metadataSha256": sha((HERE / "bridge-backport.json").read_bytes()),
            "workingCopy": str(destination), "originalTree": original_tree, "patchedTree": expected_tree,
            "officialPatchedTree": official_patched_tree,
            "localPolicy": policy, "localPolicySha256": sha(policy_path.read_bytes()), "localPolicyDiffSha256": POLICY_DIFF,
            "originalPackageUntouched": True, "packageVersionUnchanged": True}


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("bridge_package", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.bridge_package, args.destination), indent=2))
