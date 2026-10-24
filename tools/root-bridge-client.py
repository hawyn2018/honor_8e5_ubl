"""ADB client for the user-granted net.hawyn.rootbridge application.

Commands travel as Base64 arguments, not interpolated shell code. Binary stdout
and stderr are downloaded separately through the app's UID-restricted provider.
"""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
AUTHORITY = "content://net.hawyn.rootbridge"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--command")
    inputs.add_argument("--command-file", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--wait", type=float, default=180)
    args = parser.parse_args()
    command = args.command if args.command is not None else args.command_file.read_text(encoding="utf-8")
    adb = [str(ROOT / "platform-tools/adb.exe"), "-s", args.serial]

    def call(method, argument):
        result = subprocess.run(adb + ["shell", "content", "call", "--uri", AUTHORITY,
                                     "--method", method, "--arg", argument],
                                capture_output=True, timeout=20)
        text = result.stdout.decode("utf-8", errors="replace")
        if result.returncode or "json=" not in text:
            raise RuntimeError("Bridge call failed: " + text + result.stderr.decode("utf-8", errors="replace"))
        value, _ = json.JSONDecoder().raw_decode(text.split("json=", 1)[1])
        if "error" in value and not value.get("id"):
            raise RuntimeError(str(value["error"]))
        return value

    submitted = call("submit", base64.b64encode(command.encode("utf-8")).decode("ascii"))
    job_id = submitted["id"]
    print(json.dumps({"submitted": job_id}), flush=True)
    deadline = time.monotonic() + args.wait
    while True:
        status = call("status", job_id)
        finished = status.get("finished", False) or status.get("state") in ("complete", "completed", "done", "failed", "success", "succeeded", "error", "interrupted")
        if finished:
            break
        if time.monotonic() >= deadline:
            raise TimeoutError("Bridge job still pending: " + job_id)
        time.sleep(0.5)
    destination = args.output or ROOT / "reports/root-bridge-jobs" / job_id
    destination.mkdir(parents=True, exist_ok=True)
    for stream in ("stdout", "stderr"):
        path = destination / (stream + ".bin")
        with path.open("wb") as out:
            result = subprocess.run(adb + ["exec-out", "content", "read", "--uri",
                                         f"{AUTHORITY}/jobs/{job_id}/{stream}"],
                                    stdout=out, stderr=subprocess.PIPE, timeout=30)
        if result.returncode:
            raise RuntimeError("Bridge output read failed: " + result.stderr.decode("utf-8", errors="replace"))
        expected = status.get(stream + "Bytes")
        if isinstance(expected, int) and path.stat().st_size != expected:
            raise RuntimeError(f"Bridge {stream} length mismatch: {path.stat().st_size} != {expected}")
    (destination / "status.json").write_text(json.dumps(status, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(destination.resolve()), "status": status}, ensure_ascii=True), flush=True)
    code = status.get("exitCode", status.get("exit_code"))
    return code if isinstance(code, int) else 1


if __name__ == "__main__":
    raise SystemExit(main())
