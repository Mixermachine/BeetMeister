#!/usr/bin/env python3
import argparse
import json
import struct
from pathlib import Path
from zlib import crc32

MAGIC = 0x544D5442
FORMAT_VERSION = 1
HEADER_SIZE = 12

TLV_PRODUCT_ID = 0x0001
TLV_HARDWARE_REV = 0x0002
TLV_FIRMWARE_VERSION = 0x0003
TLV_BUILD_LABEL = 0x0004
TLV_MAINTENANCE_PROTOCOL_VERSION = 0x0005
TLV_RUNTIME_PROTOCOL_VERSION = 0x0006
TLV_IMAGE_KIND = 0x0007
TLV_COMPATIBLE_HARDWARE_REV = 0x0008


def read_u16_le(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def read_u32_le(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def read_utf8(data: bytes, offset: int, length: int) -> str:
    return data[offset:offset + length].decode("utf-8")


def parse_metadata(image_bytes: bytes) -> dict:
    index = 0
    while index + HEADER_SIZE <= len(image_bytes):
        if read_u32_le(image_bytes, index) != MAGIC:
            index += 1
            continue
        format_version = read_u16_le(image_bytes, index + 4)
        total_length = read_u16_le(image_bytes, index + 6)
        expected_crc = read_u32_le(image_bytes, index + 8)
        if format_version != FORMAT_VERSION or total_length < HEADER_SIZE or index + total_length > len(image_bytes):
            index += 1
            continue
        actual_crc = crc32(image_bytes[index:index + 8]) & 0xFFFFFFFF
        if actual_crc != expected_crc:
            index += 1
            continue
        return parse_metadata_block(image_bytes, index, total_length)
    raise SystemExit("Missing BeetMeister maintenance metadata block.")


def parse_metadata_block(image_bytes: bytes, start: int, total_length: int) -> dict:
    offset = start + HEADER_SIZE
    metadata = {
        "product_id": None,
        "hardware_rev": None,
        "firmware_version": None,
        "build_label": None,
        "maintenance_protocol_version": None,
        "runtime_protocol_version": None,
        "image_kind": None,
        "compatible_hardware_revs": [],
    }
    end = start + total_length
    while offset < end:
        if offset + 4 > end:
            raise SystemExit("Malformed BeetMeister metadata entry.")
        field_type = read_u16_le(image_bytes, offset)
        length = read_u16_le(image_bytes, offset + 2)
        offset += 4
        if offset + length > end:
            raise SystemExit("Malformed BeetMeister metadata value.")
        if field_type == TLV_PRODUCT_ID:
            metadata["product_id"] = read_utf8(image_bytes, offset, length)
        elif field_type == TLV_HARDWARE_REV:
            metadata["hardware_rev"] = read_utf8(image_bytes, offset, length)
        elif field_type == TLV_FIRMWARE_VERSION:
            metadata["firmware_version"] = read_utf8(image_bytes, offset, length)
        elif field_type == TLV_BUILD_LABEL:
            metadata["build_label"] = read_utf8(image_bytes, offset, length)
        elif field_type == TLV_MAINTENANCE_PROTOCOL_VERSION:
            metadata["maintenance_protocol_version"] = read_u32_le(image_bytes, offset)
        elif field_type == TLV_RUNTIME_PROTOCOL_VERSION:
            metadata["runtime_protocol_version"] = read_u32_le(image_bytes, offset)
        elif field_type == TLV_IMAGE_KIND:
            metadata["image_kind"] = read_utf8(image_bytes, offset, length)
        elif field_type == TLV_COMPATIBLE_HARDWARE_REV:
            metadata["compatible_hardware_revs"].append(read_utf8(image_bytes, offset, length))
        offset += length
    for key in (
        "product_id",
        "hardware_rev",
        "firmware_version",
        "build_label",
        "maintenance_protocol_version",
        "runtime_protocol_version",
        "image_kind",
    ):
        if metadata[key] in (None, ""):
            raise SystemExit(f"Missing required metadata field: {key}")
    if not metadata["compatible_hardware_revs"]:
        raise SystemExit("Missing required metadata field: compatible_hardware_revs")
    return metadata


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("image")
    args = parser.parse_args()
    image_path = Path(args.image)
    image_bytes = image_path.read_bytes()
    metadata = parse_metadata(image_bytes)
    metadata["image_size"] = len(image_bytes)
    print(json.dumps(metadata, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
