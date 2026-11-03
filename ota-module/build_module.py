#!/usr/bin/env python3
"""Offline, staged builder for the hnubl OTA module. No device/network operations."""
from __future__ import annotations
import argparse
import hashlib
import io
import importlib.util
import json
import lzma
import os
from pathlib import Path
import platform
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import time
import zipfile

ROOT = Path(__file__).resolve().parent
PROJECT = ROOT.parent
FRIDA_VERSION = "17.25.0"
FRIDA_XZ = "frida-inject-17.25.0-android-arm64.xz"
FRIDA_XZ_SHA = "987d0a900cf1bc3878ba8b34b00ad2889e55e4ba26a8da7140a881f652982a73"
FRIDA_ELF_SHA = "1d3374cdbb73f3fe4737f7e7a77e6d17e19914f16d1bffbd6c42999800f8dbeb"
FRIDA_BYTES = 84601832
JS_VERSIONS = {"frida-java-bridge": "7.0.13", "esbuild": "0.28.2", "buffer": "6.0.3"}
JVM_SEEDS = ("com/android/tools/smali/dexlib2/", "com/android/tools/smali/util/",
             "com/google/common/", "com/google/thirdparty/", "com/google/errorprone/annotations/",
             "com/google/j2objc/annotations/", "javax/annotation/", "org/checkerframework/",
             "org/jspecify/annotations/")

def need(value, message):
    if not value:
        raise RuntimeError(message)

def digest(data): return hashlib.sha256(data).hexdigest()
def file_sha(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as source:
        for part in iter(lambda: source.read(1024 * 1024), b""): h.update(part)
    return h.hexdigest()

def atomic_bytes(path, data):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(prefix=".hnubl-build-", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as output:
            output.write(data); output.flush(); os.fsync(output.fileno())
        os.replace(name, path)
    finally:
        if os.path.exists(name): os.unlink(name)

def write_json(path, data):
    atomic_bytes(path, (json.dumps(data, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode())

def tree_hash(directory):
    result = {}
    for path in sorted(Path(directory).rglob("*")):
        need(not path.is_symlink(), "Symlink rejected in build input: " + str(path))
        if path.is_file(): result[path.relative_to(directory).as_posix()] = file_sha(path)
    return result

def source_snapshot():
    result = {"build_module.py": file_sha(__file__)}
    for folder in ("broker/src", "controller/src", "discovery/src", "hook/src", "hook/patches", "module"):
        for name, sha in tree_hash(ROOT / folder).items(): result[folder + "/" + name] = sha
    return result

def zip_bytes(entries):
    memory = io.BytesIO()
    with zipfile.ZipFile(memory, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, (data, mode) in sorted(entries.items()):
            need(not name.startswith("/") and ".." not in Path(name).parts and "\\" not in name, "Bad archive path")
            item = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)); item.create_system = 3
            item.external_attr = (0o100000 | mode) << 16
            item.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(item, data)
    raw = memory.getvalue()
    with zipfile.ZipFile(io.BytesIO(raw)) as archive: need(archive.testzip() is None, "Output ZIP CRC failed")
    return raw

def cp_references(data):
    """Read constant-pool class/descriptor references for dependency closure."""
    need(data[:4] == b"\xca\xfe\xba\xbe", "Invalid JVM class")
    count = struct.unpack_from(">H", data, 8)[0]; offset = 10; index = 1; strings = []
    while index < count:
        tag = data[offset]; offset += 1
        if tag == 1:
            size = struct.unpack_from(">H", data, offset)[0]; offset += 2
            strings.append(data[offset:offset + size].decode("utf-8", "replace")); offset += size
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18): offset += 4
        elif tag in (5, 6): offset += 8; index += 1
        elif tag in (7, 8, 16, 19, 20): offset += 2
        elif tag == 15: offset += 3
        else: raise RuntimeError("Unknown JVM constant-pool tag: " + str(tag))
        need(offset <= len(data), "Truncated JVM class constant pool")
        index += 1
    refs = set()
    for value in strings:
        if re.fullmatch(r"[\w$/]+", value): refs.add(value + ".class")
        refs.update(item + ".class" for item in re.findall(r"L([\w$/]+)", value))
        if re.fullmatch(r"[\w$]+(?:\.[\w$]+)+", value): refs.add(value.replace(".", "/") + ".class")
    return refs

def extract_jvm(jadx, output):
    with zipfile.ZipFile(jadx) as archive:
        need(archive.testzip() is None, "JADX dependency archive CRC failed")
        names = {n for n in archive.namelist() if n.endswith(".class") and not n.startswith("META-INF/")}
        selected = {n for n in names if n.startswith(JVM_SEEDS)}
        need(any(n.startswith(JVM_SEEDS[0]) for n in selected), "JADX jar has no expected dexlib2 namespace")
        pending = list(selected); classes = {}
        while pending:
            name = pending.pop(); data = archive.read(name); classes[name] = data
            for child in cp_references(data) & names:
                if child not in selected:
                    selected.add(child); pending.append(child)
    raw = zip_bytes({n: (b, 0o644) for n, b in classes.items()}); atomic_bytes(output, raw)
    return {"sha256": digest(raw), "classes": len(classes), "sourceJadxSha256": file_sha(jadx)}

def run(command, cwd, log, env=None):
    with Path(log).open("wb") as output:
        result = subprocess.run([str(a) for a in command], cwd=cwd, env=env, stdout=output, stderr=subprocess.STDOUT)
    need(result.returncode == 0, "Command failed (" + str(result.returncode) + "); see " + str(log))

def resolve_tools(args):
    java_home = Path(args.java_home or os.environ.get("JAVA_HOME") or r"C:\Program Files\Eclipse Adoptium\jdk-21.0.12.8-hotspot")
    extension = ".exe" if os.name == "nt" else ""
    java = java_home / "bin" / ("java" + extension); javac = java_home / "bin" / ("javac" + extension)
    sdk = Path(args.sdk or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME") or r"D:\AndroidSDK")
    android = sdk / "platforms/android-37/android.jar"
    d8 = sdk / "build-tools/37.0.0/lib/d8.jar"
    if not d8.is_file():
        choices = sorted((sdk / "build-tools").glob("*/lib/d8.jar"), reverse=True)
        need(bool(choices), "D8 jar missing under Android SDK build-tools")
        d8 = choices[0]
    fallback_node = r"C:\Program Files\WindowsApps\OpenAI.CodexPrimaryRuntime.v26-1026-487-0_26.1026.487.0_x64__3k8sg7r9htsxt\dependencies\node\bin\node.exe"
    node = Path(args.node or shutil.which("node") or fallback_node)
    tools = {"java": java, "javac": javac, "androidJar": android, "d8Jar": d8, "node": node}
    for key, path in tools.items(): need(path.is_file(), key + " missing: " + str(path))
    return tools

def js_inputs(js, existing_lock):
    package_path = js / "package.json"; lock_path = js / "package-lock.json"
    need(package_path.is_file() and lock_path.is_file(), "JS package.json/package-lock.json missing")
    lock = json.loads(lock_path.read_text(encoding="utf-8")); packages = lock.get("packages", {})
    files = {}; installed = {}
    for name, expected in JS_VERSIONS.items():
        key = "node_modules/" + name; record = packages.get(key, {})
        need(record.get("version") == expected and record.get("integrity", "").startswith("sha512-"), "JS lock mismatch: " + name)
        need((js / key / "package.json").is_file(), "Run npm ci --ignore-scripts --no-audit --no-fund in " + str(js))
        actual = json.loads((js / key / "package.json").read_text(encoding="utf-8"))
        need(actual.get("version") == expected, "Installed JS dependency version mismatch: " + name)
        installed[name] = {"version": expected, "integrity": record["integrity"]}
    # npm ci validates the lock's tarball SRI. Record every installed package file
    # at this trusted build checkpoint, and require the same files on later runs.
    modules = js / "node_modules"
    for path in sorted(modules.rglob("*")):
        relative = path.relative_to(modules).as_posix()
        if relative.startswith(".bin/") or relative == ".package-lock.json": continue
        need(not path.is_symlink(), "Symlink in JS dependency tree: " + str(path))
        if path.is_file(): files[relative] = file_sha(path)
    machine = platform.system() + "/" + platform.machine()
    result = {"packageJsonSha256": file_sha(package_path), "packageLockSha256": file_sha(lock_path),
              "packages": installed, "machine": machine, "installedFiles": files}
    old = existing_lock.get("js")
    if old:
        need(old["packageJsonSha256"] == result["packageJsonSha256"] and old["packageLockSha256"] == result["packageLockSha256"], "Vendored JS lock changed")
        if old.get("machine") == machine: need(old.get("installedFiles") == files, "JS tree differs from its recorded npm-ci checkpoint; rerun npm ci or inspect changed dependencies")
    return result

def build(args, out, stage):
    tools = resolve_tools(args)
    source_before = source_snapshot()
    vendored = ROOT / "vendor"; incoming = Path(args.vendor).resolve() if args.vendor else vendored
    vendored.mkdir(parents=True, exist_ok=True)
    lock_path = vendored / "dependency-lock.json"
    old_lock = json.loads(lock_path.read_text(encoding="utf-8")) if lock_path.exists() else {}
    compressed = incoming / FRIDA_XZ
    need(compressed.is_file() and file_sha(compressed) == FRIDA_XZ_SHA, "Pinned Frida compressed runtime missing or SHA mismatch")
    frida_meta = json.loads((incoming / "download-manifest.json").read_text(encoding="utf-8"))
    frida_meta_sha = file_sha(incoming / "download-manifest.json")
    need(frida_meta.get("url") == "https://github.com/frida/frida/releases/download/" + FRIDA_VERSION + "/" + FRIDA_XZ and
         frida_meta.get("version") == FRIDA_VERSION and frida_meta.get("sha256") == FRIDA_XZ_SHA and
         frida_meta.get("elfSha256") == FRIDA_ELF_SHA and frida_meta.get("bytes") == FRIDA_BYTES, "Frida download manifest mismatch")
    elf = lzma.decompress(compressed.read_bytes())
    need(len(elf) == FRIDA_BYTES and digest(elf) == FRIDA_ELF_SHA and elf[:4] == b"\x7fELF" and
         int.from_bytes(elf[18:20], "little") == 183, "Frida ELF identity mismatch")
    if (incoming / "frida-inject").exists(): need(file_sha(incoming / "frida-inject") == FRIDA_ELF_SHA, "Uncompressed Frida runtime differs")
    jvm = stage / "jvm-deps.jar"
    if args.jadx:
        jadx = Path(args.jadx).resolve(); need(jadx.is_file(), "JADX jar missing")
        if old_lock.get("jvm", {}).get("sourceJadxSha256"):
            need(file_sha(jadx) == old_lock["jvm"]["sourceJadxSha256"], "JADX source differs from vendored dependency provenance")
        jvm_info = extract_jvm(jadx, jvm)
    else:
        original = vendored / "jvm-deps.jar"
        need(original.is_file() and "jvm" in old_lock, "First build requires --jadx JAR; source rebuild uses vendor/jvm-deps.jar")
        need(file_sha(original) == old_lock["jvm"]["sha256"], "Vendored JVM dependency SHA mismatch")
        shutil.copyfile(original, jvm); jvm_info = old_lock["jvm"]
    if old_lock.get("jvm"): need(file_sha(jvm) == old_lock["jvm"]["sha256"], "Rebuilt JVM dependency closure differs")
    js = incoming / "js"; js_info = js_inputs(js, old_lock)
    patch_script = ROOT / "hook/patches/apply_bridge_backport.py"
    patch_spec = importlib.util.spec_from_file_location("hnubl_bridge_backport", patch_script)
    patch_helper = importlib.util.module_from_spec(patch_spec)
    previous_bytecode = sys.dont_write_bytecode
    try:
        sys.dont_write_bytecode = True
        patch_spec.loader.exec_module(patch_helper)
    finally:
        sys.dont_write_bytecode = previous_bytecode
    bridge_work = stage / "js-dependencies/node_modules/frida-java-bridge"
    bridge_backport = patch_helper.prepare(js / "node_modules/frida-java-bridge", bridge_work)
    if old_lock.get("bridgeBackport"):
        need(old_lock["bridgeBackport"] == bridge_backport["metadata"], "Recorded bridge backport identity changed")
    if old_lock.get("bridgeLocalPolicy"):
        need(old_lock["bridgeLocalPolicy"] == bridge_backport["localPolicy"], "Recorded local bridge policy identity changed")
    write_json(stage / "bridge-backport-verification.json", bridge_backport)
    java_sources = sorted(p for folder in ("broker/src", "controller/src", "discovery/src") for p in (ROOT / folder).rglob("*.java"))
    need(len(java_sources) >= 9, "Incomplete broker/controller/discovery source set")
    classes = stage / "classes"; classes.mkdir()
    arguments = ["--release", "17", "-encoding", "UTF-8", "-g:none", "-cp",
                 os.pathsep.join((str(tools["androidJar"]), str(jvm))), "-d", str(classes)] + [str(p) for p in java_sources]
    # javac argument files keep paths safe and do not depend on shell expansion.
    argument_file = stage / "javac.args"
    argument_file.write_text("\n".join('"' + a.replace("\\", "/").replace('"', '\\"') + '"' for a in arguments) + "\n", encoding="utf-8")
    run([tools["javac"], "@" + str(argument_file)], ROOT, stage / "javac.log")
    program = stage / "program.jar"
    atomic_bytes(program, zip_bytes({p.relative_to(classes).as_posix(): (p.read_bytes(), 0o644) for p in classes.rglob("*.class")}))
    dex = stage / "dex"; dex.mkdir()
    run([tools["java"], "-cp", tools["d8Jar"], "com.android.tools.r8.D8", "--release", "--min-api", "28",
         "--lib", tools["androidJar"], "--output", dex, program, jvm], ROOT, stage / "d8.log")
    dex_files = sorted(dex.glob("classes*.dex")); need(bool(dex_files), "D8 produced no DEX files")
    broker_bytes = zip_bytes({p.name: (p.read_bytes(), 0o644) for p in dex_files})
    atomic_bytes(stage / "broker.jar", broker_bytes)
    bundle = stage / "agent.bundle.js"
    native_bundle = stage / "native-slot.bundle.js"
    bundler = stage / "bundle.cjs"
    settings = {"entryPoints": [str(ROOT / "hook/src/agent.js")], "bundle": True, "format": "iife", "platform": "neutral",
                "mainFields": ["module", "main"], "nodePaths": [str(js / "node_modules")],
                "alias": {"frida-java-bridge": str(bridge_work / "index.js")},
                "target": ["es2020"], "outfile": str(bundle), "charset": "utf8", "legalComments": "eof",
                "minifyWhitespace": True, "metafile": True}
    native_settings = {"entryPoints": [str(ROOT / "hook/src/native-slot-agent.js")], "bundle": True,
                       "format": "iife", "platform": "neutral", "target": ["es2020"],
                       "outfile": str(native_bundle), "charset": "utf8", "legalComments": "eof",
                       "minifyWhitespace": True, "metafile": True}
    bundler.write_text("const esbuild=require(" + json.dumps(str(js / "node_modules/esbuild")) + ");\n" +
                       "const result=esbuild.buildSync(" + json.dumps(settings) + ");\n" +
                       "require('fs').writeFileSync(" + json.dumps(str(stage / "esbuild-meta.json")) + ",JSON.stringify(result.metafile));\n" +
                       "const nativeResult=esbuild.buildSync(" + json.dumps(native_settings) + ");\n" +
                       "require('fs').writeFileSync(" + json.dumps(str(stage / "native-esbuild-meta.json")) + ",JSON.stringify(nativeResult.metafile));\n", encoding="utf-8")
    run([tools["node"], bundler], ROOT, stage / "esbuild.log")
    need(bundle.is_file() and bundle.stat().st_size > 10000, "Incomplete hook JavaScript bundle")
    need(native_bundle.is_file() and native_bundle.stat().st_size > 1000, "Incomplete native slot agent bundle")
    native_inputs = json.loads((stage / "native-esbuild-meta.json").read_text(encoding="utf-8"))["inputs"]
    need(len(native_inputs) >= 3 and all("frida-java-bridge" not in n and "node_modules" not in n for n in native_inputs),
         "Native-only agent unexpectedly depends on the Java bridge or an external package")
    bundle_inputs = json.loads((stage / "esbuild-meta.json").read_text(encoding="utf-8"))["inputs"]
    bridge_sources = [(Path(name) if Path(name).is_absolute() else ROOT / name).resolve()
                      for name in bundle_inputs if "frida-java-bridge" in name.replace("\\", "/")]
    need(bridge_sources and all(path.is_relative_to(bridge_work) for path in bridge_sources), "Bundle resolved an unpatched bridge dependency")
    need(tree_hash(bridge_work) == bridge_backport["patchedTree"], "Patched bridge working copy changed during bundle")
    need(source_before == source_snapshot(), "Module source changed during build; restart from a stable snapshot")
    need(file_sha(compressed) == FRIDA_XZ_SHA and file_sha(incoming / "download-manifest.json") == frida_meta_sha,
         "Frida dependency changed during build")
    need(file_sha(jvm) == jvm_info["sha256"], "JVM dependency changed during build")
    if args.jadx: need(file_sha(args.jadx) == jvm_info["sourceJadxSha256"], "JADX dependency changed during build")
    js_inputs(js, {"js": js_info})
    inputs_lock = {"schema": 1, "frida": frida_meta, "jvm": jvm_info, "js": js_info,
                   "bridgeBackport": bridge_backport["metadata"], "bridgeLocalPolicy": bridge_backport["localPolicy"]}
    # Persist portable inputs; do not vendor node_modules, logs, classes or raw ELF.
    atomic_bytes(vendored / FRIDA_XZ, compressed.read_bytes())
    write_json(vendored / "download-manifest.json", frida_meta)
    atomic_bytes(vendored / "jvm-deps.jar", jvm.read_bytes())
    for name in ("package.json", "package-lock.json"): atomic_bytes(vendored / "js" / name, (js / name).read_bytes())
    write_json(lock_path, inputs_lock)
    entries = {}
    for path in sorted((ROOT / "module").rglob("*")):
        need(not path.is_symlink(), "Symlink in module input")
        if path.is_file(): entries[path.relative_to(ROOT / "module").as_posix()] = (path.read_bytes(), 0o755 if path.suffix == ".sh" else 0o644)
    need({"module.prop", "customize.sh", "service.sh", "uninstall.sh", "sepolicy.rule"} <= entries.keys(), "Module installation files incomplete")
    for name in ("broker.jar", "agent.bundle.js", "native-slot.bundle.js", "bin/frida-inject"): need(name not in entries, "Generated module entry collides with source: " + name)
    entries.update({"broker.jar": (broker_bytes, 0o644), "agent.bundle.js": (bundle.read_bytes(), 0o644),
                    "native-slot.bundle.js": (native_bundle.read_bytes(), 0o644), "bin/frida-inject": (elf, 0o755)})
    for name in ("bridge-backport.json", "api37-reflection-policy.json", "api37-reflection-policy.diff",
                 "LGPL-2.0-only.txt", "WxWindows-exception-3.1.txt"):
        entries["licenses/frida-java-bridge-" + name] = ((ROOT / "hook/patches" / name).read_bytes(), 0o644)
    payload_manifest = {n: digest(b) for n, (b, _) in sorted(entries.items())}
    entries["payload-sha256.json"] = ((json.dumps(payload_manifest, indent=2, sort_keys=True) + "\n").encode(), 0o644)
    module_bytes = zip_bytes(entries); module_sha = digest(module_bytes)
    assets = PROJECT / "app/src/main/assets/modules"
    atomic_bytes(assets / "hnubl-ota.zip", module_bytes)
    atomic_bytes(assets / "hnubl-ota.sha256", (module_sha + "  hnubl-ota.zip\n").encode("ascii"))
    need(file_sha(assets / "hnubl-ota.zip") == module_sha, "Final module asset hash mismatch")
    manifest = {"state": "complete", "sourceStable": True, "sourceSnapshot": source_before,
                "dependencyLockSha256": file_sha(lock_path), "tools": {n: {"path": str(p), "sha256": file_sha(p)} for n, p in tools.items()},
                "moduleSha256": module_sha, "moduleBytes": len(module_bytes), "payloads": payload_manifest,
                "bridgeBackport": bridge_backport,
                "assets": str(assets), "stage": str(stage), "deviceOperations": False}
    write_json(out / "build-manifest.json", manifest)
    return manifest

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=ROOT / "build")
    parser.add_argument("--vendor", type=Path, help="Validated runtime and JS dependency directory")
    parser.add_argument("--jadx", type=Path, help="First-build JADX fat JAR used to create portable JVM dependencies")
    parser.add_argument("--java-home"); parser.add_argument("--sdk"); parser.add_argument("--node")
    args = parser.parse_args(); out = args.out.resolve(); out.mkdir(parents=True, exist_ok=True)
    need(not out.is_relative_to(PROJECT) or out.is_relative_to(ROOT / "build"),
         "Inside the source project, output must remain under ota-module/build; choose an external --out otherwise")
    stage = Path(tempfile.mkdtemp(prefix="module-build-", dir=out))
    write_json(out / "build-manifest.json", {"state": "building", "stage": str(stage), "deviceOperations": False})
    try:
        result = build(args, out, stage)
        print(json.dumps({"state": "complete", "moduleSha256": result["moduleSha256"], "moduleBytes": result["moduleBytes"], "stage": str(stage)}))
    except Exception as failure:
        write_json(out / "build-manifest.json", {"state": "failed", "error": str(failure), "stage": str(stage), "deviceOperations": False})
        print("Module build stopped: " + str(failure), file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__": sys.exit(main())
