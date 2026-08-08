from __future__ import annotations

import struct
from pathlib import Path


def _read_png_info(data: bytes) -> tuple[int, int, int]:
    if len(data) < 33 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("cover is not a canonical PNG")
    width, height = struct.unpack(">II", data[16:24])
    bit_depth = data[24]
    color_type = data[25]
    channels = {
        0: 1,  # grayscale
        2: 3,  # RGB
        3: 1,  # indexed
        4: 2,  # grayscale + alpha
        6: 4,  # RGBA
    }.get(color_type, 3)
    depth = max(1, bit_depth * channels)
    if width <= 0 or height <= 0:
        raise ValueError("cover PNG has invalid dimensions")
    return width, height, depth


def embed_cover(audio_path: str, cover_path: str, output_format: str) -> str:
    """Embed a front cover without sending an image stream through FFmpeg.

    Returns a short verification string. Raises on any write/verification error,
    allowing the Android caller to keep the audio and retry without artwork.
    """
    audio = Path(audio_path)
    cover = Path(cover_path)
    if not audio.is_file() or audio.stat().st_size <= 0:
        raise ValueError("audio file is missing")
    if not cover.is_file() or cover.stat().st_size <= 0:
        raise ValueError("cover file is missing")

    image = cover.read_bytes()
    width, height, depth = _read_png_info(image)
    fmt = output_format.upper()

    if fmt == "FLAC":
        from mutagen.flac import FLAC, Picture

        media = FLAC(str(audio))
        media.clear_pictures()
        picture = Picture()
        picture.type = 3  # front cover
        picture.mime = "image/png"
        picture.desc = "Cover (front)"
        picture.width = width
        picture.height = height
        picture.depth = depth
        picture.data = image
        media.add_picture(picture)
        media.save()

        verify = FLAC(str(audio))
        if not verify.pictures or verify.pictures[0].data != image:
            raise ValueError("FLAC cover verification failed")
        return f"FLAC PICTURE {width}x{height} {len(image)}B"

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
                mime="image/png",
                type=3,
                desc="Cover (front)",
                data=image,
            )
        )
        tags.save(str(audio), v2_version=3)

        verify = ID3(str(audio))
        pictures = verify.getall("APIC")
        if not pictures or pictures[0].data != image:
            raise ValueError("MP3 APIC cover verification failed")
        return f"MP3 APIC {width}x{height} {len(image)}B"

    raise ValueError(f"unsupported output format: {output_format}")
