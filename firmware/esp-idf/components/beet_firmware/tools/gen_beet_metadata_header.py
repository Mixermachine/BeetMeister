#!/usr/bin/env python3
import argparse
import binascii
import struct
from pathlib import Path

MAGIC = 0x544D5442
FORMAT_VERSION = 1

TLV_PRODUCT_ID = 0x0001
TLV_HARDWARE_REV = 0x0002
TLV_FIRMWARE_VERSION = 0x0003
TLV_BUILD_LABEL = 0x0004
TLV_MAINTENANCE_PROTOCOL_VERSION = 0x0005
TLV_RUNTIME_PROTOCOL_VERSION = 0x0006
TLV_IMAGE_KIND = 0x0007
TLV_COMPATIBLE_HARDWARE_REV = 0x0008


def tlv_string(field_type: int, value: str) -> bytes:
    raw = value.encode("utf-8")
    return struct.pack("<HH", field_type, len(raw)) + raw


def tlv_u32(field_type: int, value: int) -> bytes:
    return struct.pack("<HHI", field_type, 4, value)


def format_bytes(block: bytes) -> str:
    lines = []
    for index in range(0, len(block), 12):
        chunk = block[index:index + 12]
        lines.append("    " + ", ".join(f"0x{value:02X}" for value in chunk))
    return ",\n".join(lines)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True)
    parser.add_argument("--project-ver", required=True)
    parser.add_argument("--product-id", required=True)
    parser.add_argument("--hardware-rev", required=True)
    parser.add_argument("--maintenance-protocol-version", type=int, required=True)
    parser.add_argument("--runtime-protocol-version", type=int, required=True)
    parser.add_argument("--image-kind", required=True)
    parser.add_argument("--compatible-hardware-rev", action="append", required=True)
    args = parser.parse_args()

    firmware_version = args.project_ver
    build_label = args.project_ver

    tlvs = []
    tlvs.append(tlv_string(TLV_PRODUCT_ID, args.product_id))
    tlvs.append(tlv_string(TLV_HARDWARE_REV, args.hardware_rev))
    tlvs.append(tlv_string(TLV_FIRMWARE_VERSION, firmware_version))
    tlvs.append(tlv_string(TLV_BUILD_LABEL, build_label))
    tlvs.append(tlv_u32(TLV_MAINTENANCE_PROTOCOL_VERSION, args.maintenance_protocol_version))
    tlvs.append(tlv_u32(TLV_RUNTIME_PROTOCOL_VERSION, args.runtime_protocol_version))
    tlvs.append(tlv_string(TLV_IMAGE_KIND, args.image_kind))
    for compatible_rev in args.compatible_hardware_rev:
        tlvs.append(tlv_string(TLV_COMPATIBLE_HARDWARE_REV, compatible_rev))

    payload = b"".join(tlvs)
    total_length = 12 + len(payload)
    header_prefix = struct.pack("<IHH", MAGIC, FORMAT_VERSION, total_length)
    header_crc32 = binascii.crc32(header_prefix) & 0xFFFFFFFF
    block = header_prefix + struct.pack("<I", header_crc32) + payload

    output = f"""#ifndef BEET_GENERATED_METADATA_H
#define BEET_GENERATED_METADATA_H

#include <stdint.h>

static const uint8_t g_beet_generated_metadata_block[] = {{
{format_bytes(block)}
}};

#endif
"""

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(output, encoding="ascii")


if __name__ == "__main__":
    main()
