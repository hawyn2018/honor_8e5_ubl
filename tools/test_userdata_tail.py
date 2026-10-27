"""Exercise the real shell planner plus native resize rounding on host fixtures.

This script never invokes ADB, opens a block device, mounts, or resizes a filesystem.
Requires clang and a POSIX shell (Git Bash is detected on Windows).
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "app/src/main/assets/recovery/online-create.sh"
WORKER = ROOT / "app/src/main/cpp/partition_worker.c"
MIB = 1024 * 1024


def run(command: list[str | Path], **kwargs) -> subprocess.CompletedProcess:
    return subprocess.run([str(x) for x in command], text=True, capture_output=True,
                          timeout=30, check=False, **kwargs)


def main() -> None:
    text = SCRIPT.read_text(encoding="utf-8")
    assert not text.startswith("\ufeff") and "\r" not in text
    planner = text.split("# BEGIN userdata tail planner\n", 1)[1].split("# END userdata tail planner", 1)[0]
    shell = os.environ.get("BASH") or shutil.which("bash")
    if not shell and os.name == "nt":
        shell = r"C:\Program Files\Git\bin\bash.exe"
    assert shell and Path(shell).is_file(), "POSIX shell required"
    clang = shutil.which("clang")
    assert clang, "clang required"
    syntax = run([shell, "-n", SCRIPT.as_posix()])
    assert syntax.returncode == 0, syntax.stderr
    checks = []
    with tempfile.TemporaryDirectory(prefix="userdata-tail-fixtures-") as temporary:
        folder = Path(temporary)
        harness = folder / "plan.c"
        binary = folder / ("plan.exe" if os.name == "nt" else "plan")
        harness.write_text(
            '#define main partition_worker_original_main\n'
            f'#include "{WORKER.as_posix()}"\n'
            '#undef main\n'
            'int main(int argc, char **argv) {\n'
            ' uint64_t maximum, bytes, section;\n'
            ' if(argc!=4 || !parse64(argv[1],&maximum) || !parse64(argv[2],&bytes) || !parse64(argv[3],&section)) return 2;\n'
            ' struct super_info s={0}; struct resize_plan p;\n'
            ' s.blocks=bytes/FS_BLOCK; s.section_blocks=section/FS_BLOCK; s.section_bytes=section;\n'
            ' s.segs_per_sec=(uint32_t)(s.section_blocks/512); s.main_block=4096;\n'
            ' s.main_segments=(uint32_t)((s.blocks-s.main_block)/512); s.sections=s.main_segments/s.segs_per_sec;\n'
            ' s.segments=s.main_segments+7;\n'
            ' if(!build_resize_plan(&s,maximum,&p)) return 1;\n'
            ' printf("FS_EXPECTED_BYTES=%" PRIu64 "\\nFS_READ_MODE=direct\\nRESULT=planned\\n",p.expected*FS_BLOCK);\n'
            ' return 0;\n'
            '}\n', encoding="utf-8", newline="\n")
        compiled = run([clang, "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror", harness, "-o", binary])
        assert compiled.returncode == 0, compiled.stdout + compiled.stderr

        def case(name: str, *, fs: int = 1024 * MIB, gpt: int = 1024 * MIB,
                 mapped: int = 1024 * MIB, extent: int = 1024 * MIB,
                 image: int = MIB, logical: int = 4096, reuse: bool = False,
                 existing_efi: int = 64 * MIB, fail: str | None = None,
                 skip: bool | None = None, final_fs: int | None = None) -> dict:
            start = 8 * MIB // logical
            assert all(v % logical == 0 for v in (gpt, mapped, extent))
            values = {
                "FS_BYTES": fs, "UD_BYTES": mapped, "MAPPED_BYTES": mapped,
                "UD_GPT_BYTES": gpt, "UD_START": start,
                "UD_END": start + gpt // logical - 1,
                "UD_KERNEL_END": start + mapped // logical - 1,
                "EFI_LIMIT_END": start + extent // logical - 1,
                "PE_BYTES": image, "LOGICAL_SECTOR": logical,
                "EFI_BYTES": existing_efi, "DEFAULT_EFI_BYTES": 64 * MIB,
                "SECTION_BYTES": 2 * MIB,
            }
            log = folder / "worker-calls.txt"
            log.write_text("", encoding="utf-8")
            script = "set -u\n" + "\n".join(f"{k}={v}" for k, v in values.items()) + "\n"
            script += f"HARNESS='{binary.as_posix()}'\nCALLS='{log.as_posix()}'\n"
            script += "PLAN_MODE=" + ("reuse" if reuse else "create") + "\n"
            script += "FS_READ_MODE=direct SKIP_F2FS_SHRINK=0 DATA_SOURCE=FIXTURE\n"
            script += "is_uint() { case \"$1\" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }\n"
            script += "kv() { printf '%s\\n' \"$1\" | sed -n \"s/^$2=//p\"; }\n"
            script += "die() { printf '%s\\n' \"BLOCK_REASON=$1\"; exit 9; }\n"
            script += "worker() { printf '%s\\n' \"$*\" >> \"$CALLS\"; [ \"$1\" = plan-f2fs ] || exit 8; \"$HARNESS\" \"$4\" \"$FS_BYTES\" \"$SECTION_BYTES\"; }\nWORKER=worker\n"
            script += planner
            script += "\nprintf '%s\\n' \"SKIP=$SKIP_F2FS_SHRINK\" \"FINAL=$NEW_UD_BYTES\" \"EFI_BYTES=$EFI_BYTES\" \"TAIL=$TAIL_FREE_BYTES\" \"MAX=$MAX_FS_BYTES\"\n"
            if not reuse:
                script += "printf '%s\\n' \"EFI_START=$EFI_START\" \"EFI_END=$EFI_END\" \"NEW_UD_END=$NEW_UD_END\"\n"
            result = run([shell, "-c", script])
            if fail:
                assert result.returncode == 9 and fail in result.stdout, (name, result.stdout, result.stderr)
                checks.append({"name": name, "passed": True, "blocked": fail})
                return {}
            assert result.returncode == 0, (name, result.stdout, result.stderr)
            output = dict(re.findall(r"^([A-Z_]+)=(.*)$", result.stdout, re.M))
            numbers = {k: int(v) for k, v in output.items() if v.isdigit()}
            if skip is not None:
                assert numbers["SKIP"] == int(skip), (name, numbers)
                assert bool(log.read_text()) == (not skip), (name, "unexpected worker invocation")
            if final_fs is not None:
                assert numbers["FINAL"] == final_fs, (name, numbers)
            assert numbers["FINAL"] <= mapped
            assert numbers["EFI_BYTES"] >= image
            assert numbers["MAX"] % 4096 == 0
            if reuse:
                assert numbers["FINAL"] == gpt and numbers["TAIL"] == gpt - fs
            else:
                assert numbers["TAIL"] == extent - fs
                assert numbers["FINAL"] + numbers["EFI_BYTES"] == extent
                assert numbers["EFI_END"] == values["EFI_LIMIT_END"]
                assert numbers["EFI_START"] == numbers["NEW_UD_END"] + 1
                assert (numbers["NEW_UD_END"] - start + 1) * logical == numbers["FINAL"]
            checks.append({"name": name, "passed": True, **numbers})
            return numbers

        case("fresh-full-filesystem", skip=False, final_fs=956 * MIB)
        case("inside-gpt-tail", fs=956 * MIB, skip=True, final_fs=956 * MIB)
        case("already-shrunk-gpt-external-free-tail", fs=956*MIB, gpt=956*MIB, skip=True, final_fs=956*MIB)
        case("gpt-needs-grow-to-readable-filesystem", fs=956*MIB, gpt=900*MIB, skip=True, final_fs=956*MIB)
        case("kernel-smaller-than-current-gpt-fs-still-readable", fs=956*MIB, mapped=970*MIB, skip=True, final_fs=956*MIB)
        case("unused-kernel-tail-exceeds-next-partition-limit", fs=956*MIB, gpt=1000*MIB, mapped=1100*MIB, skip=True, final_fs=956*MIB)
        case("filesystem-crosses-next-partition", fs=1026*MIB, gpt=1000*MIB, mapped=1100*MIB,
             fail="F2FS extends into another partition")
        case("filesystem-exceeds-readable-mapping", fs=1024*MIB, gpt=1000*MIB, mapped=1000*MIB, extent=1100*MIB,
             fail="does not fit its mounted mapping")
        case("tail-exactly-60MiB-still-reserves-default", fs=964*MIB, skip=False, final_fs=956*MIB)
        case("tail-just-over-60MiB-skips", fs=964*MIB-4096, skip=True, final_fs=964*MIB-4096)
        case("tail-just-under-60MiB-shrinks", fs=964*MIB+4096, skip=False)
        case("tail-exactly-fits-64MiB-image", fs=960*MIB, image=64*MIB, skip=True, final_fs=960*MIB)
        case("tail-exactly-fits-larger-image", fs=954*MIB, image=70*MIB, skip=True, final_fs=954*MIB)
        case("large-image-needs-additional-tail", fs=954*MIB, image=70*MIB+1, skip=False, final_fs=950*MIB)
        case("existing-20MiB-tail-only-shrinks-the-deficit", fs=1004*MIB, skip=False, final_fs=956*MIB)
        case("unaligned-large-image-remains-4KiB-safe", image=100*MIB+1, skip=False, final_fs=920*MIB)
        case("512-byte-limit-rounds-target-down", extent=1024*MIB+512, logical=512, skip=False, final_fs=956*MIB)
        case("actual-device-full-size-64bit-arithmetic", fs=490010050560, gpt=490010050560,
             mapped=490010050560, extent=490010050560, skip=False, final_fs=489938747392)
        case("reuse-keeps-userdata-even-with-internal-tail", fs=956*MIB, gpt=960*MIB, reuse=True,
             skip=True, final_fs=960*MIB)
        case("reuse-still-rejects-fs-larger-than-gpt", fs=964*MIB, gpt=960*MIB, reuse=True,
             fail="exceeds existing GPT userdata")
        case("reuse-still-rejects-oversize-image", reuse=True, image=65*MIB,
             fail="selected efisp image exceeds")

        # All changing-layout paths use one explicit range plan, then exact raw
        # before/planned snapshots for resume and interrupted-job retirement.
        assert '"$GPTTOOL" plan-range "$DISK" "$UD_INDEX" "$NEW_UD_END" "$EFI_INDEX" "$EFI_END" "$EFI_GUID"' in text
        for field in ("CURRENT_GPT_BYTES", "CURRENT_GPT_END", "KERNEL_UD_BYTES", "KERNEL_UD_END",
                      "MAPPED_BYTES", "EFI_LIMIT_END", "FS_INSPECT_LIMIT", "CONTIGUOUS_BYTES"):
            assert f'"{field}=${field}"' in text, field
        assert '[ "$(plan_text)" = "$(cat "$JOB_DIR/plan.txt")" ]' in text
        assert 'retired_gpt_hash" = "$(value "$JOB_DIR/manifest.txt" GPT_BEFORE_SHA256)' in text
        assert 'retired_gpt_hash" = "$(value "$JOB_DIR/manifest.txt" GPT_PLANNED_SHA256)' in text
    print(json.dumps({"passed": True, "cases": checks}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
