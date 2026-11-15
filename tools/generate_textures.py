#!/usr/bin/env python3
"""
Generates high-detail 32x32 RGBA pixel-art textures for Project Realism.

Covers:
  - Primitive rocks, knapped stone parts, plant fiber, cordage
  - Custom tool designs (sharp and dull variants) for knife, axe, pickaxe
  - Wood processing items & drying blocks (Oak, Birch, Spruce, Dark Oak)
  - Notched tree inner wood & branch stub block textures

Usage:
    python3 tools/generate_textures.py
    python3 tools/generate_textures.py --check
    python3 tools/generate_textures.py --preview /tmp/preview.png
"""

from __future__ import annotations

import argparse
import math
import pathlib
import random
import struct
import zlib
from typing import Dict, List, Optional, Sequence, Tuple

RGBA = Tuple[int, int, int, int]
RGB = Tuple[int, int, int]

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEX_ROOT = ROOT / "src" / "main" / "resources" / "assets" / "project_realism" / "textures"
T: RGBA = (0, 0, 0, 0)


# ---------------------------------------------------------------------------
# PNG encoding / decoding
# ---------------------------------------------------------------------------

def encode_png_rgba(width: int, height: int, rows: List[List[RGBA]]) -> bytes:
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for r, g, b, a in row:
            raw.extend((r & 255, g & 255, b & 255, a & 255))

    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), level=9)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", idat) + chunk(b"IEND", b"")


def decode_png_rgba(data: bytes) -> Tuple[int, int, List[List[RGBA]]]:
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    pos = 8
    width = height = 0
    ct = 6
    idat = bytearray()
    while pos < len(data):
        length = struct.unpack(">I", data[pos : pos + 4])[0]
        tag = data[pos + 4 : pos + 8]
        payload = data[pos + 8 : pos + 8 + length]
        if tag == b"IHDR":
            width, height, _bd, ct = struct.unpack(">IIBB", payload[:10])
        elif tag == b"IDAT":
            idat.extend(payload)
        elif tag == b"IEND":
            break
        pos += 12 + length
    assert ct == 6
    raw = zlib.decompress(bytes(idat))
    stride = width * 4
    rows: List[List[RGBA]] = []
    idx = 0
    for _ in range(height):
        assert raw[idx] == 0
        idx += 1
        rb = raw[idx : idx + stride]
        idx += stride
        rows.append([(rb[x * 4], rb[x * 4 + 1], rb[x * 4 + 2], rb[x * 4 + 3]) for x in range(width)])
    return width, height, rows


# ---------------------------------------------------------------------------
# Color & 32x32 Canvas Helpers
# ---------------------------------------------------------------------------

def clamp(v: float) -> int:
    return max(0, min(255, int(round(v))))


def shade(col: RGB, factor: float, shift: Tuple[int, int, int] = (0, 0, 0)) -> RGBA:
    return (
        clamp(col[0] * factor + shift[0]),
        clamp(col[1] * factor + shift[1]),
        clamp(col[2] * factor + shift[2]),
        255,
    )


def mix(a: RGB, b: RGB, t: float) -> RGB:
    return (clamp(a[0] + (b[0] - a[0]) * t), clamp(a[1] + (b[1] - a[1]) * t), clamp(a[2] + (b[2] - a[2]) * t))


class Canvas:
    def __init__(self, w: int = 32, h: int = 32, fill: RGBA = T) -> None:
        self.w = w
        self.h = h
        self.px: List[List[RGBA]] = [[fill for _ in range(w)] for _ in range(h)]

    def set(self, x: int, y: int, col: RGBA) -> None:
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = col

    def get(self, x: int, y: int) -> RGBA:
        if 0 <= x < self.w and 0 <= y < self.h:
            return self.px[y][x]
        return T

    def line(self, x0: int, y0: int, x1: int, y1: int, col: RGBA, thickness: int = 1) -> None:
        steps = max(abs(x1 - x0), abs(y1 - y0), 1) * 2
        for i in range(steps + 1):
            t = i / steps
            cx = round(x0 + (x1 - x0) * t)
            cy = round(y0 + (y1 - y0) * t)
            if thickness <= 1:
                self.set(cx, cy, col)
            else:
                r = thickness // 2
                for dy in range(-r, r + 1):
                    for dx in range(-r, r + 1):
                        if dx * dx + dy * dy <= r * r + 1:
                            self.set(cx + dx, cy + dy, col)

    def fill_poly(self, pts: Sequence[Tuple[float, float]], fn) -> None:
        ys = [p[1] for p in pts]
        min_y = max(0, int(math.floor(min(ys))))
        max_y = min(self.h - 1, int(math.ceil(max(ys))))
        n = len(pts)
        for y in range(min_y, max_y + 1):
            py = y + 0.5
            xs: List[float] = []
            for i in range(n):
                x1, y1 = pts[i]
                x2, y2 = pts[(i + 1) % n]
                if (y1 <= py < y2) or (y2 <= py < y1):
                    xs.append(x1 + (py - y1) * (x2 - x1) / (y2 - y1))
            xs.sort()
            for i in range(0, len(xs) - 1, 2):
                x_start = max(0, int(math.ceil(xs[i] - 0.5)))
                x_end = min(self.w - 1, int(math.floor(xs[i + 1] - 0.5)))
                for x in range(x_start, x_end + 1):
                    self.set(x, y, fn(x, y))

    def outline(self, outline_col: Optional[RGBA] = None) -> None:
        """Adds crisp 1px dark hue-shifted outline around opaque pixels."""
        src = [row[:] for row in self.px]
        for y in range(self.h):
            for x in range(self.w):
                if src[y][x][3] != 0:
                    continue
                neighbors: List[RGBA] = []
                for dx, dy in ((-1, 0), (1, 0), (0, -1), (0, 1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < self.w and 0 <= ny < self.h and src[ny][nx][3] > 0:
                        neighbors.append(src[ny][nx])
                if neighbors:
                    if outline_col is not None:
                        self.px[y][x] = outline_col
                    else:
                        avg_r = sum(c[0] for c in neighbors) // len(neighbors)
                        avg_g = sum(c[1] for c in neighbors) // len(neighbors)
                        avg_b = sum(c[2] for c in neighbors) // len(neighbors)
                        self.px[y][x] = (max(14, int(avg_r * 0.28)), max(12, int(avg_g * 0.26)), max(12, int(avg_b * 0.26)), 255)


def hash_noise(x: int, y: int, seed: int) -> float:
    n = (x * 374761393 + y * 668265263 + seed * 1442695041) & 0xFFFFFFFF
    n = (n ^ (n >> 13)) * 1274126177 & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 65535.0


# ---------------------------------------------------------------------------
# Stone & Wood Palettes
# ---------------------------------------------------------------------------

WOOD_PALETTES: Dict[str, Dict[str, RGB]] = {
    "oak": {
        "bark_dark": (66, 48, 28),
        "bark_mid": (104, 78, 46),
        "bark_light": (132, 102, 62),
        "heart_dark": (138, 104, 58),
        "heart_mid": (178, 140, 84),
        "heart_light": (206, 170, 110),
        "wet_tint": (118, 128, 72),
    },
    "birch": {
        "bark_dark": (54, 52, 48),
        "bark_mid": (214, 210, 198),
        "bark_light": (240, 238, 230),
        "heart_dark": (166, 146, 102),
        "heart_mid": (206, 186, 136),
        "heart_light": (228, 212, 166),
        "wet_tint": (150, 158, 108),
    },
    "spruce": {
        "bark_dark": (42, 27, 16),
        "bark_mid": (74, 48, 28),
        "bark_light": (98, 66, 40),
        "heart_dark": (112, 76, 42),
        "heart_mid": (148, 104, 60),
        "heart_light": (176, 128, 78),
        "wet_tint": (108, 96, 56),
    },
    "dark_oak": {
        "bark_dark": (34, 23, 14),
        "bark_mid": (58, 40, 25),
        "bark_light": (78, 55, 35),
        "heart_dark": (72, 46, 26),
        "heart_mid": (98, 65, 38),
        "heart_light": (124, 84, 50),
        "wet_tint": (76, 68, 42),
    },
}


# ---------------------------------------------------------------------------
# 1. Primitive Rocks, Knapped Parts, Plant Fiber, Cordage (32x32)
# ---------------------------------------------------------------------------

def make_granite_rock_item() -> Canvas:
    c = Canvas()
    cx, cy = 15.5, 16.5
    for y in range(32):
        for x in range(32):
            dx = (x - cx) / 11.2
            dy = (y - cy) / 9.2
            ang = math.atan2(dy, dx)
            r = math.hypot(dx, dy) + 0.08 * math.sin(ang * 3 + 0.7) + 0.05 * math.cos(ang * 5 - 1.1)
            if r <= 0.96:
                light = max(0.55, min(1.25, 1.04 - 0.24 * dx - 0.28 * dy))
                n = hash_noise(x, y, 101)
                if n < 0.09:
                    base = (72, 54, 52)       # dark biotite fleck
                elif n > 0.87:
                    base = (232, 206, 198)    # pale quartz glint
                elif n > 0.62:
                    base = (204, 138, 124)    # pink feldspar
                else:
                    base = (172, 114, 102)
                c.set(x, y, shade(base, light))
    c.outline((46, 30, 28, 255))
    return c


def make_limestone_rock_item() -> Canvas:
    c = Canvas()
    cx, cy = 15.5, 16.5
    for y in range(32):
        for x in range(32):
            dx = (x - cx) / 11.8
            dy = (y - cy) / 8.5
            ang = math.atan2(dy, dx)
            r = math.hypot(dx, dy) + 0.06 * math.cos(ang * 2 + 0.4) + 0.05 * math.sin(ang * 4)
            if r <= 0.95:
                light = max(0.60, min(1.22, 1.03 - 0.22 * dx - 0.28 * dy))
                band = math.sin(y * 1.15 + hash_noise(x, y, 202) * 0.8)
                n = hash_noise(x, y, 203)
                if band < -0.55:
                    base = (166, 157, 138)
                elif n > 0.85:
                    base = (236, 230, 212)
                else:
                    base = (204, 195, 174)
                c.set(x, y, shade(base, light))
    c.outline((76, 70, 58, 255))
    return c


def make_flint_rock_item() -> Canvas:
    c = Canvas()
    pts = [(9, 10), (16, 6), (24, 9), (27, 16), (24, 23), (15, 25), (7, 22), (5, 16)]

    def shader(x: int, y: int) -> RGBA:
        dx = (x - 15.5) / 11.0
        dy = (y - 16.0) / 10.0
        n = hash_noise(x, y, 303)
        # Waxy conchoidal facet + pale chalk cortex on bottom-left edge
        if x < 9 and y > 13:
            base = (158, 160, 152)
        elif (x - 14) * 0.7 + (y - 12) < -2.5:
            base = (118, 128, 144) if n > 0.35 else (146, 156, 174)
        else:
            base = (56, 62, 74) if n > 0.25 else (44, 48, 58)
        light = max(0.65, min(1.25, 1.02 - 0.22 * dx - 0.24 * dy))
        return shade(base, light)

    c.fill_poly(pts, shader)
    c.outline((20, 22, 28, 255))
    return c


def make_sharp_stone() -> Canvas:
    c = Canvas()
    # Diagonal leaf-shaped flint flake pointing top-right (26, 5)
    pts = [(7, 23), (6, 19), (11, 12), (19, 6), (26, 5), (25, 12), (19, 19), (12, 25)]

    def shader(x: int, y: int) -> RGBA:
        # Distance from main dorsal ridge (from (8,23) to (25,6))
        ridge_dist = ((x - 8) * (-17) + (y - 23) * (-17))  # dot
        # Actually use diagonal coordinate u = x + y, v = x - y
        v = x - y
        n = hash_noise(x, y, 404)
        ripple = math.sin((x + y) * 1.1 + n * 0.8)
        if x + y > 34 and v > 4:
            # Keen outer razor edge
            return (222, 232, 242, 255) if ripple > -0.2 else (188, 200, 212, 255)
        if v < -2:
            # Upper-left sharp bevel
            base = (168, 178, 190) if ripple > 0 else (142, 152, 164)
        elif v > 3:
            # Lower-right shaded bevel
            base = (86, 92, 102) if ripple > 0 else (68, 74, 84)
        else:
            # Central ridge
            base = (122, 130, 140)
        return shade(base, 1.05 - 0.012 * (y - 16) + 0.06 * (n - 0.5))

    c.fill_poly(pts, shader)
    c.outline((24, 28, 34, 255))
    return c


def make_stone_axe_head() -> Canvas:
    c = Canvas()
    # Heavy celt blade: narrow waisted poll on left (x=5..10), flared curved cutting bit on right (x=21..27)
    pts = [(5, 13), (12, 11), (20, 7), (26, 9), (28, 16), (26, 23), (20, 25), (12, 21), (5, 19)]

    def shader(x: int, y: int) -> RGBA:
        n = hash_noise(x, y, 505)
        ripple = math.sin((x - y * 0.5) * 0.9 + n * 0.7)
        if x >= 23:
            # Honed curved cutting edge
            base = (218, 226, 235) if ripple > -0.3 else (186, 196, 208)
        elif x >= 19:
            # Ground bevel transition
            base = (154, 162, 172)
        elif x <= 10:
            # Rough grooved hafting poll
            base = (78, 80, 86) if (x % 3 == 0) else (96, 100, 106)
        else:
            # Knapped core body
            base = (118, 124, 132) if ripple > 0 else (98, 104, 112)
        light = max(0.70, min(1.20, 1.05 - 0.02 * (y - 16)))
        return shade(base, light)

    c.fill_poly(pts, shader)
    c.outline((26, 28, 32, 255))
    return c


def make_plant_fiber() -> Canvas:
    c = Canvas()
    # Bundled wild sedge / grass blades tied together near bottom-center
    blades = [
        ((12, 26), (7, 13), (6, 7), (104, 148, 48)),
        ((15, 26), (13, 14), (12, 5), (136, 180, 64)),
        ((17, 26), (18, 13), (21, 5), (156, 202, 78)),
        ((19, 25), (23, 15), (26, 9), (124, 168, 56)),
        ((14, 26), (16, 16), (17, 8), (172, 216, 92)),
    ]
    for (x0, y0), (xm, ym), (x1, y1), col in blades:
        for i in range(40):
            t = i / 39.0
            bx = round((1 - t) ** 2 * x0 + 2 * (1 - t) * t * xm + t ** 2 * x1)
            by = round((1 - t) ** 2 * y0 + 2 * (1 - t) * t * ym + t ** 2 * y1)
            factor = 0.78 + 0.35 * (1.0 - by / 30.0)
            c.set(bx, by, shade(col, factor))
            if t < 0.7:
                c.set(bx + 1, by, shade(col, factor * 0.88))
    # Tie band around waist (y=21..22)
    c.line(11, 21, 20, 21, (192, 160, 88, 255), 1)
    c.line(11, 22, 20, 22, (152, 120, 60, 255), 1)
    c.outline((32, 48, 18, 255))
    return c


def make_cordage() -> Canvas:
    c = Canvas()
    # Elliptical multi-strand twisted rope coil + center binding collar
    for t_deg in range(0, 360, 2):
        rad = math.radians(t_deg)
        for ring_r in (-2.0, 0.0, 2.0):
            rx = 16.0 + (8.2 + ring_r * 0.65) * math.cos(rad)
            ry = 16.0 + (9.8 + ring_r * 0.65) * math.sin(rad)
            px, py = round(rx), round(ry)
            twist = math.sin(t_deg * 0.35 + ring_r)
            if twist > 0.35:
                col = (228, 194, 130, 255)
            elif twist < -0.35:
                col = (138, 102, 52, 255)
            else:
                col = (192, 150, 88, 255)
            c.set(px, py, col)
            c.set(px + 1, py, col)
    # Cross-wrapped center tie around left/right bundle waist
    for y in range(14, 19):
        for x in range(6, 13):
            c.set(x, y, (214, 178, 114, 255) if (x + y) % 2 == 0 else (164, 124, 68, 255))
        for x in range(20, 27):
            c.set(x, y, (214, 178, 114, 255) if (x + y) % 2 == 0 else (164, 124, 68, 255))
    # Short frayed loose tail at bottom right
    c.line(22, 24, 26, 27, (186, 146, 84, 255), 1)
    c.outline((56, 38, 18, 255))
    return c


# ---------------------------------------------------------------------------
# 2. Custom Tool Designs (32x32, sharp and dull variants)
# ---------------------------------------------------------------------------

def draw_wood_shaft(c: Canvas, x0: int, y0: int, x1: int, y1: int, thickness: int = 2) -> None:
    steps = max(abs(x1 - x0), abs(y1 - y0)) * 2
    for i in range(steps + 1):
        t = i / steps
        cx = x0 + (x1 - x0) * t
        cy = y0 + (y1 - y0) * t
        for dy in range(-1, 2):
            for dx in range(-1, 2):
                if abs(dx) + abs(dy) <= thickness:
                    px, py = round(cx + dx), round(cy + dy)
                    # Upper-left highlight vs bottom-right bark shadow
                    offset = dx + dy
                    if offset < 0:
                        col = (162, 116, 62, 255)
                    elif offset == 0:
                        col = (128, 86, 42, 255)
                    else:
                        col = (92, 60, 28, 255)
                    if hash_noise(px, py, 601) > 0.78:
                        col = shade((118, 80, 38), 0.85)
                    c.set(px, py, col)
    # Flared pommel knob at grip base (x0, y0)
    c.set(x0 - 1, y0 + 1, (92, 60, 28, 255))
    c.set(x0, y0 + 1, (76, 48, 22, 255))


def draw_cordage_wrap(c: Canvas, cx: int, cy: int, radius: int = 3) -> None:
    for dy in range(-radius, radius + 1):
        for dx in range(-radius, radius + 1):
            if abs(dx) + abs(dy) <= radius + 1:
                x, y = cx + dx, cy + dy
                cross = (dx == dy) or (dx == -dy)
                if cross:
                    c.set(x, y, (232, 198, 134, 255))
                elif (x + y) % 2 == 0:
                    c.set(x, y, (196, 154, 92, 255))
                else:
                    c.set(x, y, (148, 108, 56, 255))


def make_stone_knife(dull: bool = False) -> Canvas:
    c = Canvas()
    # Curved grip (4,27) -> (13,18)
    draw_wood_shaft(c, 5, 26, 13, 18, thickness=1)
    # Leaf-shaped knapped stone blade (13,18) -> (27,4)
    pts = [(12, 17), (16, 10), (22, 5), (27, 4), (26, 10), (21, 16), (14, 19)]

    def shader(x: int, y: int) -> RGBA:
        if dull and ((x == 24 and y == 6) or (x == 19 and y == 9) or (x == 26 and y == 8)):
            return T  # chipped dull notches along cutting edge
        n = hash_noise(x, y, 701)
        if x - y > 4 and not dull:
            return (224, 234, 244, 255) if (x + y) % 3 != 0 else (188, 200, 212, 255)
        if y - x < -2:
            base = (156, 166, 178) if not dull else (112, 118, 128)
        else:
            base = (96, 102, 112)
        return shade(base, 0.95 + 0.12 * n)

    c.fill_poly(pts, shader)
    draw_cordage_wrap(c, 13, 18, radius=2)
    c.outline((26, 22, 20, 255))
    return c


def make_stone_axe(dull: bool = False) -> Canvas:
    c = Canvas()
    # Ergonomic haft from (5,26) up to (23,8)
    draw_wood_shaft(c, 5, 26, 23, 8, thickness=1)
    # Heavy waisted stone axe head projecting forward-left & slight rear poll
    pts = [(9, 6), (15, 4), (21, 7), (24, 12), (21, 16), (14, 18), (8, 15), (6, 10)]

    def shader(x: int, y: int) -> RGBA:
        if dull and ((x == 7 and y == 11) or (x == 11 and y == 5) or (x == 9 and y == 14)):
            return T  # visibly chipped dull edge
        n = hash_noise(x, y, 801)
        # Distance from outer left curved bit
        edge_dist = (x - 8) * 0.7 + (y - 9) * 0.7
        if edge_dist < 1.2:
            return (130, 136, 144, 255) if dull else ((224, 232, 240, 255) if n > 0.25 else (194, 204, 214, 255))
        if edge_dist < 4.5:
            base = (158, 166, 176) if not dull else (118, 124, 132)
        else:
            base = (104, 110, 118) if n > 0.35 else (84, 90, 98)
        return shade(base, 0.96 + 0.1 * n)

    c.fill_poly(pts, shader)
    # Socket wood tip poking past collar + thick cordage cross-lashing
    c.set(22, 9, (128, 86, 42, 255))
    c.set(23, 8, (152, 106, 54, 255))
    draw_cordage_wrap(c, 19, 12, radius=3)
    c.outline((24, 20, 18, 255))
    return c


def make_stone_pickaxe(dull: bool = False) -> Canvas:
    c = Canvas()
    # Wood shaft from (5,26) up to (23,8)
    draw_wood_shaft(c, 5, 26, 23, 8, thickness=1)
    # Curved arched pickaxe head across top-left -> top-right -> lower-right
    pts = [
        (7, 9), (12, 5), (18, 4), (24, 6), (27, 11), (28, 17), (26, 23),
        (23, 24), (23, 18), (22, 13), (18, 9), (13, 9), (8, 12),
    ]

    def shader(x: int, y: int) -> RGBA:
        if dull and ((x == 7 and y == 10) or (x == 25 and y == 23)):
            return T  # blunted pick tips
        n = hash_noise(x, y, 901)
        tip_dist = min(math.hypot(x - 8, y - 10), math.hypot(x - 25, y - 22))
        if tip_dist < 3.6 and not dull:
            return (222, 230, 240, 255) if n > 0.25 else (182, 192, 204, 255)
        if y < 8 or (x > 24 and y < 16):
            base = (158, 166, 176)
        else:
            base = (102, 108, 116) if n > 0.35 else (82, 88, 96)
        return shade(base, 0.96 + 0.1 * n)

    c.fill_poly(pts, shader)
    draw_cordage_wrap(c, 22, 9, radius=3)
    c.outline((24, 20, 18, 255))
    return c


# ---------------------------------------------------------------------------
# 3. Wood Processing Items (32x32)
# ---------------------------------------------------------------------------

def make_fresh_log_item(species: str) -> Canvas:
    pal = WOOD_PALETTES[species]
    c = Canvas()
    # Isometric-style tilted cut log cylinder (bark body + wet end-grain rings)
    # Body cylinder
    c.fill_poly([(5, 13), (17, 6), (27, 13), (27, 22), (15, 28), (5, 22)],
                lambda x, y: shade(
                    pal["bark_dark"] if (species == "birch" and hash_noise(x // 2, y, 11) > 0.76)
                    else (pal["bark_light"] if (x + y) % 5 == 0 else pal["bark_mid"]),
                    1.05 - 0.015 * (y - 12),
                ))
    # Cut sapwood front-top face centered around (16, 13)
    for y in range(6, 20):
        for x in range(7, 25):
            dx = (x - 16.0) / 8.5
            dy = (y - 12.5) / 5.2
            r = math.hypot(dx, dy)
            if r <= 0.95:
                if r > 0.78:
                    base = mix(pal["heart_light"], pal["wet_tint"], 0.35)
                elif int(r * 7) % 2 == 0:
                    base = mix(pal["heart_mid"], pal["wet_tint"], 0.25)
                else:
                    base = pal["heart_dark"]
                c.set(x, y, shade(base, 1.04))
    c.outline()
    return c


def make_split_wood_item(species: str, dry: bool = False) -> Canvas:
    pal = WOOD_PALETTES[species]
    c = Canvas()
    # Two stacked wedge-shaped firewood billets with distinct heartwood top, end-grain & bark back
    for idx, (ox, oy) in enumerate([(0, 4), (4, -4)]):
        pts = [(5 + ox, 19 + oy), (17 + ox, 11 + oy), (25 + ox, 14 + oy), (25 + ox, 19 + oy), (13 + ox, 26 + oy), (5 + ox, 23 + oy)]

        def shader(x: int, y: int, _ox=ox, _oy=oy, _i=idx) -> RGBA:
            if _i == 1 and y >= 22 + _oy - (x - 5 - _ox) * 0.15:
                return shade(pal["bark_dark"], 0.65)  # crisp dark separation shadow
            if x <= 11 + _ox and y >= 18 + _oy:
                # Triangular split end-grain wedge
                base = pal["heart_light"] if dry else mix(pal["heart_mid"], pal["wet_tint"], 0.30)
                return shade(base, 1.08)
            if y > 20 + _oy - (x - 5 - _ox) * 0.45:
                base = pal["bark_mid"]
            else:
                base = pal["heart_light"] if dry else mix(pal["heart_mid"], pal["wet_tint"], 0.40)
            grain = 0.94 + 0.12 * hash_noise(x, y // 2, 1200 + _i)
            return shade(base, grain * (1.06 if _i == 1 else 0.92))

        c.fill_poly(pts, shader)
    c.outline()
    return c


def make_rough_plank_item(species: str, dry: bool = False) -> Canvas:
    pal = WOOD_PALETTES[species]
    c = Canvas()
    # Rough-hewn diagonal timber board with saw/axe notch ends & bark rim
    pts = [(4, 21), (7, 17), (18, 6), (22, 6), (27, 9), (26, 13), (14, 26), (9, 27), (5, 25)]

    def shader(x: int, y: int) -> RGBA:
        u = x + y
        v = x - y
        n = hash_noise(x, y, 1300)
        if v > 11 or u > 38:
            base = pal["bark_mid"]
        else:
            grain = math.sin((x - y) * 1.4 + n * 1.1)
            base = pal["heart_light"] if (dry and grain > 0) else (
                pal["heart_mid"] if dry else mix(pal["heart_dark"], pal["wet_tint"], 0.45)
            )
            if not dry and n > 0.72:
                base = mix(base, pal["wet_tint"], 0.5)
        light = 1.08 - 0.015 * (y - 16)
        return shade(base, light)

    c.fill_poly(pts, shader)
    c.set(15, 16, shade(pal["heart_dark"], 0.78))
    c.set(16, 16, shade(pal["heart_dark"], 0.78))
    c.set(19, 12, shade(pal["heart_dark"], 0.80))
    c.outline()
    return c


# ---------------------------------------------------------------------------
# 4. Block Textures (32x32 opaque RGBA)
# ---------------------------------------------------------------------------

def make_rock_block(kind: str) -> Canvas:
    c = Canvas(32, 32, (0, 0, 0, 255))
    seed = {"granite": 1001, "limestone": 1002, "flint": 1003}[kind]
    for y in range(32):
        for x in range(32):
            n = hash_noise(x, y, seed)
            if kind == "granite":
                if n < 0.09:
                    base = (76, 58, 56)
                elif n > 0.87:
                    base = (228, 198, 190)
                elif n > 0.55:
                    base = (196, 134, 120)
                else:
                    base = (168, 114, 104)
                bevel = 1.08 - 0.14 * (x / 31.0 + y / 31.0)
            elif kind == "limestone":
                band = math.sin(y * 0.95 + n * 0.7)
                base = (168, 158, 138) if band < -0.5 else ((232, 224, 204) if n > 0.75 else (204, 195, 174))
                bevel = 1.06 - 0.10 * (y / 31.0)
            else:  # flint
                wave = math.sin((x + y) * 0.45 + n * 0.6)
                if n > 0.92:
                    base = (158, 160, 152)
                elif wave > 0.35:
                    base = (104, 112, 128)
                else:
                    base = (52, 58, 70)
                bevel = 1.08 - 0.14 * (y / 31.0)
            c.set(x, y, shade(base, bevel))
    return c


def make_fallen_stick_block() -> Canvas:
    c = Canvas(32, 32, (0, 0, 0, 255))
    for y in range(32):
        for x in range(32):
            n = hash_noise(x, y, 1400)
            ridge = math.sin(y * 1.35 + n * 1.1)
            base = (146, 102, 54) if ridge > 0.35 else ((84, 54, 26) if ridge < -0.45 else (114, 76, 38))
            if 10 <= x <= 13 and 12 <= y <= 15:
                base = (64, 40, 18)  # wood knot
            c.set(x, y, shade(base, 1.04 - 0.08 * (y / 31.0)))
    return c


def make_notched_inner_block(species: str) -> Canvas:
    pal = WOOD_PALETTES[species]
    c = Canvas(32, 32, (0, 0, 0, 255))
    for y in range(32):
        for x in range(32):
            n = hash_noise(x, y, 1500)
            ring = math.sin(math.hypot(x - 15.5, y - 15.5) * 1.3 + n * 0.6)
            base = pal["heart_light"] if ring > 0.25 else (pal["heart_dark"] if ring < -0.45 else pal["heart_mid"])
            # Subtle axe kerf chop groove lines
            if (y % 7 == 0) and n > 0.35:
                base = mix(base, pal["heart_dark"], 0.45)
            c.set(x, y, shade(base, 1.0))
    return c


def make_trunk_branch_block(species: str) -> Canvas:
    pal = WOOD_PALETTES[species]
    c = Canvas(32, 32, (0, 0, 0, 255))
    for y in range(32):
        for x in range(32):
            n = hash_noise(x, y, 1600)
            if species == "birch":
                base = pal["bark_dark"] if (y % 6 == 0 and n > 0.55) else pal["bark_mid"]
            else:
                base = pal["bark_light"] if x % 5 == 0 else (pal["bark_dark"] if n < 0.25 else pal["bark_mid"])
            c.set(x, y, shade(base, 1.0 - 0.1 * (y / 31.0)))
    return c


def make_drying_wood_block(species: str, dry: bool, is_plank: bool) -> Canvas:
    pal = WOOD_PALETTES[species]
    c = Canvas(32, 32, (0, 0, 0, 255))
    for y in range(32):
        for x in range(32):
            n = hash_noise(x, y, 1700 + (1 if dry else 0))
            if is_plank:
                grain = math.sin(y * 1.2 + n * 0.9)
                base = pal["heart_light"] if (dry and grain > 0.15) else (
                    pal["heart_mid"] if dry else mix(pal["heart_dark"], pal["wet_tint"], 0.48)
                )
                if y in (0, 15, 16, 31):
                    base = mix(base, pal["bark_dark"], 0.35)
            else:
                # Split firewood bundle: textured bark strip on outer edges + radial split wood grain
                if x < 6 or x > 25:
                    base = pal["bark_dark"] if (y % 4 == 0 and n > 0.4) else pal["bark_mid"]
                else:
                    grain = math.sin(x * 1.1 + n * 0.8)
                    base = pal["heart_light"] if (dry and grain > 0.1) else (
                        pal["heart_mid"] if dry else mix(pal["heart_mid"], pal["wet_tint"], 0.42)
                    )
                    if y in (15, 16):
                        base = pal["bark_dark"]
            c.set(x, y, shade(base, 0.96 + 0.1 * n))
    return c


# ---------------------------------------------------------------------------
# Firemaking, Hearth & Ash Textures
# ---------------------------------------------------------------------------

def make_tinder_bundle() -> Canvas:
    c = Canvas(32, 32)
    # Cozy woven nest of dry golden straw, bark shavings & cordage wrap
    for i in range(42):
        ang = (i / 42.0) * math.pi * 2.0
        r0 = 3.0 + (i % 4) * 1.4
        r1 = 10.5 + (i % 3) * 1.6
        x0 = round(16 + math.cos(ang) * r0)
        y0 = round(17 + math.sin(ang) * r0 * 0.72)
        x1 = round(16 + math.cos(ang + 0.55) * r1)
        y1 = round(17 + math.sin(ang + 0.55) * r1 * 0.72)
        col = (214, 178, 94, 255) if i % 3 == 0 else ((178, 134, 62, 255) if i % 3 == 1 else (138, 98, 44, 255))
        c.line(x0, y0, x1, y1, col, 1)
    # Central tender hollow & ember tinder core
    for y in range(13, 21):
        for x in range(11, 21):
            d = math.hypot(x - 16, (y - 17) * 1.25)
            if d < 5.0:
                n = hash_noise(x, y, 2100)
                col = (232, 202, 128) if n > 0.45 else (196, 156, 86)
                c.set(x, y, shade(col, 1.0 - d * 0.04))
    # Fiber cordage binding strap across bundle
    c.line(8, 20, 24, 15, (148, 122, 68, 255), 2)
    c.line(8, 21, 24, 16, (108, 86, 44, 255), 1)
    c.outline()
    return c


def make_fire_drill() -> Canvas:
    c = Canvas(32, 32)
    # Horizontal notched cedar hearthboard at base
    c.fill_poly([(4, 22), (27, 22), (28, 27), (3, 27)],
                lambda x, y: shade((146, 98, 52) if y < 25 else (106, 68, 34), 0.94 + 0.1 * hash_noise(x, y, 2200)))
    # Charred notch & ember dust glow on board
    for y in range(21, 26):
        for x in range(14, 19):
            if math.hypot(x - 16, y - 22.5) < 2.6:
                c.set(x, y, (238, 114, 28, 255) if y == 22 else (56, 36, 26, 255))
    # Angled wooden spindle stick pressed into hearthboard
    for i in range(22):
        t = i / 21.0
        px = round(16 + t * 8.0)
        py = round(22 - t * 17.0)
        for dx in (-1, 0, 1):
            col = (182, 134, 74) if dx < 0 else ((146, 102, 52) if dx == 0 else (104, 68, 34))
            if i < 3:
                col = mix(col, (48, 30, 22), 0.65)  # charred tip
            c.set(px + dx, py, shade(col, 1.0))
    c.outline()
    return c


def make_bow_drill() -> Canvas:
    c = Canvas(32, 32)
    # Curved wooden bow arc
    for i in range(48):
        t = i / 47.0
        bx = round(6 + t * 20)
        by = round(19 - math.sin(t * math.pi) * 12)
        c.line(bx, by, bx, by + 1, (148, 96, 48, 255), 2)
    # Twisted cordage bowstring wrapped around upright spindle
    c.line(6, 19, 16, 16, (206, 186, 132, 255), 1)
    c.line(16, 16, 26, 19, (206, 186, 132, 255), 1)
    # Vertical spindle + top granite bearing socket + charred bottom tip
    for y in range(6, 27):
        col = (176, 132, 74) if y < 23 else (58, 36, 24)
        c.set(15, y, shade(col, 1.05))
        c.set(16, y, shade(col, 0.92))
    # Stone handhold bearing block at top
    c.fill_poly([(12, 3), (20, 3), (21, 7), (11, 7)],
                lambda x, y: shade((132, 132, 138), 0.92 + 0.14 * hash_noise(x, y, 2300)))
    # Glowing friction ember spot at base
    c.set(15, 27, (242, 118, 28, 255))
    c.set(16, 27, (252, 182, 52, 255))
    c.outline()
    return c


def make_wood_ash_item() -> Canvas:
    c = Canvas(32, 32)
    c.fill_poly([(6, 23), (11, 15), (17, 13), (22, 16), (26, 23), (22, 26), (10, 26)],
                lambda x, y: shade(
                    (204, 206, 202) if y < 18 else ((146, 148, 146) if hash_noise(x, y, 2400) > 0.22 else (58, 56, 54)),
                    0.94 + 0.12 * hash_noise(x, y, 2401)))
    c.outline()
    return c


def make_hearth_side_block() -> Canvas:
    c = Canvas(32, 32, (0, 0, 0, 255))
    for y in range(32):
        for x in range(32):
            n = hash_noise(x, y, 2500)
            if y < 22:
                # Fieldstone ring masonry
                stone_cell = ((x // 8) + (y // 6) * 3)
                edge = (x % 8 == 0) or (y % 6 == 0)
                base = (72, 70, 68) if edge else ((128, 126, 122) if stone_cell % 2 == 0 else (108, 104, 100))
                c.set(x, y, shade(base, 0.92 + 0.16 * n))
            else:
                # Compacted dark earth foundation
                base = (68, 52, 38) if n > 0.35 else (54, 40, 28)
                c.set(x, y, shade(base, 0.95 + 0.1 * n))
    return c


def make_hearth_top_block(mode: str) -> Canvas:
    c = Canvas(32, 32, (0, 0, 0, 255))
    for y in range(32):
        for x in range(32):
            d = math.hypot(x - 15.5, y - 15.5)
            n = hash_noise(x, y, 2600 + (1 if mode == "embers" else (2 if mode == "lit" else 0)))
            if d > 11.5:
                # Outer ring of river cobblestones
                ang = math.atan2(y - 15.5, x - 15.5)
                seg = int((ang + math.pi) / (math.pi / 4))
                seam = abs(((ang + math.pi) % (math.pi / 4)) - (math.pi / 8)) > (math.pi / 8 - 0.11)
                base = (66, 64, 62) if seam else ((136, 132, 128) if seg % 2 == 0 else (112, 108, 104))
                c.set(x, y, shade(base, 0.90 + 0.18 * n))
            else:
                # Interior fuel & ash pit
                if mode == "cold":
                    base = (128, 128, 124) if n > 0.45 else (58, 54, 50)
                elif mode == "embers":
                    if n > 0.62:
                        base = (232, 82, 18)
                    elif n > 0.45:
                        base = (164, 42, 16)
                    else:
                        base = (52, 48, 46)
                else:  # lit blazing fire bed
                    if d < 5.5 and n > 0.35:
                        base = (255, 212, 72)
                    elif n > 0.30:
                        base = (242, 108, 22)
                    else:
                        base = (68, 42, 28)
                c.set(x, y, shade(base, 0.94 + 0.12 * n))
    return c


def make_wood_ash_pile_block() -> Canvas:
    c = Canvas(32, 32, (0, 0, 0, 255))
    for y in range(32):
        for x in range(32):
            n = hash_noise(x, y, 2700)
            if n < 0.09:
                base = (48, 46, 46)  # tiny unburned charcoal fleck
            elif n > 0.72:
                base = (214, 216, 210)
            else:
                base = (176, 178, 174)
            c.set(x, y, shade(base, 0.94 + 0.12 * n))
    return c


# ---------------------------------------------------------------------------
# Hydration & Water Quality Gear Textures
# ---------------------------------------------------------------------------

def make_waterskin() -> Canvas:
    c = Canvas(32, 32)
    # Shoulder strap arc
    for i in range(36):
        t = i / 35.0
        bx = round(7 + t * 18)
        by = round(10 - math.sin(t * math.pi) * 6.5)
        c.line(bx, by, bx, by + 1, (112, 74, 42, 255), 1)
    # Pear-shaped stitched leather flask body
    for y in range(9, 28):
        for x in range(6, 26):
            ny = (y - 19.0) / 8.5
            width = 7.8 * (1.0 - 0.35 * ny * ny) if y > 13 else 3.2 + (y - 9) * 0.85
            if abs(x - 16) <= width:
                n = hash_noise(x, y, 2800)
                dist_edge = width - abs(x - 16)
                light = 1.12 - ((x - 14) * 0.028) - max(0.0, (y - 17) * 0.025)
                base = (146, 86, 46)
                if dist_edge < 1.3:
                    base = (96, 54, 28) if (y % 3 != 0) else (206, 186, 134)  # stitched seam
                c.set(x, y, shade(base, light * (0.94 + 0.12 * n)))
    # Cordage neck wrap & wooden cork stopper
    for x in range(13, 20):
        c.set(x, 8, (186, 162, 108, 255))
        c.set(x, 9, (148, 124, 78, 255))
    for y in range(4, 8):
        for x in range(14, 19):
            c.set(x, y, shade((176, 132, 78), 1.0 - (x - 14) * 0.06))
    c.outline()
    return c


def make_water_filter() -> Canvas:
    c = Canvas(32, 32)
    # Bamboo / hollowed split-wood purifying tube with internal gravel, sand & charcoal layers
    for y in range(5, 27):
        for x in range(10, 22):
            n = hash_noise(x, y, 2900)
            if x in (10, 11, 20, 21):
                # Wooden cylinder casing with cordage bands
                col = (166, 126, 72) if y not in (9, 10, 21, 22) else (196, 174, 116)
                c.set(x, y, shade(col, 1.04 - (x - 10) * 0.02))
            else:
                if y < 11:
                    col = (88, 174, 226) if n > 0.35 else (112, 196, 242)  # water at top
                elif y < 17:
                    col = (52, 50, 54) if n > 0.25 else (34, 34, 38)  # activated charcoal layer
                elif y < 23:
                    col = (212, 194, 144) if n > 0.3 else (184, 166, 118)  # quartz sand bed
                else:
                    col = (146, 144, 140) if n > 0.4 else (114, 112, 108)  # gravel base
                c.set(x, y, shade(col, 0.92 + 0.16 * n))
    # Clear purified drop below spout
    for y in range(28, 31):
        for x in range(14, 18):
            if math.hypot(x - 15.5, y - 29.0) < 1.8:
                c.set(x, y, (118, 212, 255, 255))
    c.outline()
    return c


def make_electrolyte_salts() -> Canvas:
    c = Canvas(32, 32)
    # Open woven pouch holding bright crystalline mineral salts
    c.fill_poly([(7, 13), (25, 13), (27, 27), (5, 27)],
                lambda x, y: shade((182, 164, 128) if (x + y) % 3 else (154, 136, 102),
                                   0.94 + 0.12 * hash_noise(x, y, 3000)))
    # Cordage tie rim
    c.line(8, 14, 24, 14, (132, 104, 62, 255), 2)
    # Mound of pale mineral & sea-salt crystals peeking out top
    for y in range(7, 16):
        for x in range(9, 23):
            d = math.hypot((x - 16) * 0.78, y - 13.5)
            if d < 5.6:
                n = hash_noise(x, y, 3050)
                col = (242, 236, 232) if n > 0.55 else ((232, 206, 196) if n > 0.25 else (218, 232, 236))
                c.set(x, y, shade(col, 1.05 - d * 0.04))
    c.outline()
    return c


# ---------------------------------------------------------------------------
# Diegetic Material Processing, Hidecraft & Vessel Textures
# ---------------------------------------------------------------------------

def make_material_canvas() -> Canvas:
    return Canvas(32, 32, (255, 255, 255, 255))


def make_stone_scraper(dull: bool = False) -> Canvas:
    c = Canvas(32, 32)
    # Fan-shaped knapped end-scraper held with cordage-wrapped finger grip
    for y in range(6, 27):
        for x in range(7, 25):
            dy = (y - 17.0) / 9.5
            dx = (x - 16.0) / (8.5 if y < 17 else 5.2)
            if dx * dx + dy * dy <= 1.0:
                n = hash_noise(x, y, 3100)
                if y <= 11:
                    col = (146, 142, 136) if dull else (196, 196, 192)  # beveled scraping edge
                elif y >= 21:
                    col = (164, 138, 92) if (x + y) % 2 == 0 else (136, 112, 72)  # cordage wrap
                else:
                    col = (108, 106, 102)
                c.set(x, y, shade(col, 0.92 + 0.16 * n))
    c.outline()
    return c


def make_bone_awl() -> Canvas:
    c = Canvas(32, 32)
    # Tapered sharp bone awl diagonally oriented from knuckle handle to fine piercing point
    for i in range(22):
        t = i / 21.0
        cx = round(7 + i * 0.85)
        cy = round(25 - i * 0.85)
        rad = 2.6 - t * 2.1
        for oy in range(-3, 4):
            for ox in range(-3, 4):
                if ox * ox + oy * oy <= rad * rad:
                    n = hash_noise(cx + ox, cy + oy, 3200)
                    col = (228, 220, 198) if i > 3 else (204, 192, 166)
                    c.set(cx + ox, cy + oy, shade(col, 0.94 + 0.12 * n))
    c.outline()
    return c


def make_bone_needle() -> Canvas:
    c = Canvas(32, 32)
    # Slender ivory bone needle with tiny slotted eye & cordage thread trailing
    c.line(8, 24, 25, 7, (232, 224, 206, 255), 2)
    c.set(11, 21, (60, 52, 46, 255))  # needle eye
    c.line(10, 22, 6, 26, (186, 158, 102, 255), 1)
    c.line(6, 26, 12, 28, (164, 138, 86, 255), 1)
    c.outline()
    return c


def make_raw_hide() -> Canvas:
    c = Canvas(32, 32)
    # Irregular anatomical hide silhouette with thick dark spine down center and fleshy underside
    for y in range(4, 28):
        for x in range(5, 27):
            edge_w = 9.5 - 3.0 * math.cos((y - 16) * 0.25)
            if abs(x - 16) <= edge_w:
                n = hash_noise(x, y, 3300)
                spine = abs(x - 16) < 2.2
                col = (128, 68, 44) if spine else (172, 92, 68)
                c.set(x, y, shade(col, 0.90 + 0.18 * n))
    c.outline()
    return c


def make_leather_piece() -> Canvas:
    c = Canvas(32, 32)
    # Clean trimmed supple tan leather panel
    c.fill_poly([(7, 6), (24, 7), (25, 25), (6, 24)],
                lambda x, y: shade((184, 124, 70), 0.94 + 0.12 * hash_noise(x, y, 3400)))
    c.outline()
    return c


def make_hide_scrap() -> Canvas:
    c = Canvas(32, 32)
    c.fill_poly([(8, 12), (21, 9), (24, 20), (14, 24), (7, 19)],
                lambda x, y: shade((166, 108, 62), 0.92 + 0.16 * hash_noise(x, y, 3500)))
    c.outline()
    return c


def make_tallow_wax() -> Canvas:
    c = Canvas(32, 32)
    for y in range(8, 25):
        for x in range(7, 25):
            if math.hypot((x - 16) * 0.9, y - 16.5) < 7.2:
                n = hash_noise(x, y, 3600)
                c.set(x, y, shade((238, 224, 172), 0.92 + 0.14 * n))
    c.outline()
    return c


def make_pine_resin() -> Canvas:
    c = Canvas(32, 32)
    for y in range(7, 26):
        for x in range(8, 24):
            if math.hypot(x - 16, (y - 16.5) * 0.85) < 6.8:
                n = hash_noise(x, y, 3700)
                c.set(x, y, shade((216, 134, 34) if n > 0.35 else (176, 94, 20), 0.94 + 0.14 * n))
    c.outline()
    return c


def make_gourd(carved: bool) -> Canvas:
    c = Canvas(32, 32)
    # Double-lobed bottle gourd silhouette
    for y in range(6, 27):
        for x in range(7, 25):
            upper = math.hypot((x - 16) * 1.15, y - 11.5) < 4.5
            lower = math.hypot((x - 16) * 0.92, y - 20.5) < 6.4
            if upper or lower:
                n = hash_noise(x, y, 3800 + (50 if carved else 0))
                base = (196, 146, 68) if carved else (112, 162, 68)
                c.set(x, y, shade(base, 0.92 + 0.16 * n - (x - 14) * 0.018))
    if carved:
        for x in range(14, 19):
            c.set(x, 6, (118, 78, 42, 255))  # bored stopper mouth
    c.outline()
    return c


def make_workstation_wood(seed: int, slab_top: bool = False) -> Canvas:
    c = Canvas(32, 32, (0, 0, 0, 255))
    for y in range(32):
        for x in range(32):
            n = hash_noise(x, y, seed)
            plank_seam = (y % 8 == 0) or (slab_top and (x == 0 or x == 31 or y == 0 or y == 31))
            base = (104, 72, 42) if plank_seam else ((158, 114, 70) if (y // 8) % 2 == 0 else (142, 102, 62))
            if slab_top and 4 <= x <= 27 and 4 <= y <= 27 and not plank_seam:
                base = (174, 132, 84)  # smooth planed top work face
            c.set(x, y, shade(base, 0.94 + 0.12 * n))
    return c


# ---------------------------------------------------------------------------
# Registry of All Textures
# ---------------------------------------------------------------------------

def build_all_textures() -> Dict[str, Canvas]:
    tex: Dict[str, Canvas] = {
        "item/granite_rock.png": make_granite_rock_item(),
        "item/limestone_rock.png": make_limestone_rock_item(),
        "item/flint_rock.png": make_flint_rock_item(),
        "item/sharp_stone.png": make_sharp_stone(),
        "item/stone_axe_head.png": make_stone_axe_head(),
        "item/plant_fiber.png": make_plant_fiber(),
        "item/cordage.png": make_cordage(),
        "item/stone_knife.png": make_stone_knife(dull=False),
        "item/stone_knife_dull.png": make_stone_knife(dull=True),
        "item/stone_axe.png": make_stone_axe(dull=False),
        "item/stone_axe_dull.png": make_stone_axe(dull=True),
        "item/stone_pickaxe.png": make_stone_pickaxe(dull=False),
        "item/stone_pickaxe_dull.png": make_stone_pickaxe(dull=True),
        "item/tinder_bundle.png": make_tinder_bundle(),
        "item/fire_drill.png": make_fire_drill(),
        "item/bow_drill.png": make_bow_drill(),
        "item/wood_ash.png": make_wood_ash_item(),
        "item/waterskin.png": make_waterskin(),
        "item/water_filter.png": make_water_filter(),
        "item/electrolyte_salts.png": make_electrolyte_salts(),
        "misc/material_canvas.png": make_material_canvas(),
        "item/stone_scraper.png": make_stone_scraper(dull=False),
        "item/stone_scraper_dull.png": make_stone_scraper(dull=True),
        "item/bone_awl.png": make_bone_awl(),
        "item/bone_needle.png": make_bone_needle(),
        "item/raw_hide.png": make_raw_hide(),
        "item/leather_piece.png": make_leather_piece(),
        "item/hide_scrap.png": make_hide_scrap(),
        "item/tallow_wax.png": make_tallow_wax(),
        "item/pine_resin.png": make_pine_resin(),
        "item/raw_gourd.png": make_gourd(carved=False),
        "item/gourd_flask.png": make_gourd(carved=True),
        "block/workstation_wood.png": make_workstation_wood(4000, slab_top=False),
        "block/workstation_top.png": make_workstation_wood(4100, slab_top=True),
        "block/granite_rock.png": make_rock_block("granite"),
        "block/limestone_rock.png": make_rock_block("limestone"),
        "block/flint_rock.png": make_rock_block("flint"),
        "block/fallen_stick.png": make_fallen_stick_block(),
        "block/hearth_side.png": make_hearth_side_block(),
        "block/hearth_top.png": make_hearth_top_block("cold"),
        "block/hearth_embers.png": make_hearth_top_block("embers"),
        "block/hearth_lit.png": make_hearth_top_block("lit"),
        "block/wood_ash_pile.png": make_wood_ash_pile_block(),
    }
    for sp in ("oak", "birch", "spruce", "dark_oak"):
        tex[f"item/fresh_{sp}_log.png"] = make_fresh_log_item(sp)
        tex[f"item/{sp}_split_wood.png"] = make_split_wood_item(sp, dry=False)
        tex[f"item/{sp}_split_wood_dry.png"] = make_split_wood_item(sp, dry=True)
        tex[f"item/rough_{sp}_plank.png"] = make_rough_plank_item(sp, dry=False)
        tex[f"item/rough_{sp}_plank_dry.png"] = make_rough_plank_item(sp, dry=True)
        tex[f"block/notched_{sp}_inner.png"] = make_notched_inner_block(sp)
        tex[f"block/{sp}_trunk_branch.png"] = make_trunk_branch_block(sp)
        tex[f"block/{sp}_split_wood_wet.png"] = make_drying_wood_block(sp, dry=False, is_plank=False)
        tex[f"block/{sp}_split_wood_dry.png"] = make_drying_wood_block(sp, dry=True, is_plank=False)
        tex[f"block/rough_{sp}_plank_wet.png"] = make_drying_wood_block(sp, dry=False, is_plank=True)
        tex[f"block/rough_{sp}_plank_dry.png"] = make_drying_wood_block(sp, dry=True, is_plank=True)
    return tex


def write_all(textures: Dict[str, Canvas]) -> None:
    for rel, canvas in textures.items():
        path = TEX_ROOT / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(encode_png_rgba(canvas.w, canvas.h, canvas.px))
    print(f"Wrote {len(textures)} 32x32 textures under {TEX_ROOT.relative_to(ROOT)}")


def check_all(textures: Dict[str, Canvas]) -> int:
    failures = 0
    for rel, canvas in textures.items():
        path = TEX_ROOT / rel
        if not path.exists():
            print(f"MISSING: {path}")
            failures += 1
            continue
        w, h, actual = decode_png_rgba(path.read_bytes())
        if (w, h) != (32, 32) or actual != canvas.px:
            print(f"MISMATCH: {path}")
            failures += 1
        if rel.startswith("item/"):
            transparent = sum(1 for row in actual for p in row if p[3] == 0)
            opaque = w * h - transparent
            if transparent < 128 or opaque < 64:
                print(f"BAD SILHOUETTE: {path} (transparent={transparent}, opaque={opaque})")
                failures += 1
    if failures == 0:
        print(f"All {len(textures)} 32x32 textures verified OK.")
    return 1 if failures else 0


def write_preview(textures: Dict[str, Canvas], out_path: pathlib.Path) -> None:
    scale = 4
    tile = 32 * scale  # 128
    pad = 8
    cols = 7
    keys = list(textures.keys())
    rows_n = (len(keys) + cols - 1) // cols
    w = cols * tile + (cols + 1) * pad
    h = rows_n * tile + (rows_n + 1) * pad
    sheet = [[(34, 36, 42, 255) for _ in range(w)] for _ in range(h)]
    for idx, key in enumerate(keys):
        gx = idx % cols
        gy = idx // cols
        ox = pad + gx * (tile + pad)
        oy = pad + gy * (tile + pad)
        cv = textures[key]
        for py in range(32):
            for px in range(32):
                r, g, b, a = cv.px[py][px]
                for sy in range(scale):
                    for sx in range(scale):
                        cx = ox + px * scale + sx
                        cy = oy + py * scale + sy
                        if a == 0:
                            chk = ((px // 2 + py // 2) % 2) == 0
                            sheet[cy][cx] = (70, 72, 80, 255) if chk else (56, 58, 66, 255)
                        else:
                            sheet[cy][cx] = (r, g, b, 255)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(encode_png_rgba(w, h, sheet))
    print(f"Preview written to {out_path}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--preview", type=pathlib.Path, default=None)
    args = parser.parse_args()

    textures = build_all_textures()
    if args.preview is not None:
        write_preview(textures, args.preview)
    if args.check:
        return check_all(textures)
    write_all(textures)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
