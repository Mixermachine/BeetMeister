"""Parse the ESP-IDF partitions CSV.

The partitions CSV (`firmware/esp-idf/partitions/<board>.csv`) is the
source of truth for partition offsets/sizes. The harness reads it
at runtime so the partition layout is never hard-coded — if the
firmware partition table changes, this loader picks up the new
layout automatically.

Format (from `gen_esp32part.py`):

    # Name,     Type, SubType, Offset,   Size,      Flags
    nvs,        data, nvs,     0x9000,   0x6000,
    appcfg,     data, nvs,     0x12000,  0x10000,
    ...

The CSV is consumed by both the fresh-install `controller_reset.py`
(erases the three NVS partitions) and the firmware-update
`firmware.py` (optional `erase_region appcfg` before flashing
v0.3.0). If a partition is missing, the loader raises — the
caller decides whether to fail the run or to skip.
"""

from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Partition:
    name: str
    type: str
    subtype: str
    offset: int
    size: int

    @property
    def end(self) -> int:
        return self.offset + self.size


def _int(value: str) -> int:
    """Parse either 0xHEX or decimal from the CSV."""
    value = value.strip()
    if value.startswith("0x") or value.startswith("0X"):
        return int(value, 16)
    return int(value)


def load(csv_path: Path) -> dict[str, Partition]:
    """Return name -> Partition for every entry in the CSV.

    Duplicate names (rare; happens if the CSV is hand-edited) are
    rejected — the caller cannot tell which one to erase.
    """
    out: dict[str, Partition] = {}
    with csv_path.open(newline="") as fh:
        # Skip the comment line(s) (start with '#').
        rows = (row for row in csv.reader(fh) if row and not row[0].lstrip().startswith("#"))
        for row in rows:
            if len(row) < 5:
                # Trailing/empty line; skip.
                continue
            name, ptype, psubtype, offset_s, size_s = row[0], row[1], row[2], row[3], row[4]
            if name in out:
                raise ValueError(
                    f"{csv_path}: duplicate partition name '{name}' in CSV"
                )
            out[name] = Partition(
                name=name,
                type=ptype,
                subtype=psubtype,
                offset=_int(offset_s),
                size=_int(size_s),
            )
    return out


def require(partitions: dict[str, Partition], *names: str) -> tuple[Partition, ...]:
    """Return the named partitions or raise with a clear list of missing ones."""
    missing = [n for n in names if n not in partitions]
    if missing:
        available = ", ".join(sorted(partitions))
        raise KeyError(
            f"partitions CSV missing required entries {missing}; available: {available}"
        )
    return tuple(partitions[n] for n in names)
