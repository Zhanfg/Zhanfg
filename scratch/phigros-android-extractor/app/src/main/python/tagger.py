from __future__ import annotations

import struct
from pathlib import Path


def _read_png_info(data: bytes) -> tuple[str, int, int, int]:
    if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("not png")
    width, height = struct.unpack(">II", data[16:24])
    bit_depth = data[24]
    color_type = data[25]
    channels = {
        0: 1,
        2: 3,
        3: 1,
        4: 2,
        6: 4,
    }.get(color_type, 3)
    depth = max(1, bit_depth * channels)
    if width <= 0 or height <= 0:
        raise ValueError("PNG has invalid dimensions")
    return "image/png", width, height, depth


def _read_jpeg_info(data: bytes) -> tuple[str, int, int, int]:
    if len(data) < 4 or data[:2] != b"\xff\xd8":
        raise ValueError("not jpeg")

    pos = 2
    while pos + 4 <= len(data):
        if data[pos] != 0xFF:
            pos += 1
            continue
        while pos < len(data) and data[pos] == 0xFF:
            pos += 1
        if pos >= len(data):
            break
        marker = data[pos]
        pos += 1

        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            continue
        if pos + 2 > len(data):
            break
        seg_len = int.from_bytes(data[pos:pos + 2], "big")
        if seg_len < 2 or pos + seg_len > len(data):
            break

        if marker in {
            0xC0, 0xC1, 0xC2, 0xC3,
            0xC5, 0xC6, 0xC7,
            0xC9, 0xCA, 0xCB,
            0xCD, 0xCE, 0xCF,
        }:
            if seg_len < 8:
                raise ValueError("JPEG SOF segment is truncated")
            precision = data[pos + 2]
            height = int.from_bytes(data[pos + 3:pos + 5], "big")
            width = int.from_bytes(data[pos + 5:pos + 7], "big")
            components = data[pos + 7]
            if width <= 0 or height <= 0:
                raise ValueError("JPEG has invalid dimensions")
            return "image/jpeg", width, height, max(1, precision * max(1, components))

        pos += seg_len

    raise ValueError("JPEG dimensions not found")


def _read_image_info(data: bytes) -> tuple[str, int, int, int]:
    try:
        return _read_png_info(data)
    except ValueError:
        pass
    try:
        return _read_jpeg_info(data)
    except ValueError:
        pass
    raise ValueError("cover is neither a valid PNG nor JPEG")


def embed_cover(audio_path: str, cover_path: str, output_format: str) -> str:
    """Embed a front cover without sending an image stream through FFmpeg."""
    audio = Path(audio_path)
    cover = Path(cover_path)
    if not audio.is_file() or audio.stat().st_size <= 0:
        raise ValueError("audio file is missing")
    if not cover.is_file() or cover.stat().st_size <= 0:
        raise ValueError("cover file is missing")

    image = cover.read_bytes()
    mime, width, height, depth = _read_image_info(image)
    fmt = output_format.upper()

    if fmt == "FLAC":
        from mutagen.flac import FLAC, Picture

        media = FLAC(str(audio))
        media.clear_pictures()
        picture = Picture()
        picture.type = 3
        picture.mime = mime
        picture.desc = "Cover (front)"
        picture.width = width
        picture.height = height
        picture.depth = depth
        picture.data = image
        media.add_picture(picture)
        media.save()

        verify = FLAC(str(audio))
        if not verify.pictures:
            raise ValueError("FLAC cover verification failed: missing PICTURE block")
        first = verify.pictures[0]
        if first.type != 3 or first.mime != mime or first.data != image:
            raise ValueError("FLAC cover verification failed: PICTURE mismatch")
        return f"FLAC PICTURE {mime} {width}x{height} {len(image)}B"

    if fmt == "MP3":
        from mutagen.id3 import APIC, ID3, ID3NoHeaderError

        try:
            tags = ID3(str(audio))
        except ID3NoHeaderError:
            tags = ID3()
        tags.delall("APIC")
        tags.add(
            APIC(
                encoding=3,
                mime=mime,
                type=3,
                desc="Cover (front)",
                data=image,
            )
        )
        tags.save(str(audio), v2_version=3)

        verify = ID3(str(audio))
        pictures = verify.getall("APIC")
        if not pictures:
            raise ValueError("MP3 cover verification failed: missing APIC")
        first = pictures[0]
        if first.type != 3 or first.mime != mime or first.data != image:
            raise ValueError("MP3 cover verification failed: APIC mismatch")
        return f"MP3 APIC {mime} {width}x{height} {len(image)}B"

    raise ValueError(f"unsupported output format: {output_format}")
