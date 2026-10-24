"""Build upstream e2fsprogs' Android source lists using the Windows NDK.

No device connection or execution is used. Generated headers follow upstream
util/gen-android-files. Sparse image I/O is disabled: BootForge uses raw blocks.
"""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import hashlib
import os
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "e2fsprogs-source" / "e2fsprogs-c3cce4a07efefc62bc7fc57a678cb870af27d0f2"
def ndk_bin():
    override = os.environ.get("BOOTFORGE_NDK_BIN")
    if override:
        return Path(override)
    ndk_root = os.environ.get("ANDROID_NDK_HOME") or os.environ.get("ANDROID_NDK_ROOT")
    if ndk_root:
        return Path(ndk_root) / "toolchains/llvm/prebuilt/windows-x86_64/bin"
    raise RuntimeError("Set BOOTFORGE_NDK_BIN, ANDROID_NDK_HOME or ANDROID_NDK_ROOT")


NDK = ndk_bin()
awk_command = os.environ.get("BOOTFORGE_AWK") or shutil.which("awk")
if not awk_command:
    raise RuntimeError("Put awk on PATH or set BOOTFORGE_AWK to its executable")
AWK = Path(awk_command)
BUILD = ROOT / "e2fsprogs-build"
BUILD.mkdir(exist_ok=True)
os.environ["TMP"] = os.environ["TEMP"] = str(BUILD)

def run(command, **options):
    result = subprocess.run([str(x) for x in command], capture_output=True, **options)
    if result.returncode:
        raise RuntimeError(result.stdout.decode(errors="replace") + result.stderr.decode(errors="replace"))
    return result.stdout

def generate():
    config = (SRC / "util/android_config.h").read_text()
    config = config.replace("#define ENABLE_LIBSPARSE 1", "/* Android sparse images disabled; raw ext4 tools only. */")
    (SRC / "lib/config.h").write_text(config)
    for directory, guard in (("ext2fs", "_EXT2_TYPES_H"), ("blkid", "_BLKID_TYPES_H"), ("uuid", "_UUID_TYPES_H")):
        types = (SRC / "util/android_types.h").read_text().replace("#define _LINUX_TYPES_H", f"#define {guard}")
        (SRC / "lib" / directory / f"{directory[:-2] if directory == 'ext2fs' else directory}_types.h").write_text(types)
    for directory in ("blkid", "uuid"):
        shutil.copyfile(SRC / "lib" / directory / f"{directory}.h.in", SRC / "lib" / directory / f"{directory}.h")
    (SRC / "lib/dirpaths.h").write_text('#define LOCALEDIR "/etc/locale"\n#define ROOT_SYSCONFDIR "/etc"\n')
    error_template = (SRC / "lib/ext2fs/ext2_err.et.in").read_text().replace("@E2FSPROGS_VERSION@", "1.47.2")
    (SRC / "lib/ext2fs/ext2_err.et").write_text(error_template)
    for name in ("ext2fs/ext2_err", "support/prof_err"):
        for extension in ("c", "h"):
            output = SRC / "lib" / (name + "." + extension)
            run([AWK, "-f", SRC / "lib/et" / f"et_{extension}.awk", "outfile=" + output.as_posix(), "outfn=" + output.name, SRC / "lib" / (name + ".et")])
    host_cc = shutil.which("clang")
    if not host_cc:
        raise RuntimeError("Host clang is required to generate the upstream CRC table")
    generator = BUILD / "gen_crc32ctable.exe"
    run([host_cc, SRC / "lib/ext2fs/gen_crc32ctable.c", "-o", generator])
    (SRC / "lib/ext2fs/crc32c_table.h").write_bytes(run([generator]))

def android_sources(directory):
    text = (SRC / directory / "Android.bp").read_text()
    listing = re.search(r"\bsrcs:\s*\[(.*?)\]", text, re.S).group(1)
    return [f"{directory}/{name}" for name in re.findall(r'"([^"\n]+\.c)"', listing)]

COMMON = [str(NDK / "clang.exe"), "--target=aarch64-linux-android28", "-std=gnu17", "-O2", "-D_FILE_OFFSET_BITS=64", "-DHAVE_CONFIG_H", "-ffunction-sections", "-fdata-sections", "-Wno-macro-redefined", "-Wno-typedef-redefinition", "-Wno-pointer-sign", "-Wno-format"]
for include in ("lib", "lib/ext2fs", "lib/et", "lib/e2p", "lib/blkid", "lib/uuid", "lib/support", "e2fsck", "resize", "."):
    COMMON.append("-I" + str(SRC / include))

def compile_one(relative):
    output = BUILD / (relative.replace("/", "_") + ".o")
    source = SRC / relative
    run(COMMON + [str(source), "-c", "-o", str(output)])
    return output

def main():
    generate()
    library_dirs = ("lib/ext2fs", "lib/et", "lib/e2p", "lib/blkid", "lib/uuid", "lib/support")
    library_sources = [name for directory in library_dirs for name in android_sources(directory)]
    # The upstream Android.bp list predates these two libext2fs additions;
    # they are included in upstream lib/ext2fs/Makefile.in.
    library_sources += ["lib/ext2fs/getenv.c", "lib/ext2fs/orphan.c"]
    programs = {
        "e2fsck": android_sources("e2fsck"),
        "resize2fs": android_sources("resize"),
        "dumpe2fs": ["misc/dumpe2fs.c"],
    }
    all_sources = sorted(set(library_sources + [name for names in programs.values() for name in names]))
    print("Compiling", len(all_sources), "upstream source files", flush=True)
    with ThreadPoolExecutor(max_workers=6) as pool:
        objects = dict(zip(all_sources, pool.map(compile_one, all_sources)))
    libraries = [objects[name] for name in library_sources]
    archive = BUILD / "e2fsprogs.a"
    run([NDK / "llvm-ar.exe", "rcs", archive] + libraries)
    for name, sources in programs.items():
        target = ROOT / f"{name}-arm64"
        run([NDK / "clang.exe", "--target=aarch64-linux-android28", "-static", "-Wl,--gc-sections"] + [objects[source] for source in sources] + [archive, "-o", target])
        run([NDK / "llvm-strip.exe", "--strip-all", target])
        report = run([NDK / "llvm-readelf.exe", "-h", "-l", "-d", target])
        (ROOT / f"{name}-elf.txt").write_bytes(report)
        print(name, target.stat().st_size, hashlib.sha256(target.read_bytes()).hexdigest(), flush=True)

if __name__ == "__main__":
    main()
