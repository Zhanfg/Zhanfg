from __future__ import annotations

import base64
import ctypes
import ctypes.util
import json
import lzma
import os
import struct
import threading
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional, Tuple

import lz4.block
import fsb5

CATALOG_PATH = "assets/aa/catalog.json"
BUNDLE_PREFIX = "assets/aa/Android/"


class ExtractError(RuntimeError):
    pass


class ProgressLogger:
    """Thread-safe line logger which can be tailed by the Android UI."""

    def __init__(self, path: Optional[str]):
        self._lock = threading.Lock()
        self._stream = None
        if path:
            log_path = Path(path)
            log_path.parent.mkdir(parents=True, exist_ok=True)
            self._stream = log_path.open("w", encoding="utf-8", buffering=1)

    def log(self, message: str) -> None:
        if self._stream is None:
            return
        timestamp = time.strftime("%H:%M:%S")
        with self._lock:
            self._stream.write(f"{timestamp} {message}\n")
            self._stream.flush()

    def close(self) -> None:
        if self._stream is None:
            return
        with self._lock:
            self._stream.close()
            self._stream = None


class ByteReader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def read(self, size: int) -> bytes:
        end = self.pos + size
        if end > len(self.data):
            raise ExtractError("Unexpected end of binary data")
        out = self.data[self.pos:end]
        self.pos = end
        return out

    def u16be(self) -> int:
        return struct.unpack(">H", self.read(2))[0]

    def u32be(self) -> int:
        return struct.unpack(">I", self.read(4))[0]

    def i64be(self) -> int:
        return struct.unpack(">q", self.read(8))[0]

    def cstring(self) -> str:
        end = self.data.find(b"\0", self.pos)
        if end < 0:
            raise ExtractError("Unterminated string in UnityFS header")
        raw = self.data[self.pos:end]
        self.pos = end + 1
        return raw.decode("utf-8", "replace")

    def align(self, alignment: int) -> None:
        self.pos = (self.pos + alignment - 1) // alignment * alignment


class LittleReader:
    def __init__(self, data: bytes):
        self.data = data
        self.pos = 0

    def u32(self) -> int:
        end = self.pos + 4
        if end > len(self.data):
            raise ExtractError("Addressables bucket data is truncated")
        value = int.from_bytes(self.data[self.pos:end], "little")
        self.pos = end
        return value


class ApkSet:
    """APK index with serialized ZipFile reads.

    zipfile.ZipFile isn't guaranteed to be safe for concurrent reads from the
    same object. The relatively short archive read is serialized, while UnityFS
    decompression and FSB5 decoding continue in worker threads in parallel.
    """

    def __init__(self, paths: Iterable[str]):
        self.archives: List[zipfile.ZipFile] = []
        self.index: Dict[str, zipfile.ZipFile] = {}
        self._read_lock = threading.Lock()
        for path in paths:
            zf = zipfile.ZipFile(path, "r")
            self.archives.append(zf)
            for name in zf.namelist():
                self.index.setdefault(name, zf)

    def close(self) -> None:
        for zf in self.archives:
            zf.close()

    def exists(self, name: str) -> bool:
        return name in self.index

    def read(self, name: str) -> bytes:
        zf = self.index.get(name)
        if zf is None:
            raise KeyError(name)
        with self._read_lock:
            return zf.read(name)


@dataclass
class UnityBlock:
    uncompressed_size: int
    compressed_size: int
    flags: int


COMPRESSION_MASK = 0x3F
BLOCKS_INFO_AT_END = 0x80
BLOCK_INFO_NEED_PADDING_AT_START = 0x200


def _decompress(data: bytes, compression: int, expected_size: int) -> bytes:
    if compression == 0:
        out = data
    elif compression == 1:
        try:
            out = lzma.decompress(data, format=lzma.FORMAT_ALONE)
        except lzma.LZMAError:
            out = lzma.decompress(data)
    elif compression in (2, 3):
        out = lz4.block.decompress(data, uncompressed_size=expected_size)
    else:
        raise ExtractError(f"Unsupported UnityFS compression type: {compression}")

    if expected_size >= 0 and len(out) != expected_size:
        raise ExtractError(
            f"UnityFS decompression size mismatch: got {len(out)}, expected {expected_size}"
        )
    return out


def unpack_unityfs(bundle: bytes) -> bytes:
    r = ByteReader(bundle)
    signature = r.cstring()
    if signature != "UnityFS":
        return bundle

    version = r.u32be()
    r.cstring()
    r.cstring()
    r.i64be()
    compressed_info_size = r.u32be()
    uncompressed_info_size = r.u32be()
    flags = r.u32be()

    if version >= 7:
        r.align(16)

    data_start_after_header = r.pos

    if flags & BLOCKS_INFO_AT_END:
        info_pos = len(bundle) - compressed_info_size
        if info_pos < 0:
            raise ExtractError("Invalid UnityFS blocks-info offset")
        info_compressed = bundle[info_pos:info_pos + compressed_info_size]
        r.pos = data_start_after_header
    else:
        info_compressed = r.read(compressed_info_size)

    info = _decompress(info_compressed, flags & COMPRESSION_MASK, uncompressed_info_size)

    ir = ByteReader(info)
    ir.read(16)
    block_count = ir.u32be()
    blocks: List[UnityBlock] = []
    for _ in range(block_count):
        blocks.append(UnityBlock(ir.u32be(), ir.u32be(), ir.u16be()))

    if flags & BLOCK_INFO_NEED_PADDING_AT_START:
        r.align(16)

    output = bytearray()
    for block in blocks:
        compressed = r.read(block.compressed_size)
        output.extend(
            _decompress(compressed, block.flags & COMPRESSION_MASK, block.uncompressed_size)
        )
    return bytes(output)


def parse_catalog(catalog_bytes: bytes) -> List[Tuple[str, str]]:
    data = json.loads(catalog_bytes.decode("utf-8"))
    try:
        key_data = base64.b64decode(data["m_KeyDataString"])
        bucket_data = base64.b64decode(data["m_BucketDataString"])
        entry_data = base64.b64decode(data["m_EntryDataString"])
    except KeyError as exc:
        raise ExtractError(f"Addressables catalog missing field: {exc}") from exc

    rows: List[List[object]] = []
    br = LittleReader(bucket_data)
    bucket_count = br.u32()

    for _ in range(bucket_count):
        key_pos = br.u32()
        if key_pos >= len(key_data):
            raise ExtractError("Addressables key offset out of range")

        key_type = key_data[key_pos]
        key_pos += 1

        if key_type == 0:
            if key_pos + 4 > len(key_data):
                raise ExtractError("Invalid UTF-8 Addressables key")
            length = int.from_bytes(key_data[key_pos:key_pos + 4], "little")
            key_pos += 4
            key_value = key_data[key_pos:key_pos + length].decode("utf-8")
        elif key_type == 1:
            if key_pos + 4 > len(key_data):
                raise ExtractError("Invalid UTF-16 Addressables key")
            length = int.from_bytes(key_data[key_pos:key_pos + 4], "little")
            key_pos += 4
            raw = key_data[key_pos:key_pos + length]
            key_value = raw.decode("utf-16")
        elif key_type == 4:
            if key_pos >= len(key_data):
                raise ExtractError("Invalid integer Addressables key")
            key_value = key_data[key_pos]
        else:
            raise ExtractError(f"Unknown Addressables key type: {key_type}")

        entry_count = br.u32()
        for _ in range(entry_count):
            entry_index = br.u32()
            start = 4 + 28 * entry_index
            if start + 10 > len(entry_data):
                raise ExtractError("Addressables entry offset out of range")
            entry_value = int.from_bytes(entry_data[start + 8:start + 10], "little")
            rows.append([key_value, entry_value])

    for i, row in enumerate(rows):
        ref = row[1]
        if isinstance(ref, int) and ref != 0xFFFF:
            if ref >= len(rows):
                raise ExtractError("Addressables entry reference out of range")
            rows[i][1] = rows[ref][0]

    result: List[Tuple[str, str]] = []
    for key_value, entry_value in rows:
        if not isinstance(key_value, str) or not isinstance(entry_value, str):
            continue
        if not key_value.startswith("Assets/Tracks/"):
            continue
        if key_value.startswith("Assets/Tracks/#"):
            continue
        result.append((key_value[len("Assets/Tracks/"):], entry_value))
    return result


def _configure_fsb_native_libs(native_lib_dir: str) -> None:
    import fsb5.utils

    original = fsb5.utils.load_lib

    def android_load_lib(*names):
        attempts: List[str] = []
        for name in names:
            candidates = [f"lib{name}.so", os.path.join(native_lib_dir, f"lib{name}.so")]
            for candidate in candidates:
                attempts.append(candidate)
                try:
                    return ctypes.CDLL(candidate)
                except OSError:
                    pass
            try:
                found = ctypes.util.find_library(name)
                if found:
                    return ctypes.CDLL(found)
            except Exception:
                pass
        try:
            return original(*names)
        except Exception as exc:
            raise ExtractError(
                "Unable to load libogg/libvorbis from APK native libraries: " + ", ".join(attempts)
            ) from exc

    fsb5.utils.load_lib = android_load_lib


def _safe_filename(name: str) -> str:
    bad = '<>:"/\\|?*\x00'
    table = str.maketrans({c: "_" for c in bad})
    cleaned = name.translate(table).strip().strip(".")
    return cleaned or "unnamed"


def _find_fsb(payload: bytes):
    start = 0
    while True:
        pos = payload.find(b"FSB5", start)
        if pos < 0:
            return None
        try:
            bank = fsb5.FSB5(payload[pos:])
            if bank.samples:
                return bank
        except Exception:
            pass
        start = pos + 4


def _extract_one(
    apks: ApkSet,
    out: Path,
    key: str,
    bundle_name: str,
    logger: ProgressLogger,
) -> Tuple[bool, str]:
    song_id = key[:-12]
    bundle_path = BUNDLE_PREFIX + bundle_name

    if not apks.exists(bundle_path):
        message = f"{song_id}: bundle missing: {bundle_path}"
        logger.log(f"[跳过] {message}")
        return False, message

    started = time.monotonic()
    logger.log(f"[解析] {song_id}")

    try:
        bundle = apks.read(bundle_path)
        payload = unpack_unityfs(bundle)
        bank = _find_fsb(payload)
        if bank is None:
            raise ExtractError("FSB5 payload not found")

        sample = bank.samples[0]
        ext = bank.get_sample_extension()
        if ext not in ("ogg", "mp3", "wav"):
            raise ExtractError(f"Unsupported audio format: {bank.header.mode}")

        rebuilt = bank.rebuild_sample(sample)
        destination = out / f"{_safe_filename(song_id)}.{ext}"
        tmp = destination.with_suffix(destination.suffix + ".part")
        tmp.write_bytes(rebuilt)
        os.replace(tmp, destination)

        elapsed = time.monotonic() - started
        logger.log(
            f"[完成] {destination.name}  {len(rebuilt) / (1024 * 1024):.2f} MiB  {elapsed:.2f}s"
        )
        return True, destination.name
    except Exception as exc:
        message = f"{song_id}: {exc}"
        logger.log(f"[失败] {message}")
        return False, message


def extract_from_apks(
    apk_paths_text: str,
    output_dir: str,
    native_lib_dir: str,
    progress_log_path: Optional[str] = None,
    worker_count: int = 4,
) -> int:
    apk_paths = [x.strip() for x in apk_paths_text.splitlines() if x.strip()]
    if not apk_paths:
        raise ExtractError("No APK paths were provided")

    out = Path(output_dir)
    out.mkdir(parents=True, exist_ok=True)
    for old in out.iterdir():
        if old.is_file():
            old.unlink()

    logger = ProgressLogger(progress_log_path)
    apks = None

    try:
        logger.log(f"[准备] APK 数量：{len(apk_paths)}")
        _configure_fsb_native_libs(native_lib_dir)

        apks = ApkSet(apk_paths)
        if not apks.exists(CATALOG_PATH):
            raise ExtractError(f"Missing {CATALOG_PATH}")

        logger.log("[目录] 正在解析 Addressables catalog")
        rows = parse_catalog(apks.read(CATALOG_PATH))

        # Catalogs can contain repeated references. Deduplicate by track key so
        # worker threads never race on the same output filename.
        seen_keys = set()
        music_rows: List[Tuple[str, str]] = []
        for key, value in rows:
            if not key.endswith(".0/music.wav") or key in seen_keys:
                continue
            seen_keys.add(key)
            music_rows.append((key, value))

        if not music_rows:
            raise ExtractError("No Phigros music entries found in Addressables catalog")

        requested_workers = max(1, int(worker_count or 1))
        workers = min(requested_workers, len(music_rows))
        logger.log(f"[并发] 发现 {len(music_rows)} 首，启动 {workers} 个解析线程")

        extracted = 0
        failures: List[str] = []
        started = time.monotonic()

        with ThreadPoolExecutor(max_workers=workers, thread_name_prefix="phigros") as pool:
            futures = [
                pool.submit(_extract_one, apks, out, key, bundle_name, logger)
                for key, bundle_name in music_rows
            ]
            completed = 0
            for future in as_completed(futures):
                ok, detail = future.result()
                completed += 1
                if ok:
                    extracted += 1
                else:
                    failures.append(detail)
                logger.log(f"[进度] {completed}/{len(music_rows)}，成功 {extracted}，失败 {len(failures)}")

        elapsed = time.monotonic() - started
        logger.log(
            f"[汇总] 解析结束：成功 {extracted}，失败 {len(failures)}，耗时 {elapsed:.2f}s"
        )

        if extracted == 0:
            detail = "\n".join(failures[:8])
            raise ExtractError("No songs could be extracted" + ("\n" + detail if detail else ""))

        if failures:
            (out / "_partial_failures.txt").write_text("\n".join(failures), encoding="utf-8")

        return extracted
    finally:
        if apks is not None:
            apks.close()
        logger.close()
