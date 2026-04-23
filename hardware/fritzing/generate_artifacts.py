from __future__ import annotations

import html
import os
import shutil
import subprocess
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parent
SOURCE_DIR = ROOT / "source"
EDGE_CANDIDATES = [
    Path(r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"),
    Path(r"C:\Program Files\Microsoft\Edge\Application\msedge.exe"),
]


@dataclass(frozen=True)
class PairPin:
    pair: int
    relay_gpio: str
    moisture_gpio: str


PAIRS = [
    PairPin(1, "GPIO14", "GPIO10"),
    PairPin(2, "GPIO21", "GPIO9"),
    PairPin(3, "GPIO47", "GPIO8"),
    PairPin(4, "GPIO38", "GPIO7"),
    PairPin(5, "GPIO39", "GPIO6"),
    PairPin(6, "GPIO40", "GPIO5"),
    PairPin(7, "GPIO41", "GPIO4"),
    PairPin(8, "GPIO42", "GPIO1"),
]


LEFT_HEADER = [
    "3V3",
    "3V3",
    "RST",
    "GPIO4",
    "GPIO5",
    "GPIO6",
    "GPIO7",
    "GPIO15",
    "GPIO16",
    "GPIO17",
    "GPIO18",
    "GPIO8",
    "GPIO3",
    "GPIO46",
    "GPIO9",
    "GPIO10",
    "GPIO11",
    "GPIO12",
    "GPIO13",
    "GPIO14",
    "5VIN",
    "GND",
]


RIGHT_HEADER = [
    "GND",
    "GPIO43",
    "GPIO44",
    "GPIO1",
    "GPIO2",
    "GPIO42",
    "GPIO41",
    "GPIO40",
    "GPIO39",
    "GPIO38",
    "GPIO37",
    "GPIO36",
    "GPIO35",
    "GPIO0",
    "GPIO45",
    "GPIO48",
    "GPIO47",
    "GPIO21",
    "GPIO20",
    "GPIO19",
    "GND",
    "GND",
]


PIN_ROLES = {
    "GPIO1": ("Pair 8 moisture", "#dceafe", "#1d4ed8"),
    "GPIO2": ("Battery sense", "#fee2e2", "#b91c1c"),
    "GPIO4": ("Pair 7 moisture", "#dceafe", "#1d4ed8"),
    "GPIO5": ("Pair 6 moisture", "#dceafe", "#1d4ed8"),
    "GPIO6": ("Pair 5 moisture", "#dceafe", "#1d4ed8"),
    "GPIO7": ("Pair 4 moisture", "#dceafe", "#1d4ed8"),
    "GPIO8": ("Pair 3 moisture", "#dceafe", "#1d4ed8"),
    "GPIO9": ("Pair 2 moisture", "#dceafe", "#1d4ed8"),
    "GPIO10": ("Pair 1 moisture", "#dceafe", "#1d4ed8"),
    "GPIO11": ("OLED SDA", "#dcfce7", "#166534"),
    "GPIO12": ("OLED SCL", "#dcfce7", "#166534"),
    "GPIO13": ("Future single button", "#fef3c7", "#92400e"),
    "GPIO14": ("Pair 1 relay", "#fce7f3", "#be185d"),
    "GPIO15": ("Free expansion", "#f3f4f6", "#374151"),
    "GPIO16": ("Free expansion", "#f3f4f6", "#374151"),
    "GPIO17": ("Free expansion", "#f3f4f6", "#374151"),
    "GPIO18": ("Free expansion", "#f3f4f6", "#374151"),
    "GPIO19": ("USB D-", "#e5e7eb", "#111827"),
    "GPIO20": ("USB D+", "#e5e7eb", "#111827"),
    "GPIO21": ("Pair 2 relay", "#fce7f3", "#be185d"),
    "GPIO35": ("PSRAM unavailable", "#e5e7eb", "#111827"),
    "GPIO36": ("PSRAM unavailable", "#e5e7eb", "#111827"),
    "GPIO37": ("PSRAM unavailable", "#e5e7eb", "#111827"),
    "GPIO38": ("Pair 4 relay", "#fce7f3", "#be185d"),
    "GPIO39": ("Pair 5 relay", "#fce7f3", "#be185d"),
    "GPIO40": ("Pair 6 relay", "#fce7f3", "#be185d"),
    "GPIO41": ("Pair 7 relay", "#fce7f3", "#be185d"),
    "GPIO42": ("Pair 8 relay", "#fce7f3", "#be185d"),
    "GPIO43": ("UART TX", "#e5e7eb", "#111827"),
    "GPIO44": ("UART RX", "#e5e7eb", "#111827"),
    "GPIO45": ("Strapping pin", "#e5e7eb", "#111827"),
    "GPIO46": ("Strapping pin", "#e5e7eb", "#111827"),
    "GPIO47": ("Pair 3 relay", "#fce7f3", "#be185d"),
    "GPIO48": ("Onboard RGB LED", "#e5e7eb", "#111827"),
    "GPIO0": ("Strapping pin", "#e5e7eb", "#111827"),
    "GPIO3": ("Strapping pin", "#e5e7eb", "#111827"),
}


def esc(value: str) -> str:
    return html.escape(value, quote=True)


def svg_text(
    x: float,
    y: float,
    text: str,
    size: int = 16,
    weight: str = "400",
    fill: str = "#111827",
    anchor: str = "start",
    family: str = "Segoe UI, Arial, sans-serif",
) -> str:
    return (
        f'<text x="{x}" y="{y}" font-family="{esc(family)}" font-size="{size}" '
        f'font-weight="{weight}" fill="{fill}" text-anchor="{anchor}">{esc(text)}</text>'
    )


def svg_rect(
    x: float,
    y: float,
    width: float,
    height: float,
    fill: str,
    stroke: str = "#111827",
    stroke_width: int = 1,
    rx: int = 12,
) -> str:
    return (
        f'<rect x="{x}" y="{y}" width="{width}" height="{height}" rx="{rx}" '
        f'fill="{fill}" stroke="{stroke}" stroke-width="{stroke_width}"/>'
    )


def svg_line(
    x1: float,
    y1: float,
    x2: float,
    y2: float,
    stroke: str = "#374151",
    stroke_width: int = 3,
    dash: str | None = None,
) -> str:
    dash_attr = f' stroke-dasharray="{dash}"' if dash else ""
    return (
        f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{stroke}" '
        f'stroke-width="{stroke_width}" stroke-linecap="round"{dash_attr}/>'
    )


def svg_path(d: str, stroke: str = "#374151", stroke_width: int = 3, fill: str = "none") -> str:
    return (
        f'<path d="{esc(d)}" stroke="{stroke}" stroke-width="{stroke_width}" '
        f'fill="{fill}" stroke-linecap="round" stroke-linejoin="round"/>'
    )


def svg_circle(cx: float, cy: float, r: float, fill: str, stroke: str = "#111827", stroke_width: int = 1) -> str:
    return (
        f'<circle cx="{cx}" cy="{cy}" r="{r}" fill="{fill}" stroke="{stroke}" '
        f'stroke-width="{stroke_width}"/>'
    )


def svg_header(width: int, height: int, inner: str) -> str:
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" viewBox="0 0 {width} {height}">
  <defs>
    <style>
      .small {{ font: 14px 'Segoe UI', Arial, sans-serif; fill: #1f2937; }}
      .mono {{ font: 13px 'Consolas', 'Courier New', monospace; fill: #111827; }}
      .note {{ font: 13px 'Segoe UI', Arial, sans-serif; fill: #374151; }}
      .title {{ font: 700 32px 'Segoe UI', Arial, sans-serif; fill: #111827; }}
      .subtitle {{ font: 600 18px 'Segoe UI', Arial, sans-serif; fill: #1f2937; }}
    </style>
  </defs>
  <rect width="{width}" height="{height}" fill="#f8fafc"/>
{inner}
</svg>
"""


def wrap_for_view(svg_path_file: Path, layer_id: str) -> str:
    content = svg_path_file.read_text(encoding="utf-8")
    start = content.index(">", content.index("<svg")) + 1
    end = content.rindex("</svg>")
    inner = content[start:end]
    return f"""<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="1600" height="1000" viewBox="0 0 1600 1000">
  <g id="{layer_id}">
{inner}
  </g>
  <g id="icon"/>
  <g id="breadboard"/>
  <g id="schematic"/>
  <g id="silkscreen"/>
  <g id="copper0"/>
  <g id="copper1"/>
</svg>
"""


def build_controller_pinout() -> str:
    parts = [
        svg_text(60, 70, "BeetMeister Controller Pinout", 34, "700"),
        svg_text(60, 104, "44-pin ESP32-S3 DevKitC-1 style board with canonical BeetMeister assignments", 18, "600", "#334155"),
        svg_rect(430, 150, 740, 620, "#0f172a", "#020617", 2, 28),
        svg_text(800, 220, "ESP32-S3", 30, "700", "#f8fafc", "middle"),
        svg_text(800, 255, "WROOM-1-N16R8", 20, "600", "#cbd5e1", "middle"),
        svg_text(800, 290, "Pin plan locked to hardware/wiring/pin-assignment.md", 15, "400", "#cbd5e1", "middle"),
    ]

    top_y = 175
    row_h = 24
    left_x = 430
    right_x = 1170

    for idx, pin in enumerate(LEFT_HEADER):
        y = top_y + idx * 27
        parts.append(svg_circle(left_x, y, 5, "#e2e8f0", "#cbd5e1"))
        role = PIN_ROLES.get(pin)
        if role:
            fill, stroke = role[1], role[2]
            label = role[0]
        else:
            fill, stroke = "#ffffff", "#94a3b8"
            label = ""
        box_x = 80
        box_w = 300
        parts.append(svg_rect(box_x, y - 15, box_w, row_h, fill, stroke, 1, 10))
        parts.append(svg_text(box_x + 10, y + 2, pin, 14, "700"))
        if label:
            parts.append(svg_text(box_x + 112, y + 2, label, 13, "400", stroke))
        parts.append(svg_line(box_x + box_w, y - 3, left_x - 8, y - 3, "#94a3b8", 1))

    for idx, pin in enumerate(RIGHT_HEADER):
        y = top_y + idx * 27
        parts.append(svg_circle(right_x, y, 5, "#e2e8f0", "#cbd5e1"))
        role = PIN_ROLES.get(pin)
        if role:
            fill, stroke = role[1], role[2]
            label = role[0]
        else:
            fill, stroke = "#ffffff", "#94a3b8"
            label = ""
        box_x = 1220
        box_w = 300
        parts.append(svg_rect(box_x, y - 15, box_w, row_h, fill, stroke, 1, 10))
        parts.append(svg_text(box_x + 10, y + 2, pin, 14, "700"))
        if label:
            parts.append(svg_text(box_x + 112, y + 2, label, 13, "400", stroke))
        parts.append(svg_line(right_x + 8, y - 3, box_x, y - 3, "#94a3b8", 1))

    legend = [
        ("Moisture input", "#dceafe", "#1d4ed8"),
        ("Relay output", "#fce7f3", "#be185d"),
        ("Battery sense", "#fee2e2", "#b91c1c"),
        ("OLED / future UI", "#dcfce7", "#166534"),
        ("Reserved / risky", "#e5e7eb", "#111827"),
        ("Free expansion", "#f3f4f6", "#374151"),
    ]
    parts.append(svg_text(80, 810, "Legend", 20, "700"))
    for idx, (title, fill, stroke) in enumerate(legend):
        col = idx % 3
        row = idx // 3
        lx = 80 + col * 250
        ly = 830 + row * 34
        parts.append(svg_rect(lx, ly, 220, 26, fill, stroke, 1, 8))
        parts.append(svg_text(lx + 110, ly + 17, title, 13, "600", stroke, "middle"))

    parts.extend(
        [
            svg_rect(1080, 800, 440, 80, "#ffffff", "#cbd5e1", 1, 16),
            svg_text(1100, 826, "Board-specific notes", 18, "700"),
            svg_text(1100, 848, "GPIO48 stays reserved for the onboard RGB LED.", 13),
            svg_text(1100, 866, "GPIO11/GPIO12 are active; GPIO13 remains deferred.", 13),
        ]
    )

    return svg_header(1600, 1000, "\n".join(parts))


def build_power_distribution() -> str:
    parts = [
        svg_text(60, 70, "BeetMeister Power Distribution", 34, "700"),
        svg_text(60, 104, "Baseline: LiFePO4 direct battery rail for controller, relay board, and pumps", 18, "600", "#334155"),
        svg_rect(100, 180, 250, 160, "#fef3c7", "#d97706", 2, 24),
        svg_text(225, 225, "Charge Input", 24, "700", "#92400e", "middle"),
        svg_text(225, 258, "USB / bench source", 16, "600", "#92400e", "middle"),
        svg_text(225, 285, "feeds generic charger module", 14, "400", "#78350f", "middle"),
        svg_rect(430, 150, 290, 220, "#ffffff", "#0f766e", 2, 24),
        svg_text(575, 195, "Generic LiFePO4 Charger", 24, "700", "#0f766e", "middle"),
        svg_text(575, 228, "VIN+, VIN-, BATT+, BATT-", 16, "600", "#115e59", "middle"),
        svg_text(575, 258, "placeholder block until module SKU is fixed", 13, "400", "#134e4a", "middle"),
        svg_rect(810, 170, 250, 180, "#dbeafe", "#1d4ed8", 2, 24),
        svg_text(935, 218, "LiFePO4 Battery", 26, "700", "#1d4ed8", "middle"),
        svg_text(935, 252, "1S / 15 Ah planned", 18, "600", "#1d4ed8", "middle"),
        svg_text(935, 282, "3.20 V deep-low threshold", 14, "400", "#1e3a8a", "middle"),
        svg_rect(1180, 120, 300, 180, "#dcfce7", "#166534", 2, 24),
        svg_text(1330, 164, "ESP32-S3 Controller", 26, "700", "#166534", "middle"),
        svg_text(1330, 198, "direct battery on 3.3V pin", 16, "600", "#166534", "middle"),
        svg_text(1330, 228, "Battery sense on GPIO2", 14, "400", "#166534", "middle"),
        svg_text(1330, 252, "OLED on GPIO11 / GPIO12", 14, "400", "#166534", "middle"),
        svg_rect(1180, 360, 300, 180, "#fce7f3", "#be185d", 2, 24),
        svg_text(1330, 404, "8-Channel Relay Board", 26, "700", "#be185d", "middle"),
        svg_text(1330, 438, "logic and switched pump rail", 16, "600", "#be185d", "middle"),
        svg_text(1330, 468, "active-high IN1..IN8 with pull-downs", 14, "400", "#9d174d", "middle"),
        svg_rect(1180, 600, 300, 170, "#fae8ff", "#7e22ce", 2, 24),
        svg_text(1330, 644, "8 Pumps", 26, "700", "#7e22ce", "middle"),
        svg_text(1330, 678, "switched from direct battery rail", 16, "600", "#7e22ce", "middle"),
        svg_text(1330, 708, "optional 5V boost remains a later variant", 13, "400", "#6b21a8", "middle"),
        svg_rect(720, 430, 300, 200, "#ffffff", "#b91c1c", 2, 24),
        svg_text(870, 468, "Battery Sense Divider", 24, "700", "#b91c1c", "middle"),
        svg_text(870, 502, "Rtop 149.2k  ->  GPIO2  ->  Rbottom 148.5k", 15, "600", "#7f1d1d", "middle"),
        svg_text(870, 530, "current bench build: 300k || 300k on each side", 13, "400", "#7f1d1d", "middle"),
        svg_text(870, 556, "0.1 uF local capacitor recommended at the ADC node", 13, "400", "#7f1d1d", "middle"),
    ]

    battery_rail_y = 90
    parts.extend(
        [
            svg_line(1060, 260, 1180, 210, "#1d4ed8", 5),
            svg_line(720, 260, 810, 260, "#0f766e", 5),
            svg_line(350, 260, 430, 260, "#d97706", 5),
            svg_line(935, 170, 935, battery_rail_y, "#dc2626", 6),
            svg_line(935, battery_rail_y, 1330, battery_rail_y, "#dc2626", 6),
            svg_line(1330, battery_rail_y, 1330, 120, "#dc2626", 6),
            svg_line(1330, battery_rail_y, 1330, 360, "#dc2626", 6),
            svg_line(1330, battery_rail_y, 1330, 600, "#dc2626", 6),
            svg_text(1000, 78, "Direct battery rail", 16, "700", "#b91c1c"),
            svg_line(935, 350, 870, 430, "#b91c1c", 4),
            svg_line(1020, 520, 1180, 210, "#b91c1c", 3, "8 8"),
        ]
    )

    ground_y = 880
    parts.extend(
        [
            svg_line(160, ground_y, 1490, ground_y, "#111827", 6),
            svg_text(1000, 808, "Common ground", 16, "700", "#111827"),
        ]
    )
    for x, y in [(225, 340), (575, 370), (935, 350), (1330, 300), (1330, 540), (1330, 770), (870, 630)]:
        parts.append(svg_line(x, y, x, ground_y, "#111827", 3))

    parts.extend(
        [
            svg_rect(60, 730, 620, 108, "#ffffff", "#cbd5e1", 1, 16),
            svg_text(82, 758, "Bench annotation", 18, "700"),
            svg_text(82, 784, "Current bring-up may still use USB-C power, but the final baseline here is battery-powered.", 13),
            svg_text(82, 806, "A boosted 5V pump rail is intentionally omitted from the v1 baseline and only noted as a later variant.", 13),
        ]
    )

    return svg_header(1600, 1000, "\n".join(parts))


def build_system_wiring() -> str:
    parts = [
        svg_text(60, 70, "BeetMeister System Wiring", 34, "700"),
        svg_text(60, 104, "Final-baseline wiring view with current bench notes and canonical pair numbering", 18, "600", "#334155"),
        svg_rect(650, 220, 320, 400, "#dcfce7", "#166534", 2, 28),
        svg_text(810, 235, "ESP32-S3 Controller", 28, "700", "#166534", "middle"),
        svg_text(810, 266, "44-pin board / 16 MB flash / 8 MB PSRAM", 16, "600", "#166534", "middle"),
        svg_text(810, 294, "Battery GPIO2 / OLED GPIO11-12 / future button GPIO13", 13, "400", "#166534", "middle"),
        svg_rect(90, 180, 280, 170, "#fef3c7", "#d97706", 2, 24),
        svg_text(230, 220, "Charge Input", 22, "700", "#92400e", "middle"),
        svg_text(230, 250, "USB / bench source", 15, "600", "#92400e", "middle"),
        svg_rect(90, 380, 280, 180, "#ffffff", "#0f766e", 2, 24),
        svg_text(230, 420, "Generic Charger", 24, "700", "#0f766e", "middle"),
        svg_text(230, 452, "VIN+, VIN-, BATT+, BATT-", 15, "600", "#0f766e", "middle"),
        svg_rect(90, 610, 280, 160, "#dbeafe", "#1d4ed8", 2, 24),
        svg_text(230, 650, "LiFePO4 Battery", 24, "700", "#1d4ed8", "middle"),
        svg_text(230, 682, "1S / 15 Ah planned", 16, "600", "#1d4ed8", "middle"),
        svg_text(230, 708, "direct battery baseline", 14, "400", "#1e3a8a", "middle"),
        svg_rect(1150, 140, 300, 120, "#e0f2fe", "#0369a1", 2, 22),
        svg_text(1300, 180, "SSD1306 OLED", 24, "700", "#0369a1", "middle"),
        svg_text(1300, 212, "SDA GPIO11 / SCL GPIO12", 15, "600", "#0369a1", "middle"),
        svg_rect(1090, 290, 390, 270, "#fce7f3", "#be185d", 2, 24),
        svg_text(1285, 330, "8-Channel Relay Board", 26, "700", "#be185d", "middle"),
        svg_text(1285, 360, "IN1..IN8 active-high / external pull-downs", 15, "600", "#be185d", "middle"),
        svg_text(1285, 388, "Relay 1 GPIO14  Relay 2 GPIO21  Relay 3 GPIO47  Relay 4 GPIO38", 13, "400", "#9d174d", "middle"),
        svg_text(1285, 410, "Relay 5 GPIO39  Relay 6 GPIO40  Relay 7 GPIO41  Relay 8 GPIO42", 13, "400", "#9d174d", "middle"),
        svg_rect(1080, 600, 400, 220, "#fae8ff", "#7e22ce", 2, 24),
        svg_text(1280, 640, "8 Pumps on Direct Battery Rail", 26, "700", "#7e22ce", "middle"),
        svg_text(1280, 672, "each pump switched by its paired relay contact", 15, "600", "#7e22ce", "middle"),
        svg_text(1280, 700, "boosted rail remains a documented later variant", 13, "400", "#6b21a8", "middle"),
        svg_rect(400, 660, 620, 170, "#ffffff", "#2563eb", 2, 24),
        svg_text(710, 700, "Moisture Sensors", 26, "700", "#1d4ed8", "middle"),
        svg_text(710, 730, "3-wire capacitive sensors with analog output", 15, "600", "#1d4ed8", "middle"),
        svg_text(710, 756, "Pair 1 GPIO10  Pair 2 GPIO9  Pair 3 GPIO8  Pair 4 GPIO7", 13, "400", "#1e3a8a", "middle"),
        svg_text(710, 778, "Pair 5 GPIO6  Pair 6 GPIO5  Pair 7 GPIO4  Pair 8 GPIO1", 13, "400", "#1e3a8a", "middle"),
        svg_rect(430, 130, 330, 70, "#ffffff", "#b91c1c", 2, 18),
        svg_text(595, 160, "Battery divider -> GPIO2", 15, "700", "#7f1d1d", "middle"),
        svg_text(595, 182, "current bench build approx 2:1", 13, "400", "#7f1d1d", "middle"),
        svg_rect(820, 130, 270, 70, "#ffffff", "#92400e", 2, 18),
        svg_text(955, 160, "GPIO13 reserved", 15, "700", "#92400e", "middle"),
        svg_text(955, 182, "future single-button local UI / bond-admit", 12, "400", "#92400e", "middle"),
    ]

    # Main power and data links.
    parts.extend(
        [
            svg_line(370, 455, 650, 455, "#0f766e", 4),
            svg_line(370, 690, 650, 290, "#1d4ed8", 4),
            svg_line(370, 690, 1090, 690, "#dc2626", 5),
            svg_text(720, 678, "direct battery rail", 14, "700", "#b91c1c"),
            svg_line(970, 360, 1090, 360, "#be185d", 4),
            svg_line(970, 450, 1090, 450, "#be185d", 4),
            svg_line(970, 540, 1090, 540, "#be185d", 4),
            svg_line(970, 260, 1150, 200, "#0369a1", 4),
            svg_line(800, 200, 800, 220, "#b91c1c", 4),
            svg_line(1090, 710, 1020, 710, "#7e22ce", 3),
            svg_line(1080, 710, 970, 540, "#7e22ce", 3),
        ]
    )

    # Sensor-to-controller and relay-to-controller lines.
    moisture_y = 760
    sensor_x = 430
    controller_left = 650
    for idx, pair in enumerate(PAIRS):
        y = moisture_y - idx * 18
        parts.append(svg_text(sensor_x, y, f"Pair {pair.pair} -> {pair.moisture_gpio}", 13, "600", "#1d4ed8"))
        parts.append(svg_line(sensor_x + 150, y - 5, controller_left, 560 - idx * 26, "#60a5fa", 2))

    relay_y = 435
    relay_x = 1135
    for idx, pair in enumerate(PAIRS):
        y = relay_y + idx * 20
        parts.append(svg_text(relay_x, y, f"IN{pair.pair} <- {pair.relay_gpio}", 13, "600", "#be185d"))
        parts.append(svg_line(970, 350 + idx * 22, 1120, y - 5, "#f472b6", 2))

    # Pair-to-pump mapping.
    pump_label_y = 722
    for idx, pair in enumerate(PAIRS):
        col = 0 if idx < 4 else 1
        row = idx % 4
        x = 1120 + col * 180
        y = pump_label_y + row * 26
        parts.append(svg_text(x, y, f"Pair {pair.pair} pump", 13, "600", "#7e22ce"))

    # Ground rail.
    parts.extend(
        [
            svg_line(120, 875, 1490, 875, "#111827", 7),
            svg_text(820, 862, "Common ground shared by controller, charger, sensors, relay board, and pumps", 14, "700", "#111827", "middle"),
        ]
    )
    for x, y in [(230, 560), (230, 770), (810, 620), (1285, 560), (1280, 820)]:
        parts.append(svg_line(x, y, x, 875, "#111827", 3))

    parts.extend(
        [
            svg_rect(1180, 20, 330, 90, "#ffffff", "#cbd5e1", 1, 14),
            svg_text(1200, 48, "Bench note", 18, "700"),
            svg_text(1200, 72, "Current bring-up may still run from USB-C.", 13),
            svg_text(1200, 92, "This drawing shows the intended battery baseline.", 13),
        ]
    )

    return svg_header(1600, 1000, "\n".join(parts))


ARTIFACTS = {
    "controller-pinout": {
        "title": "BeetMeister Controller Pinout",
        "description": "Controller-centric pinout view matching the canonical BeetMeister ESP32-S3 wiring plan.",
        "builder": build_controller_pinout,
    },
    "power-distribution": {
        "title": "BeetMeister Power Distribution",
        "description": "Battery, charger, direct pump rail, relay supply, and battery divider baseline.",
        "builder": build_power_distribution,
    },
    "system-wiring": {
        "title": "BeetMeister System Wiring",
        "description": "Full final-baseline wiring view covering controller, sensors, relay board, OLED, battery, charger, and pumps.",
        "builder": build_system_wiring,
    },
}


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")


def find_edge() -> Path:
    for candidate in EDGE_CANDIDATES:
        if candidate.exists():
            return candidate
    raise FileNotFoundError("Microsoft Edge executable not found in the standard install paths.")


def render_export(edge_path: Path, svg_file: Path, output_file: Path, pdf: bool) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        html_path = Path(tmp) / "preview.html"
        html_path.write_text(
            f"""<!doctype html>
<html>
  <head>
    <meta charset="utf-8">
    <style>
      html, body {{
        margin: 0;
        background: #f8fafc;
      }}
      img {{
        width: 1600px;
        height: 1000px;
        display: block;
      }}
    </style>
  </head>
  <body>
    <img src="{svg_file.resolve().as_uri()}">
  </body>
</html>
""",
            encoding="utf-8",
        )

        command = [
            str(edge_path),
            "--headless",
            "--disable-gpu",
            "--hide-scrollbars",
            "--window-size=1600,1000",
        ]
        if pdf:
            command.append(f"--print-to-pdf={output_file}")
        else:
            command.append(f"--screenshot={output_file}")
        command.append(html_path.resolve().as_uri())

        subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)


def build_part_files(name: str, title: str, description: str, svg_file: Path) -> tuple[str, str, dict[str, str]]:
    module_id = f"beetmeister_{name.replace('-', '_')}_diagram"
    fzp_name = f"{name}.fzp"
    fz_name = f"{name}.fz"

    fzp = f"""<?xml version="1.0" encoding="UTF-8"?>
<module fritzingVersion="1.0.4" moduleId="{module_id}">
  <version>1</version>
  <author>OpenAI Codex</author>
  <title>{esc(title)}</title>
  <label>{esc(title)}</label>
  <date>2026-04-22</date>
  <description>{esc(description)}</description>
  <family>breadboard</family>
  <tags>
    <tag>BeetMeister</tag>
    <tag>documentation</tag>
    <tag>fritzing</tag>
  </tags>
  <properties/>
  <views>
    <iconView>
      <layers image="svg/icon/{name}_icon.svg"/>
    </iconView>
    <breadboardView>
      <layers image="svg/breadboard/{name}_breadboard.svg"/>
    </breadboardView>
    <schematicView>
      <layers image="svg/schematic/{name}_schematic.svg"/>
    </schematicView>
    <pcbView>
      <layers image="svg/pcb/{name}_pcb.svg">
        <layer layerId="silkscreen"/>
        <layer layerId="copper0"/>
        <layer layerId="copper1"/>
      </layers>
    </pcbView>
  </views>
  <connectors/>
</module>
"""

    fz = f"""<?xml version="1.0" encoding="UTF-8"?>
<module fritzingVersion="1.0.4" icon=".png">
  <title>{esc(title)}</title>
  <views>
    <view name="breadboardView" backgroundColor="#ffffff" gridSize="0.1in" showGrid="0" alignToGrid="0" viewFromBelow="0"/>
    <view name="schematicView" backgroundColor="#ffffff" gridSize="0.1in" showGrid="0" alignToGrid="0" viewFromBelow="0"/>
    <view name="pcbView" backgroundColor="#ffffff" gridSize="0.05in" showGrid="0" alignToGrid="0" viewFromBelow="0"/>
  </views>
  <instances>
    <instance moduleIdRef="{module_id}" modelIndex="1" path="{fzp_name}">
      <title>{esc(title)}</title>
      <views>
        <breadboardView layer="breadboard">
          <geometry z="1" x="0" y="0"/>
        </breadboardView>
        <schematicView layer="schematic">
          <geometry z="1" x="0" y="0"/>
        </schematicView>
        <pcbView layer="silkscreen">
          <geometry z="1" x="0" y="0"/>
        </pcbView>
      </views>
    </instance>
  </instances>
</module>
"""

    view_files = {
        f"svg/icon/{name}_icon.svg": wrap_for_view(svg_file, "icon"),
        f"svg/breadboard/{name}_breadboard.svg": wrap_for_view(svg_file, "breadboard"),
        f"svg/schematic/{name}_schematic.svg": wrap_for_view(svg_file, "schematic"),
        f"svg/pcb/{name}_pcb.svg": wrap_for_view(svg_file, "silkscreen"),
    }
    return fzp, fz, view_files


def build_fzz(name: str, title: str, description: str, svg_file: Path) -> None:
    fzp, fz, view_files = build_part_files(name, title, description, svg_file)
    target = ROOT / f"{name}.fzz"
    with zipfile.ZipFile(target, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr(f"{name}.fz", fz)
        archive.writestr(f"{name}.fzp", fzp)
        for arcname, content in view_files.items():
            archive.writestr(arcname, content)


def generate_all() -> None:
    SOURCE_DIR.mkdir(parents=True, exist_ok=True)
    edge_path = find_edge()

    for name, metadata in ARTIFACTS.items():
        svg_file = SOURCE_DIR / f"{name}.svg"
        write_text(svg_file, metadata["builder"]())
        build_fzz(name, metadata["title"], metadata["description"], svg_file)
        render_export(edge_path, svg_file, ROOT / f"{name}.png", pdf=False)
        render_export(edge_path, svg_file, ROOT / f"{name}.pdf", pdf=True)


if __name__ == "__main__":
    generate_all()
