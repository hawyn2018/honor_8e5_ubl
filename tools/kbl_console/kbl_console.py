#!/usr/bin/env python3
"""Fastboot KBL client. A host client is not a device kernel implementation."""
from __future__ import annotations

import codecs
from contextlib import contextmanager
from dataclasses import dataclass, field
import hashlib
import os
from pathlib import Path
import re
import secrets
import shlex
import shutil
import struct
import subprocess
import sys
import tempfile
import time
from typing import Callable, Iterable, Sequence, TextIO

PROTOCOL = "KBL1"
DEFAULT_WIRE_LIMIT = 60
MAX_INFO_BYTES = 56
LEGACY_LINE_BYTES = 4096  # Negotiation fallback for earlier firmware only.
HEX_ID = re.compile(r"[0-9a-fA-F]{1,32}\Z")
INFO_LINE = re.compile(r"^\s*(?:\(bootloader\)\s*)?(?:INFO)?(KBL1(?: .*)?)\s*$")
CONTROL_NAMES = {"kblstart", "kbladb", "kblin", "kbloff", "kblstop", "kblstatus"}
VALUE_OPTIONS = {"-s", "--serial", "-S", "--sparse-limit", "--slot"}
FLAG_OPTIONS = {"-v", "--verbose", "--unbuffered", "--force", "--disable-verity",
                "--disable-verification", "--skip-secondary", "--skip-reboot"}


class KblError(Exception):
    pass


class ProtocolError(KblError):
    pass


class TransportError(KblError):
    pass


class DisconnectedError(TransportError):
    """The local native client lost or timed out waiting for the transport."""
    pass


@dataclass(frozen=True)
class Reply:
    fields: dict[str, str] = field(default_factory=dict)
    output: bytes = b""

    def identifier(self, key: str, required: bool = True) -> str | None:
        value = self.fields.get(key)
        if value is None and not required:
            return None
        if value is None or not HEX_ID.fullmatch(value):
            raise ProtocolError(f"缺少或无效的 KBL {key} 标识。")
        return value.lower()

    def integer(self, key: str) -> int:
        value = self.fields.get(key, "")
        if not re.fullmatch(r"-?[0-9]+", value):
            raise ProtocolError(f"缺少或无效的 KBL {key} 数值。")
        result = int(value)
        # Firmware emits signed error values and unsigned64 size/count values.
        if not -(1 << 63) <= result <= (1 << 64) - 1:
            raise ProtocolError(f"KBL {key} 数值越界。")
        return result


def parse_reply(stdout: bytes, stderr: bytes = b"", *, tag_required: bool = True) -> Reply:
    """Parse only tagged INFO lines; ordinary fastboot progress is not metadata."""
    fields: dict[str, str] = {}
    output = bytearray()
    found = False
    for raw in stdout.splitlines() + stderr.splitlines():
        text = raw.decode("utf-8", errors="replace")
        match = INFO_LINE.fullmatch(text)
        if not match:
            continue
        tagged = match.group(1).rstrip()
        try:
            encoded = tagged.encode("ascii")
        except UnicodeEncodeError as exc:
            raise ProtocolError("KBL 协议元数据不是 ASCII。") from exc
        if len(encoded) > MAX_INFO_BYTES:
            raise ProtocolError("KBL INFO 包超过约定长度。")
        found = True
        words = tagged.split()
        for token in words[1:]:
            if "=" not in token:
                raise ProtocolError("KBL 元数据字段格式错误。")
            key, value = token.split("=", 1)
            if not re.fullmatch(r"[a-z][a-z0-9_]*", key) or not value:
                raise ProtocolError("KBL 元数据字段无效。")
            if key == "out":
                if len(value) % 2 or not re.fullmatch(r"[0-9a-fA-F]+", value):
                    raise ProtocolError("KBL stdout 十六进制数据损坏。")
                output.extend(bytes.fromhex(value))
            else:
                if key in fields and fields[key] != value:
                    raise ProtocolError(f"KBL {key} 元数据互相冲突。")
                fields[key] = value
    if not found and tag_required:
        raise ProtocolError("设备未返回 KBL1 协议；不能据普通 OKAY 判断环境已启动。")
    return Reply(fields, bytes(output))


def control_invocation(args: Sequence[str]) -> tuple[list[str], str] | None:
    """Only recognize exact controls after known global options; else passthrough."""
    index = 0
    while index < len(args) and args[index].startswith("-"):
        option = args[index]
        if option == "--":
            index += 1
            break
        if option in VALUE_OPTIONS:
            if index + 1 >= len(args):
                return None
            index += 2
        elif option in FLAG_OPTIONS or any(option.startswith(v + "=") for v in VALUE_OPTIONS):
            index += 1
        elif any(option.startswith(v) and len(option) > len(v) for v in ("-s", "-S")):
            index += 1
        else:
            return None
    tail = list(args[index:])
    if len(tail) == 2 and tail[0] == "oem" and tail[1] in CONTROL_NAMES:
        return list(args[:index]), tail[1]
    return None


class NativeFastboot:
    def __init__(self, executable: str | Path | Sequence[str], prefix: Sequence[str] = (),
                 wire_limit: int = DEFAULT_WIRE_LIMIT, timeout: float = 30):
        self.executable = ([str(executable)] if isinstance(executable, (str, Path))
                           else list(executable))
        if not self.executable:
            raise KblError("未指定原生 Fastboot。")
        if not 35 <= wire_limit <= 63:
            raise KblError("当前 KBL wire limit 应在 35–63 之间。")
        if timeout <= 0:
            raise KblError("KBL transport timeout 必须为正数。")
        self.prefix = list(prefix)
        self.wire_limit = wire_limit
        self.timeout = timeout

    @contextmanager
    def _transfer_wait(self, payload_bytes: int):
        """Allow synchronous hash/copy time; keep an explicit outer deadline."""
        configured = self.timeout
        self.timeout = configured + payload_bytes / (1024 * 1024)
        try:
            yield
        finally:
            self.timeout = configured

    def passthrough(self, args: Sequence[str]) -> int:
        # No shell, argument rewriting, output parsing or synthetic device commands.
        try:
            return subprocess.call(self.executable + list(args))
        except OSError as exc:
            raise TransportError(f"启动原生 Fastboot 失败：{exc}") from exc

    def _capture(self, args: Sequence[str]) -> subprocess.CompletedProcess:
        command = self.executable + self.prefix + list(args)
        flags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
        try:
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       creationflags=flags, start_new_session=os.name != "nt")
        except OSError as exc:
            raise TransportError(f"启动原生 Fastboot 失败：{exc}") from exc
        timeout = self.timeout
        deadline = getattr(self, "_reconnect_deadline", None)
        if deadline is not None:
            timeout = min(timeout, max(0.001, deadline - time.monotonic()))
        try:
            stdout, stderr = process.communicate(timeout=timeout)
        except (KeyboardInterrupt, subprocess.TimeoutExpired) as exc:
            # Only the local native client is terminated. No device cancel/off RPC.
            process.terminate()
            try:
                process.communicate(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.communicate()
            if isinstance(exc, KeyboardInterrupt):
                raise
            raise DisconnectedError("Fastboot 请求超时；设备环境未被此客户端销毁。") from exc
        return subprocess.CompletedProcess(command, process.returncode, stdout, stderr or b"")

    @staticmethod
    def _is_disconnected(result: subprocess.CompletedProcess) -> bool:
        detail = (result.stdout + result.stderr).decode("utf-8", errors="replace").lower()
        # A real device FAIL is a completed transaction, not a re-enumeration.
        if "remote:" in detail:
            return False
        return any(word in detail for word in ("no such device", "no device", "device not found", "device disconnected",
                   "status read failed", "usb error", "failed to open device", "could not open device",
                   "write to device failed", "read from device failed", "waiting for any device"))

    @staticmethod
    def _activation_identity(reply: Reply, expected_id: str | None = None) -> str:
        identifier = reply.identifier("id")
        if int(identifier, 16) == 0:
            raise ProtocolError("设备返回空 KBL 会话标识；启动请求未重发。")
        if expected_id is not None and identifier != expected_id:
            raise ProtocolError("等待期间 KBL 会话已更换；启动请求未重发。")
        if reply.fields.get("status") not in ("running", "busy") or reply.integer("el") not in (1, 2, 3):
            raise ProtocolError("设备没有报告已启动的 KBL 环境；启动请求未重发。")
        if reply.fields.get("status") == "busy":
            reply.identifier("job")
        return identifier

    def _wait_after_start(self, *, require_adb: bool = False, expected_id: str | None = None) -> Reply:
        """Never replay START/ADB. Observe activation with read-only status.

        A dropped ACK leaves START's outcome uncertain. Re-sending it could
        repeat a mutation, so every subsequent transaction is STATUS or its
        generation-bound reply-page queries, with the same native selector.
        """
        self._reconnect_deadline = time.monotonic() + self.timeout
        last: TransportError | None = None
        try:
            while time.monotonic() < self._reconnect_deadline:
                try:
                    reply = self.request("kblstatus")
                    identifier = self._activation_identity(reply, expected_id)
                    if expected_id is None:
                        expected_id = identifier
                    if not require_adb or reply.fields.get("adb") == "active":
                        return reply
                    phase = reply.fields.get("adb", "missing")
                    if phase not in ("waiting-start-ack", "starting", "prepared", "off-parked", "disabled"):
                        raise ProtocolError(f"KBL 已运行，但 ADB 未启动：{phase}；kbladb 未重发。")
                except TransportError as exc:
                    last = exc
                    remaining = self._reconnect_deadline - time.monotonic()
                    if remaining > 0:
                        time.sleep(min(0.15, remaining))
            target = "KBL ADB" if require_adb else "KBL"
            raise TransportError(f"等待 {target} 就绪超时；仅查询了状态，没有重发启动请求。") from last
        finally:
            del self._reconnect_deadline

    def request(self, name: str, argument: str | None = None, *, tag_required: bool = True) -> Reply:
        if not re.fullmatch(r"kbl[a-z]+", name):
            raise KblError("KBL 协议命令名称无效。")
        pieces = ["oem", name] + ([] if argument is None else [argument])
        wire = " ".join(pieces)
        if not wire.isascii() or len(wire.encode("ascii")) > self.wire_limit:
            raise KblError(f"请求超过已配置的 {self.wire_limit} 字节线长；未发送，也未截断。")
        try:
            result = self._capture(pieces)
            if result.returncode:
                detail = (result.stderr + result.stdout).decode("utf-8", errors="replace").strip()
                error = DisconnectedError if self._is_disconnected(result) else TransportError
                raise error(f"Fastboot 请求失败（{result.returncode}）：{detail}")
            if not tag_required or any(INFO_LINE.fullmatch(line.decode("utf-8", errors="replace"))
                                       for line in result.stdout.splitlines() + result.stderr.splitlines()):
                reply = parse_reply(result.stdout, result.stderr, tag_required=tag_required)
            else:
                # A plain OKAY does not prove startup; use generation-bound reply pages.
                reply = self._deferred_reply()
        except DisconnectedError:
            if name in ("kblstart", "kbladb") and argument is None and tag_required:
                return self._wait_after_start(require_adb=name == "kbladb")
            raise
        if name == "kbladb" and argument is None and tag_required:
            identifier = self._activation_identity(reply)
            if reply.fields.get("adb") != "active":
                return self._wait_after_start(require_adb=True, expected_id=identifier)
        return reply

    def _kbl_getvar(self, name: str) -> str:
        if not name.isascii() or len("getvar:" + name) > self.wire_limit:
            raise ProtocolError("KBL 回复查询线长无效。")
        result = self._capture(["getvar", name])
        if result.returncode:
            if self._is_disconnected(result):
                raise DisconnectedError("查询 KBL 回复页时 USB 连接中断；原请求未被重发。")
            raise ProtocolError("设备拒绝 KBL 回复页查询；原请求未被重发。")
        matcher = re.compile(r"^\s*(?:\(bootloader\)\s*)?" + re.escape(name) + r":\s*(.*?)\s*$")
        values = [m.group(1) for line in (result.stdout + b"\n" + result.stderr).splitlines()
                  if (m := matcher.fullmatch(line.decode("utf-8", errors="replace")))]
        if len(values) != 1:
            raise ProtocolError("设备未返回唯一 KBL 回复页；不能据普通 OKAY 判断启动成功。")
        return values[0]

    def _deferred_reply(self) -> Reply:
        header = self._kbl_getvar("kbl-reply-header")
        match = re.fullmatch(r"KBLC1 g=([0-9a-fA-F]{16}) n=([1-9][0-9]*)", header)
        if not match:
            raise ProtocolError("KBL 延迟回复头无效。")
        generation, count = match.group(1).lower(), int(match.group(2))
        pages = []
        for index in range(count):
            value = self._kbl_getvar(f"kbl-reply:{generation}:{index}")
            try:
                encoded = value.encode("ascii")
            except UnicodeEncodeError as exc:
                raise ProtocolError("KBL 回复页不是 ASCII。") from exc
            if len(encoded) > MAX_INFO_BYTES or not INFO_LINE.fullmatch(value):
                raise ProtocolError("KBL 回复页格式或长度无效。")
            pages.append(encoded)
        return parse_reply(b"\n".join(pages))

    def store_file(self, source: str | Path, remote_path: str) -> Reply:
        """Stage an immutable, checksummed envelope; never flash a partition."""
        if not remote_path.startswith("/") or "\x00" in remote_path:
            raise KblError("KBL 目标应为不含 NUL 的绝对文件路径。")
        try:
            path_bytes = remote_path.encode("utf-8")
        except UnicodeEncodeError as exc:
            raise KblError("KBL 目标路径不是有效 UTF-8。") from exc
        if not 1 <= len(path_bytes) <= 0xffffffff:
            raise KblError("KBL 目标路径长度超出协议的 uint32 字段。")
        nonce = secrets.randbits(64)
        temporary = None
        try:
            digest, count = hashlib.sha256(), 0
            with Path(source).open("rb") as incoming, tempfile.NamedTemporaryFile(prefix="kbl-stage-", suffix=".bin", delete=False) as outgoing:
                temporary = Path(outgoing.name)
                before = os.fstat(incoming.fileno())
                if before.st_size > 0xffffffff - 64 - len(path_bytes):
                    raise KblError("Fastboot DATA使用32位长度字段；更大的文件请通过ADB传输。")
                outgoing.write(b"\0" * 64)
                outgoing.write(path_bytes)
                while block := incoming.read(1024 * 1024):
                    outgoing.write(block); digest.update(block); count += len(block)
                after = os.fstat(incoming.fileno())
                if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns) or count != before.st_size:
                    raise KblError("源文件在复制期间改变，未发送。")
                outgoing.seek(0)
                outgoing.write(struct.pack("<8sQIIQ32s", b"KBLPUT01", nonce, len(path_bytes), 0, count, digest.digest()))
            # STORE hashes and copies synchronously before acknowledging. Its
            # wait grows with real bytes at a conservative1MiB/s plus the
            # configured base; _capture still honors _reconnect_deadline.
            with self._transfer_wait(64 + len(path_bytes) + count):
                result = self._capture(["stage", str(temporary)])
                if result.returncode:
                    raise TransportError("Fastboot RAM 暂存失败；没有执行分区刷写或自动重试。")
                reply = self.request("kblstore", f"env1:{nonce:016x}")
            if reply.fields.get("stored") != "1" or reply.integer("size") != count:
                raise ProtocolError("KBL 未确认完整文件已保存。")
            reported_hash = reply.fields.get("hash0", "") + reply.fields.get("hash1", "")
            if reported_hash.lower() != digest.hexdigest():
                raise ProtocolError("KBL 文件 SHA-256 与发送源不一致，未自动执行程序。")
            reply.identifier("id")
            return reply
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)

    def submit(self, line: str) -> Reply:
        if "\x00" in line or "\r" in line or "\n" in line:
            raise KblError("一条请求应为不含 NUL 或换行的完整命令行。")
        try:
            data = line.encode("utf-8")
        except UnicodeEncodeError as exc:
            raise KblError("命令包含无效的 Unicode 字符；未发送。") from exc
        if not data:
            raise KblError("命令不能为空。")
        if len("oem kblcmd ") + len(data) * 2 <= self.wire_limit:
            return self.request("kblcmd", data.hex())
        started = self.request("kblput", "B")
        identifier = started.identifier("put")
        if len(identifier) != 16:
            raise ProtocolError("命令上传标识不是协议要求的 16 位十六进制。")
        if len(data) > LEGACY_LINE_BYTES and started.fields.get("command64") != "1":
            raise ProtocolError("设备使用旧版固定命令缓冲，请更新KBL后发送此命令。")
        offset = 0
        while offset < len(data):
            header = f"D{identifier}:{offset:04x}:"
            capacity = (self.wire_limit - len("oem kblput ") - len(header)) // 2
            if capacity < 1:
                raise KblError("已配置线长不足以承载当前偏移字段。")
            part = data[offset:offset + capacity]
            # Each frame remains within the actual Fastboot packet contract.
            # No retry can duplicate an acknowledged staging position.
            self.request("kblput", header + part.hex(), tag_required=False)
            offset += len(part)
        return self.request("kblput", f"E{identifier}:{len(data):04x}")

    def fetch_kbl_file(self, remote: str, destination: str | Path) -> Path:
        """Open a device-owned immutable snapshot, verify binary fetch, close it."""
        target = Path(destination).expanduser().absolute()
        if target.exists() or not target.parent.is_dir():
            raise KblError("回读目标应为现有目录中的新文件名。")
        try:
            path = remote.encode("utf-8")
        except UnicodeEncodeError as exc:
            raise KblError("KBL 路径不是有效 UTF-8。") from exc
        if not remote.startswith("/") or b"\0" in path or not 1 <= len(path) <= 0xffffffff - 64:
            raise KblError("KBL 回读路径需为不含 NUL、协议可表示长度的绝对路径。")
        temporary, alias, problem = None, None, None
        try:
            if len("oem kblreadopen ") + 2 * len(path) <= self.wire_limit:
                reply = self.request("kblreadopen", path.hex())
            else:
                nonce = secrets.randbits(64)
                with tempfile.NamedTemporaryFile(prefix="kbl-read-", suffix=".bin", delete=False) as stage:
                    temporary = Path(stage.name)
                    stage.write(struct.pack("<8sQIIQ32s", b"KBLPUT01", nonce, len(path), 0, 0,
                                            hashlib.sha256(b"").digest()) + path)
                result = self._capture(["stage", str(temporary)])
                if result.returncode:
                    raise TransportError("回读路径 RAM 暂存失败；未重试或执行分区刷写。")
                reply = self.request("kblreadopen", f"env1:{nonce:016x}")
            candidate = reply.fields.get("file", "")
            if not re.fullmatch(r"kbl\.[0-9a-fA-F]{16}", candidate):
                raise ProtocolError("设备未返回有效的 KBL 快照句柄。")
            alias = candidate.lower()
            size = reply.integer("size")
            digest = reply.fields.get("hash0", "") + reply.fields.get("hash1", "")
            if size < 0 or not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
                raise ProtocolError("KBL 快照长度或 SHA-256 无效。")
            if size == 0:
                if digest.lower() != hashlib.sha256(b"").hexdigest():
                    raise ProtocolError("空文件的 SHA-256 不一致。")
                # Standard fetch rejects zero-sized chunks. This is a verified
                # empty snapshot, not a fake response from an absent backend.
                with target.open("xb"):
                    pass
                return target
            return self.fetch(alias, target, expected_size=size, expected_sha256=digest)
        except BaseException as exc:
            problem = exc
            raise
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
            if alias is not None:
                try:
                    self.request("kblreadclose", alias, tag_required=False)
                except (KblError, OSError, KeyboardInterrupt) as cleanup:
                    if problem is not None:
                        problem.add_note(f"KBL 快照 {alias} 关闭失败：{cleanup}；可稍后显式 kbloff 清理。")
                    else:
                        raise KblError(f"文件已校验保存，但快照 {alias} 关闭失败：{cleanup}。") from cleanup

    def fetch(self, partition: str, destination: str | Path, *,
              expected_size: int | None = None, expected_sha256: str | None = None) -> Path:
        """Standard native binary fetch, not INFO-encoded emulated transfer.

        The target firmware must actually implement fetch. No backend is supplied
        by this host function; unsupported targets return their native failure.
        """
        if expected_size is None or expected_size < 0 or expected_size > 0xffffffffffffffff:
            raise KblError("回读前需要有效的预期字节数；当前未发送读取请求。")
        if expected_sha256 is None or not re.fullmatch(r"[a-fA-F0-9]{64}", expected_sha256):
            raise KblError("回读前需要完整 SHA-256；当前未发送读取请求。")
        if not re.fullmatch(r"[a-zA-Z0-9_.-]+", partition):
            raise KblError("fetch 需要分区名称或已发布的 kbl.ID 别名。")
        target = Path(destination).expanduser().absolute()
        if target.exists():
            raise KblError("目标文件已存在；请选择新文件名。")
        if not target.parent.is_dir():
            raise KblError("目标目录不存在。")
        descriptor, temporary = tempfile.mkstemp(prefix=".kbl-fetch-", suffix=".partial", dir=target.parent)
        os.close(descriptor)
        temporary_path = Path(temporary)
        try:
            result = self._capture(["fetch", partition, str(temporary_path)])
            if result.returncode:
                detail = (result.stderr + result.stdout).decode("utf-8", errors="replace").strip()
                raise TransportError(f"原生二进制 fetch 失败：{detail}")
            digest = hashlib.sha256()
            actual_size = 0
            with temporary_path.open("rb") as source:
                while chunk := source.read(1024 * 1024):
                    digest.update(chunk)
                    actual_size += len(chunk)
            if actual_size != expected_size:
                raise TransportError(f"回读大小不一致：预期 {expected_size}，收到 {actual_size} 字节。")
            if digest.hexdigest() != expected_sha256.lower():
                raise TransportError("回读 SHA-256 不一致；没有发布目标文件。")
            # Exclusive final creation prevents a race from overwriting an unrelated file.
            with temporary_path.open("rb") as source, target.open("xb") as output:
                try:
                    shutil.copyfileobj(source, output)
                except BaseException:
                    output.close()
                    target.unlink(missing_ok=True)
                    raise
            return target
        finally:
            temporary_path.unlink(missing_ok=True)


class Console:
    def __init__(self, transport: NativeFastboot, *, output: TextIO = sys.stdout,
                 error: TextIO = sys.stderr, reader: Callable[[str], str] = input,
                 poll_interval: float = 0.05, sleeper: Callable[[float], None] = time.sleep):
        self.transport, self.output, self.error, self.reader = transport, output, error, reader
        self.poll_interval, self.sleeper = poll_interval, sleeper
        self.session_id: str | None = None
        self.el: int | None = None
        self.decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")

    def _check_identity(self, reply: Reply, required: bool = False) -> None:
        identity = reply.identifier("id", required=required)
        if identity is not None:
            if self.session_id is not None and self.session_id != identity:
                raise ProtocolError("设备 KBL 会话已更换；停止使用旧任务标识。")
            self.session_id = identity
        if "el" in reply.fields:
            level = reply.integer("el")
            if level not in range(4):
                raise ProtocolError("KBL EL 读值无效。")
            if self.el is not None and self.el != level:
                raise ProtocolError("设备报告的 KBL EL 与接管时不同。")
            self.el = level

    def _display(self, data: bytes, final: bool = False) -> None:
        text = self.decoder.decode(data, final=final)
        self.output.write(text)
        self.output.flush()
        if final:
            self.decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")

    def poll(self, job: str) -> int:
        if not HEX_ID.fullmatch(job):
            raise ProtocolError("任务标识无效。")
        while True:
            reply = self.transport.request("kblpoll", job)
            self._check_identity(reply)
            returned_job = reply.identifier("job", required=False)
            if returned_job is not None and returned_job != job.lower():
                raise ProtocolError("设备返回了其他任务的结果。")
            done = reply.integer("done")
            if done not in (0, 1):
                raise ProtocolError("KBL done 读值无效。")
            self._display(reply.output, final=bool(done))
            if done:
                return reply.integer("rc")
            self.sleeper(self.poll_interval)

    def _capture_command(self, line: str, limit: int | None = None) -> bytes:
        """Run one explicit read command without displaying or replaying it."""
        submitted = self.transport.submit(line)
        self._check_identity(submitted)
        job = submitted.identifier("job")
        output = bytearray(submitted.output)
        while True:
            reply = self.transport.request("kblpoll", job)
            self._check_identity(reply)
            returned_job = reply.identifier("job", required=False)
            if returned_job is not None and returned_job != job:
                raise ProtocolError("设备返回了其他任务的结果。")
            output.extend(reply.output)
            if limit is not None and len(output) > limit:
                raise ProtocolError("分区摘要命令输出超过预期；任务未自动重发。")
            done = reply.integer("done")
            if done not in (0, 1):
                raise ProtocolError("KBL done 读值无效。")
            if done:
                if reply.integer("rc"):
                    detail = bytes(output).decode("utf-8", errors="replace").strip()
                    raise KblError(f"分区摘要命令失败：{detail}")
                return bytes(output)
            self.sleeper(self.poll_interval)

    def pull_partition(self, label: str, destination: str | Path) -> Path:
        """Stream a live partition; publish only after size and three hashes agree."""
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,36}", label) or label.startswith("kbl."):
            raise KblError("分区名需为 1–36 个 ASCII 字母、数字、下划线、点或连字符。")
        target = Path(destination).expanduser().absolute()
        if target.exists() or not target.parent.is_dir():
            raise KblError("回读目标应为现有目录中的新文件名。")
        remote = "/dev/block/by-name/" + label
        def size() -> int:
            value = self.transport._kbl_getvar("partition-size:" + label)
            if not re.fullmatch(r"(?:0[xX])?[0-9a-fA-F]+", value):
                raise ProtocolError("设备返回的分区长度格式无效。")
            result = int(value, 16)
            if result <= 0 or result >= 1 << 63:
                raise ProtocolError("设备返回的分区长度越界。")
            return result
        def source_hash() -> str:
            raw = self._capture_command("sha256sum " + remote)
            match = re.fullmatch(rb"([0-9a-fA-F]{64})  " + re.escape(remote.encode("ascii")) + rb"\n", raw)
            if match is None:
                raise ProtocolError("设备未返回唯一有效的分区 SHA-256。")
            return match.group(1).decode("ascii").lower()
        expected_size = size()
        self.error.write(f"正在计算 {label} 的 SHA-256，随后分块回读。\n")
        expected_hash = source_hash()
        fd, temporary = tempfile.mkstemp(prefix=".kbl-pullpart-", suffix=".verified", dir=target.parent)
        os.close(fd)
        stage = Path(temporary)
        stage.unlink()  # NativeFastboot.fetch exclusively creates its output.
        owned_stage = False
        try:
            self.transport.fetch(label, stage, expected_size=expected_size, expected_sha256=expected_hash)
            owned_stage = True
            if size() != expected_size or source_hash() != expected_hash:
                raise TransportError("回读前后分区长度或摘要改变；未发布目标文件。")
            # Recheck our staged bytes before exclusive publication as well.
            digest = hashlib.sha256()
            count = 0
            with stage.open("rb") as source:
                while block := source.read(1024 * 1024):
                    count += len(block)
                    digest.update(block)
            if count != expected_size or digest.hexdigest() != expected_hash:
                raise TransportError("本地暂存文件在校验后改变；未发布目标文件。")
            created = False
            try:
                with stage.open("rb") as source, target.open("xb") as output:
                    created = True
                    shutil.copyfileobj(source, output)
            except BaseException:
                if created:
                    target.unlink(missing_ok=True)
                raise
            self.error.write(f"已回读 {expected_size} 字节；接收数据与设备前后 SHA-256 一致：{expected_hash}\n")
            return target
        finally:
            if owned_stage:
                stage.unlink(missing_ok=True)

    def attach(self, command: str) -> int:
        if command not in ("kblstart", "kblin"):
            raise KblError("交互控制台入口仅接受 kblstart 或 kblin。")
        try:
            ready = self.transport.request(command)
            self._check_identity(ready, required=True)
            if ready.fields.get("status") not in ("running", "busy"):
                raise ProtocolError("设备没有报告可接管的 KBL 环境。")
            self._display(ready.output)
            self.error.write(f"KBL 会话 {self.session_id}，EL={self.el if self.el is not None else '未报告'}。"
                             "退出终端仅断开连接；kbloff/kblstop 停止环境。\n")
            job = ready.identifier("job", required=False)
            if ready.fields.get("status") == "busy" and job is None:
                raise ProtocolError("设备报告 busy，但缺少可恢复的任务标识。")
            if job is not None:
                status = self.poll(job)
                if status:
                    self.error.write(f"[任务返回 {status}]\n")
            while True:
                try:
                    line = self.reader("# ")
                except EOFError:
                    self.error.write("\n已断开电脑端连接，KBL 环境保留。\n")
                    return 0
                if line.strip() in ("exit", "quit", ":detach"):
                    self.error.write("已断开电脑端连接，KBL 环境保留。\n")
                    return 0
                if not line.strip():
                    continue
                if line.strip() == ":status":
                    reply = self.transport.request("kblstatus")
                    self._check_identity(reply, required=True)
                    self.error.write(" ".join(f"{k}={v}" for k, v in reply.fields.items()) + "\n")
                    continue
                if line.strip() == ":help":
                    self.error.write(":status 查看驻留状态；exit/quit/:detach/Ctrl+C/Ctrl+D 仅断开连接。\n"
                                     ":push LOCAL /tmp/xxx 上传文件；先 mkdir /tmp，再 cd /tmp 和 ./xxx。\n"
                                     ":pull /tmp/xxx LOCAL 回读文件，核对 SHA-256 后保存。\n"
                                     ":pullpart LABEL LOCAL 分块回读实际分区，核对长度及设备前后 SHA-256。\n"
                                     "screenoff/screenon 控制显示；poweroff 关闭设备，与 kbloff 停止环境不同。\n"
                                     "销毁环境须另行执行 fastboot oem kbloff。\n")
                    continue
                try:
                    if line.lstrip().startswith((":push ", ":pull ", ":pullpart ")):
                        lexer = shlex.shlex(line, posix=False)
                        lexer.whitespace_split, lexer.commenters = True, ""
                        pieces = [p[1:-1] if len(p) >= 2 and p[0] == p[-1] and p[0] in "\"'" else p for p in lexer]
                        if len(pieces) != 3 or pieces[0] not in (":push", ":pull", ":pullpart"):
                            raise KblError('用法：:push LOCAL /tmp/xxx、:pull /tmp/xxx LOCAL 或 :pullpart LABEL LOCAL')
                        if pieces[0] == ":pullpart":
                            saved = self.pull_partition(pieces[1], pieces[2])
                            self.error.write(f"已回读并校验至 {saved}。\n")
                            continue
                        if pieces[0] == ":pull":
                            block_prefix = "/dev/block/by-name/"
                            if pieces[1].startswith(block_prefix):
                                saved = self.pull_partition(pieces[1][len(block_prefix):], pieces[2])
                            else:
                                saved = self.transport.fetch_kbl_file(pieces[1], pieces[2])
                            self.error.write(f"已回读并校验至 {saved}。\n")
                            continue
                        reply = self.transport.store_file(pieces[1], pieces[2])
                        self._check_identity(reply, required=True)
                        self.error.write(f"已上传并校验 {reply.integer('size')} 字节至 {pieces[2]}，未自动执行。\n")
                        continue
                    reply = self.transport.submit(line)
                    self._check_identity(reply)
                    job = reply.identifier("job")
                    self._display(reply.output)
                    status = self.poll(job)
                    if status:
                        self.error.write(f"[任务返回 {status}]\n")
                except KblError as exc:
                    # A failed submit may already have reached the device. Do not
                    # repeat it automatically, clear the job or destroy the runtime.
                    raise KblError(f"{exc}\n连接已停止；可用 fastboot oem kblin 重新接管。") from exc
        except KeyboardInterrupt:
            self._display(b"", final=True)
            self.error.write("\n已断开电脑端连接；任务和 KBL 环境保留，可用 kblin 重新接管。\n")
            return 130


def find_native(explicit: str | None = None) -> Path:
    if explicit:
        path = Path(explicit).expanduser().absolute()
        if not path.is_file():
            raise KblError(f"原生 Fastboot 不存在：{path}")
        return path
    name = "fastboot.exe" if os.name == "nt" else "fastboot"
    directory = Path(__file__).resolve().parent
    candidates = [directory / "native" / name]
    candidates.extend(parent / "platform-tools" / name for parent in directory.parents)
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    # Explicitly locate the native executable, never this fastboot.cmd wrapper.
    found = shutil.which(name)
    if found and Path(found).suffix.lower() not in (".cmd", ".bat", ".py"):
        return Path(found)
    raise KblError("未找到原生 Fastboot；使用 --kbl-native 指定 platform-tools 中的可执行文件。")


def host_options(argv: Sequence[str]) -> tuple[dict, list[str]]:
    """Consume only leading --kbl-* options; preserve all native options verbatim."""
    options: dict = {"native": None, "wire_limit": DEFAULT_WIRE_LIMIT,
                     "timeout": 30.0, "no_console": False}
    args = list(argv)
    while args and args[0].startswith("--kbl-"):
        name = args.pop(0)
        if name == "--kbl-no-console":
            options["no_console"] = True
            continue
        keys = {"--kbl-native": ("native", str), "--kbl-wire-limit": ("wire_limit", int),
                "--kbl-timeout": ("timeout", float)}
        if name not in keys or not args:
            raise KblError(f"主机选项不完整或未知：{name}")
        key, conversion = keys[name]
        try:
            options[key] = conversion(args.pop(0))
        except ValueError as exc:
            raise KblError(f"主机选项数值无效：{name}") from exc
    return options, args


def main(argv: Sequence[str] | None = None) -> int:
    try:
        options, args = host_options(sys.argv[1:] if argv is None else argv)
        native = find_native(options["native"] or os.environ.get("KBL_FASTBOOT"))
        invocation = control_invocation(args)
        if invocation is None or options["no_console"]:
            return NativeFastboot(native).passthrough(args)
        prefix, command = invocation
        transport = NativeFastboot(native, prefix, options["wire_limit"], options["timeout"])
        if command in ("kblstart", "kblin"):
            return Console(transport).attach(command)
        reply = transport.request(command)
        print(" ".join(f"{key}={value}" for key, value in reply.fields.items()))
        return 0
    except KeyboardInterrupt:
        return 130
    except (KblError, OSError) as exc:
        print(f"KBL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
