"""Protocol test fixture only. Does not open USB, ADB or real Fastboot."""
import json
from pathlib import Path
import sys

state_path, log_path = map(Path, sys.argv[1:3])
args = sys.argv[3:]
with log_path.open("a", encoding="utf-8") as log:
    log.write(json.dumps(args, ensure_ascii=False) + "\n")
state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {
    "status": "stopped", "id": "0000000000000000", "starts": 0, "stops": 0}
index = 0
while index < len(args) and args[index].startswith("-"):
    index += 2 if args[index] in ("-s", "--serial", "--slot", "-S") else 1
tail = args[index:]

def save():
    state_path.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")

def info(text):
    assert len(text.encode("ascii")) <= 56
    print("(bootloader) " + text, file=sys.stderr)

def status():
    info(f"KBL1 status={state['status']} id={state['id']} el=1")
    if state.get("job"):
        info("KBL1 job=" + state["job"])

def fail(text):
    print("FAILED (remote: '" + text + "')", file=sys.stderr)
    raise SystemExit(1)

def submit(data):
    if not data or len(data) > 4096 or b"\0" in data:
        fail("bad line")
    state["line"] = data.hex()
    state["result"] = ("执行：".encode() + data + b"\n").hex()
    state["offset"] = 0
    state["job"] = "0000000000000001"
    state["status"] = "busy"
    status()

if tail and tail[0] == "fetch":
    if len(tail) != 3 or tail[1] == "unsupported":
        fail("fetch unavailable")
    Path(tail[2]).write_bytes(bytes(range(256)) * 16 + b"\0\xff")
elif len(tail) >= 2 and tail[0] == "oem":
    cmd, argument = tail[1], " ".join(tail[2:])
    if cmd == "kblstart":
        if state["status"] == "stopped":
            state["starts"] += 1
            state["id"] = f"{state['starts']:016x}"
            state["status"] = "running"
        status()
    elif cmd == "kblin":
        if state["status"] == "stopped":
            fail("not running")
        status()
    elif cmd in ("kbloff", "kblstop"):
        state["stops"] += int(state["status"] != "stopped")
        state["status"] = "stopped"
        state.pop("job", None)
        status()
    elif cmd == "kblstatus":
        status()
    elif cmd == "kblcmd":
        submit(bytes.fromhex(argument))
    elif cmd == "kblput":
        if argument == "B":
            state["put"] = "0000000100000001"
            state["staging"] = ""
            info("KBL1 put=" + state["put"])
        elif argument.startswith("D" + state.get("put", "!") + ":"):
            identifier, offset, data = argument[1:].split(":")
            if int(offset, 16) != len(state["staging"]) // 2:
                fail("wrong offset")
            state["staging"] += data
        elif argument.startswith("E" + state.get("put", "!") + ":"):
            identifier, total = argument[1:].split(":")
            if int(total, 16) != len(state["staging"]) // 2:
                fail("wrong total")
            submit(bytes.fromhex(state["staging"]))
        else:
            fail("bad upload")
    elif cmd == "kblpoll":
        if argument != state.get("job"):
            fail("unknown job")
        result = bytes.fromhex(state["result"])
        start = state["offset"]
        part = result[start:start + 24]
        state["offset"] += len(part)
        if part:
            for at in range(0, len(part), 23):
                info("KBL1 out=" + part[at:at + 23].hex())
        done = state["offset"] == len(result)
        info(f"KBL1 done={int(done)} rc=0")
        if done:
            state["status"] = "running"
            state.pop("job", None)
    else:
        print(json.dumps(args, ensure_ascii=False))
else:
    print(json.dumps(args, ensure_ascii=False))
save()
print("OKAY [ 0.001s ]", file=sys.stderr)
