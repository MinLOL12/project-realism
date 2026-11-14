#!/usr/bin/env python3
"""
Generates 16x16 RGBA Minecraft pixel-art textures for Project Realism (Stage Two).

Every item texture uses true alpha transparency (alpha=0 background) with crisp
hue-shifted outlines and directional top-left lighting so items read clearly in
the hotbar, inventory, and hand. Block textures provide rich mineral / bark grain
for the 3D ground-rock and fallen-stick models.

Usage:
    python3 tools/generate_textures.py
    python3 tools/generate_textures.py --check
"""

from __future__ import annotations

import argparse
import pathlib
import struct
import zlib
from typing import Dict, Iterable, List, Tuple

RGBA = Tuple[int, int, int, int]

ROOT = pathlib.Path(__file__).resolve().parent.parent
TEX_ROOT = ROOT / "src" / "main" / "resources" / "assets" / "project_realism" / "textures"


def encode_png_rgba(width: int, height: int, rows: List[List[RGBA]]) -> bytes:
    """Writes a deterministic, uncompressed-filter RGBA PNG using Python stdlib only."""
    assert len(rows) == height
    raw = bytearray()
    for row in rows:
        assert len(row) == width
        raw.append(0)  # filter type 0 (None)
        for r, g, b, a in row:
            raw.extend((r, g, b, a))

    def chunk(tag: bytes, data: bytes) -> bytes:
        crc = zlib.crc32(tag + data) & 0xFFFFFFFF
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", crc)

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)
    idat = zlib.compress(bytes(raw), level=9)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", idat)
        + chunk(b"IEND", b"")
    )


def decode_png_rgba(data: bytes) -> Tuple[int, int, List[List[RGBA]]]:
    """Minimal RGBA PNG decoder for validation/previewing."""
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
    assert ct == 6, f"Expected RGBA (ct=6), got ct={ct}"
    raw = zlib.decompress(bytes(idat))
    stride = width * 4
    rows: List[List[RGBA]] = []
    idx = 0
    for _y in range(height):
        flt = raw[idx]
        idx += 1
        assert flt == 0
        row_bytes = raw[idx : idx + stride]
        idx += stride
        rows.append([
            (row_bytes[x * 4], row_bytes[x * 4 + 1], row_bytes[x * 4 + 2], row_bytes[x * 4 + 3])
            for x in range(width)
        ])
    return width, height, rows


def from_grid(grid: Iterable[str], palette: Dict[str, RGBA]) -> List[List[RGBA]]:
    lines = [line for line in grid]
    assert len(lines) == 16, f"Expected 16 rows, got {len(lines)}"
    rows: List[List[RGBA]] = []
    for y, line in enumerate(lines):
        assert len(line) == 16, f"Row {y} expected length 16, got {len(line)}: {line!r}"
        rows.append([palette[ch] for ch in line])
    return rows


# ---------------------------------------------------------------------------
# Item sprites (16x16 RGBA, transparent background)
# ---------------------------------------------------------------------------

T: RGBA = (0, 0, 0, 0)

ITEM_SPECS: Dict[str, Tuple[List[str], Dict[str, RGBA]]] = {
    "item/granite_rock.png": (
        [
            "................",
            "................",
            ".....oooooo.....",
            "...ooHHHHHHoo...",
            "..oHHQMFMMQHoo..",
            ".oHHMMBMMMMSSo..",
            ".oHMQFMMBMMSSSo.",
            ".oMMBMMMMQMSSSo.",
            ".oSMMMMFMMSSSDoo",
            "..oSSMMSSSSSDDo.",
            "...ooSSSSSDDoo..",
            ".....ooooooo....",
            "................",
            "................",
            "................",
            "................",
        ],
        {
            ".": T,
            "o": (56, 36, 33, 255),    # dark warm outline
            "H": (214, 154, 139, 255), # sunlit pink feldspar
            "Q": (235, 206, 198, 255), # pale quartz glint
            "M": (176, 117, 106, 255), # salmon granite midtone
            "F": (198, 136, 121, 255), # warm feldspar fleck
            "B": (74, 58, 56, 255),    # dark biotite mica speck
            "S": (132, 84, 76, 255),   # shaded granite
            "D": (96, 60, 54, 255),    # deep shadow edge
        },
    ),
    "item/limestone_rock.png": (
        [
            "................",
            "................",
            "......ooooo.....",
            "....ooHHHHHoo...",
            "..ooHHLLLLMMMo..",
            ".oHHMMBBBBMMMSo.",
            ".oHMLLLLLLMMSSo.",
            ".oMMBBBBMMMSSSo.",
            "..oSMMMMMMSSSDDo",
            "..ooSSSSSSSDDDo.",
            "....ooDDDDDooo..",
            "......oooooo....",
            "................",
            "................",
            "................",
            "................",
        ],
        {
            ".": T,
            "o": (88, 82, 68, 255),    # weathered limestone outline
            "H": (238, 232, 214, 255), # bright chalky highlight
            "L": (222, 214, 194, 255), # pale sedimentary band
            "M": (198, 189, 168, 255), # warm limestone midtone
            "B": (166, 157, 138, 255), # bedding seam
            "S": (152, 143, 124, 255), # shaded buff
            "D": (122, 114, 98, 255),  # underside shadow
        },
    ),
    "item/flint_rock.png": (
        [
            "................",
            "................",
            ".......oooo.....",
            ".....ooGGGGo....",
            "...ooGCHHHMMo...",
            "..oGCHMMMMMSSo..",
            ".oGCCHMMMSSSDDo.",
            ".oCMMMMMMSSSDDo.",
            "..oSMMMMSSSDDDo.",
            "..ooSSSSSDDDoo..",
            "....ooSSDDDoo...",
            "......oooooo....",
            "................",
            "................",
            "................",
            "................",
        ],
        {
            ".": T,
            "o": (22, 24, 30, 255),    # obsidian-slate outline
            "G": (138, 148, 166, 255), # waxy conchoidal glint
            "H": (106, 114, 130, 255), # cool slate highlight
            "C": (172, 174, 168, 255), # chalky flint cortex rim
            "M": (72, 78, 90, 255),    # deep flint midtone
            "S": (50, 55, 65, 255),    # shaded flint
            "D": (34, 38, 46, 255),    # dark shadow
        },
    ),
    "item/sharp_stone.png": (
        [
            "................",
            "............oo..",
            ".........oooGGo.",
            "........oGHHHGo.",
            "......ooGHMMMGo.",
            ".....oGGHMMMSo..",
            "....oGHMMMMSSo..",
            "...oGHMMMSSSDDo.",
            "..oGHMMSSSDDDo..",
            "..oHMMSSSDDDo...",
            "..oMSSSDDDoo....",
            "...oSSDDoo......",
            "....oooo........",
            "................",
            "................",
            "................",
        ],
        {
            ".": T,
            "o": (30, 34, 40, 255),    # crisp dark outline
            "G": (214, 224, 234, 255), # honed razor edge glint
            "H": (166, 176, 188, 255), # flaked bevel highlight
            "M": (122, 130, 140, 255), # knapped stone face
            "S": (86, 92, 102, 255),   # shaded flake
            "D": (58, 63, 72, 255),    # thick heel shadow
        },
    ),
    "item/stone_axe_head.png": (
        [
            "................",
            ".........oooo...",
            ".......ooHHHGo..",
            ".....ooHHMMMGGo.",
            "..oooSSMMMMMGGo.",
            ".oSDDMMMMMMHGGGo",
            ".oSDDDMMMMMHGGo.",
            ".oSDDDMMMSSHGGo.",
            "..oooDDSSSSHGGo.",
            ".....ooSSSDGGoo.",
            ".......ooSSGoo..",
            ".........oooo...",
            "................",
            "................",
            "................",
            "................",
        ],
        {
            ".": T,
            "o": (34, 36, 40, 255),    # dark outline
            "G": (212, 220, 228, 255), # ground cutting arc
            "H": (164, 172, 182, 255), # beveled face
            "M": (124, 130, 138, 255), # stone body
            "S": (88, 92, 100, 255),   # rough knapped shadow
            "D": (62, 65, 72, 255),    # deep butt shadow
        },
    ),
    "item/plant_fiber.png": (
        [
            "................",
            "............oo..",
            ".....oo....oLLo.",
            "....oLLo..oLMMo.",
            "...oLMGo.oLMGo..",
            "..ooMGo..oMGo...",
            "..oLMMo.oMGo..oo",
            "...oMGo.oGDo.oLo",
            "..oLMGooGDo.oLMo",
            "..oGGDGGDo.oMGo.",
            "...oGDDGoooGDo..",
            "...ooDDDDGGDo...",
            "....oDDDDDDo....",
            ".....oooooo.....",
            "................",
            "................",
        ],
        {
            ".": T,
            "o": (38, 58, 22, 255),    # deep botanical outline
            "L": (182, 214, 104, 255), # sunlit grass tip
            "M": (136, 178, 68, 255),  # fresh fiber green
            "G": (96, 138, 44, 255),   # mid stem olive
            "D": (66, 98, 30, 255),    # shaded fiber base
        },
    ),
    "item/cordage.png": (
        [
            "................",
            "......oooooo....",
            "....ooHHHHMMo...",
            "...oHHMMBMMBBo..",
            "..oHMoo..ooMMBo.",
            "..oBMo....oHMBo.",
            "..oBMBooooBMBo..",
            "...oBMMHHMMBBo..",
            "...oBCHMMCCBBo..",
            "..oHMCCHMMCBBo..",
            "..oBMoCooCMMBo..",
            "..oBMBo..oBMBo..",
            "...oBBMMMMBBo...",
            "....ooBBBBoo....",
            "......oooo......",
            "................",
        ],
        {
            ".": T,
            "o": (68, 48, 22, 255),    # dark rope outline
            "H": (228, 194, 130, 255), # highlighted hemp strand
            "M": (192, 152, 90, 255),  # twisted cordage midtone
            "B": (140, 104, 54, 255),  # braided twist shadow
            "C": (240, 212, 154, 255), # cross-wrap tie highlight
        },
    ),
    "item/stone_knife.png": (
        [
            "................",
            ".............oo.",
            "...........ooGo.",
            ".........ooGHGo.",
            "........oGHMMGo.",
            ".......oGHMMSo..",
            "......oGHMSSo...",
            ".....oCCSSSSo...",
            "....oCccCooo....",
            "...oWWCco.......",
            "..oWwdWo........",
            ".oWwdWo.........",
            ".oWdWo..........",
            ".oWdo...........",
            "..oo............",
            "................",
        ],
        {
            ".": T,
            "o": (32, 28, 24, 255),    # dark outline
            "G": (216, 226, 236, 255), # sharp blade edge
            "H": (166, 176, 188, 255), # blade bevel
            "M": (122, 130, 140, 255), # knapped blade body
            "S": (82, 88, 98, 255),    # blade spine shadow
            "C": (212, 174, 110, 255), # cordage lashing highlight
            "c": (152, 114, 62, 255),  # cordage lashing shadow
            "W": (146, 102, 54, 255),  # stick handle light
            "w": (116, 78, 38, 255),   # stick handle mid
            "d": (84, 54, 24, 255),    # stick handle dark
        },
    ),
    "item/stone_axe.png": (
        [
            "................",
            ".....oooo.......",
            "...ooGGGGoo.....",
            "..oGHMMMMGGo....",
            ".oGHMMMMMMSSo...",
            ".oGMMMMMSSCCCo..",
            ".oGMMMMSSCccWWo.",
            "..oSSMSSCCWddo..",
            "...ooSSWWwdWo...",
            ".....oWWwdWo....",
            "....oWwdWo......",
            "...oWwdWo.......",
            "..oWwdWo........",
            ".oWwdWo.........",
            ".ooWdo..........",
            "...oo...........",
        ],
        {
            ".": T,
            "o": (30, 26, 24, 255),
            "G": (214, 222, 232, 255), # honed stone bit
            "H": (166, 174, 184, 255),
            "M": (122, 128, 138, 255),
            "S": (84, 88, 96, 255),
            "C": (212, 174, 110, 255), # cordage lashing
            "c": (152, 114, 62, 255),
            "W": (146, 102, 54, 255),  # wood shaft
            "w": (116, 78, 38, 255),
            "d": (84, 54, 24, 255),
        },
    ),
    "item/stone_pickaxe.png": (
        [
            "................",
            "......ooooo.....",
            "....ooGGHMMoo...",
            "...oGGHMMMSSCo..",
            "..oGHMMooSCCcWo.",
            "..oGMo...oCCWdo.",
            "...oo....oWWMMo.",
            "........oWwdMSo.",
            ".......oWwdWMSSo",
            "......oWwdWoMSSo",
            ".....oWwdWo.oGGo",
            "....oWwdWo...ooo",
            "...oWwdWo.......",
            "..oWwdWo........",
            "..ooWdo.........",
            "....oo..........",
        ],
        {
            ".": T,
            "o": (30, 26, 24, 255),
            "G": (214, 222, 232, 255), # chiseled stone tips
            "H": (166, 174, 184, 255),
            "M": (122, 128, 138, 255),
            "S": (84, 88, 96, 255),
            "C": (212, 174, 110, 255), # bound joint cordage
            "c": (152, 114, 62, 255),
            "W": (146, 102, 54, 255),  # wood handle
            "w": (116, 78, 38, 255),
            "d": (84, 54, 24, 255),
        },
    ),
}

# ---------------------------------------------------------------------------
# Block surface textures (16x16 opaque RGBA, detailed mineral & bark textures)
# ---------------------------------------------------------------------------

BLOCK_SPECS: Dict[str, Tuple[List[str], Dict[str, RGBA]]] = {
    "block/granite_rock.png": (
        [
            "HHMMQFMMBMHHMQMM",
            "HMMFFMMQQMMBMMMS",
            "MMBMMHHMMMFMMMSS",
            "QMMHMMMBMMMFQSSS",
            "MMHMMQMMFMMBMMSS",
            "FMMBMMHHMMQMMSSD",
            "MMMMFMMBMMMMSSSD",
            "HHQMMMMMMFMMSSDD",
            "HMMBMQMMMMBMSSSD",
            "MMFMMHHMMQMMSSDD",
            "BMMQMMMMFMMMSSSD",
            "MMHHMMBMMQMMSSDD",
            "QMMFMMMMMMBMSSSD",
            "MMBMMQMMFMMMSSSD",
            "SSMMSSMMSSSDSSDD",
            "SSSDDSSSDSSDDDDD",
        ],
        {
            "H": (212, 152, 138, 255),
            "Q": (228, 198, 190, 255),
            "M": (174, 118, 108, 255),
            "F": (192, 134, 120, 255),
            "B": (82, 64, 62, 255),
            "S": (138, 90, 82, 255),
            "D": (104, 66, 60, 255),
        },
    ),
    "block/limestone_rock.png": (
        [
            "HHLLLLMMMMLLLLHH",
            "HLLLLMMMMMMLLLLH",
            "BBBBMMBBBBBBBBMM",
            "MMLLLLMMMMLLLLMS",
            "LLLLMMMMLLLLMMSS",
            "MMBBBBBBBBBBBBSD",
            "HHLLLLMMMMLLLLMS",
            "LLLLMMMMLLLLMMSS",
            "BBBBBBBBMMBBBBSD",
            "MMLLLLMMMMLLLLMS",
            "LLLLMMMMLLLLMMSS",
            "MMBBBBMMBBBBBBSD",
            "LLLLMMMMLLLLMMSS",
            "SSSSMMMMSSSSSSDD",
            "SSSSDDDDSSSSSSDD",
            "SDDDDDDDSDDDDDDD",
        ],
        {
            "H": (236, 230, 212, 255),
            "L": (220, 212, 192, 255),
            "M": (196, 187, 166, 255),
            "B": (168, 159, 140, 255),
            "S": (152, 143, 124, 255),
            "D": (124, 116, 100, 255),
        },
    ),
    "block/flint_rock.png": (
        [
            "CCGGHHMMMMMSSSDD",
            "CGGHHMMMMMSSSDDD",
            "GGHHMMMMMSSSDDDD",
            "GHHMMMMGGHHSSSDD",
            "HHMMMMGHHMMSSSDD",
            "MMMMMGHHMMMSSSDD",
            "CMMMGGHMMMSSSDDD",
            "CCMMGHMMMSSSDDDD",
            "MMGGHHMMMSSSDDDD",
            "MGGHHMMMSSSDDDDD",
            "GGHHMMMSSSDDDDDD",
            "GHHMMMSSSDDCCDDD",
            "HHMMMSSSDDCCCCDD",
            "MMMSSSDDDCCDDDDD",
            "SSSDDDDDDDDDDDDD",
            "DDDDDDDDDDDDDDDD",
        ],
        {
            "C": (166, 168, 164, 255),
            "G": (130, 140, 158, 255),
            "H": (98, 106, 122, 255),
            "M": (66, 72, 84, 255),
            "S": (48, 53, 63, 255),
            "D": (32, 36, 44, 255),
        },
    ),
    "block/fallen_stick.png": (
        [
            "DDDDDDDDDDDDDDDD",
            "MMWWMMWWWWMMWWMM",
            "WWWWLLWWWWWWLLWW",
            "DDMMWWDDMMWWDDMM",
            "WWMMWWKKWWWWMMWW",
            "LLWWWWKKLLWWWWMM",
            "MMWWMMWWMMWWMMWW",
            "DDDDMMDDDDWWDDDD",
            "WWWWMMWWWWWWLLWW",
            "MMWWLLWWMMWWMMWW",
            "DDMMWWDDKKMMWWDD",
            "WWMMWWMMKKWWWWMM",
            "LLWWWWMMWWLLWWWW",
            "MMWWDDWWMMWWDDMM",
            "DDDDMMDDDDMMDDDD",
            "DDDDDDDDDDDDDDDD",
        ],
        {
            "L": (158, 112, 62, 255),  # sunlit bark ridge
            "W": (128, 86, 44, 255),   # warm oak bark
            "M": (102, 68, 34, 255),   # mid bark
            "D": (72, 46, 22, 255),    # deep bark groove
            "K": (54, 34, 16, 255),    # wood knot
        },
    ),
}


def generate_all() -> Dict[str, List[List[RGBA]]]:
    images: Dict[str, List[List[RGBA]]] = {}
    for rel, (grid, palette) in {**ITEM_SPECS, **BLOCK_SPECS}.items():
        images[rel] = from_grid(grid, palette)
    return images


def write_all(images: Dict[str, List[List[RGBA]]]) -> None:
    for rel, rows in images.items():
        out_path = TEX_ROOT / rel
        out_path.parent.mkdir(parents=True, exist_ok=True)
        out_path.write_bytes(encode_png_rgba(16, 16, rows))
        print(f"wrote {out_path.relative_to(ROOT)}")


def check_all(images: Dict[str, List[List[RGBA]]]) -> int:
    failures = 0
    for rel, expected_rows in images.items():
        path = TEX_ROOT / rel
        if not path.exists():
            print(f"MISSING: {path}")
            failures += 1
            continue
        w, h, actual_rows = decode_png_rgba(path.read_bytes())
        if (w, h) != (16, 16) or actual_rows != expected_rows:
            print(f"MISMATCH: {path}")
            failures += 1
        if rel.startswith("item/"):
            transparent = sum(1 for r in actual_rows for p in r if p[3] == 0)
            opaque = 256 - transparent
            if transparent < 32 or opaque < 20:
                print(f"BAD SILHOUETTE: {path} (transparent={transparent}, opaque={opaque})")
                failures += 1
    if failures == 0:
        print(f"All {len(images)} textures verified OK.")
    return 1 if failures else 0


def write_preview(images: Dict[str, List[List[RGBA]]], preview_path: pathlib.Path) -> None:
    """Renders an 8x nearest-neighbor contact sheet on a checkerboard for visual inspection."""
    scale = 8
    tile = 16 * scale  # 128
    pad = 12
    cols = 5
    keys = list(images.keys())
    num_rows = (len(keys) + cols - 1) // cols
    w = cols * tile + (cols + 1) * pad
    h = num_rows * tile + (num_rows + 1) * pad
    canvas: List[List[RGBA]] = [[(36, 38, 44, 255) for _ in range(w)] for _ in range(h)]

    for idx, key in enumerate(keys):
        gx = idx % cols
        gy = idx // cols
        ox = pad + gx * (tile + pad)
        oy = pad + gy * (tile + pad)
        rows = images[key]
        for py in range(16):
            for px in range(16):
                sr, sg, sb, sa = rows[py][px]
                for sy in range(scale):
                    for sx in range(scale):
                        cx = ox + px * scale + sx
                        cy = oy + py * scale + sy
                        if sa == 0:
                            chk = ((px + py) % 2) == 0
                            canvas[cy][cx] = (72, 74, 82, 255) if chk else (56, 58, 66, 255)
                        else:
                            canvas[cy][cx] = (sr, sg, sb, 255)

    preview_path.parent.mkdir(parents=True, exist_ok=True)
    preview_path.write_bytes(encode_png_rgba(w, h, canvas))
    print(f"preview written to {preview_path}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="Verify textures match generator output")
    parser.add_argument("--preview", type=pathlib.Path, default=None, help="Write enlarged preview PNG")
    args = parser.parse_args()

    images = generate_all()
    if args.preview is not None:
        write_preview(images, args.preview)
    if args.check:
        return check_all(images)
    write_all(images)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
