#!/usr/bin/env python3
"""
export_parcels.py -- turn build_grid.py's parcels_c8.csv into the compact
binary the RuneLite plugin ships as a resource, plus the tier table JSON
that both the plugin and the server seed from.

    python export_parcels.py ../Grounddumper/lulc_out/grid/parcels_c8.csv \
                             -o src/main/resources/com/gielinordeeds

The plugin needs to answer one question, thousands of times per second, with
no network call: "what tier and price is the parcel under this WorldPoint?"
So the whole grid is a flat array indexed by (py * cols + px), 3 bytes each.

Layout (little-endian):
    magic   4s   "GDP1"
    cols    u16  400
    rows    u16  288
    cell    u8   8      game tiles per parcel edge
    min_x   u16  896    game-coord bounds of the grid, from meta.json
    max_y   u16  4288
    then cols*rows records of:
        tier  u8   index into TIERS below
        price u16  the per-parcel price (base tier price x commitment mult)

rps is NOT stored: build_grid.price_rps() scales price and rps by the same
multiplier, so rps == tier_rps * (price / tier_price) exactly. Deriving it
saves 2 bytes per parcel and makes it impossible for the two to drift apart.
"""
import argparse, csv, json, struct
from pathlib import Path

# MUST stay in the same order as TIERS in build_grid.py -- the index is the
# on-disk tier byte and the server's tier code. Appending is safe; reordering
# silently reclassifies every parcel already claimed in the database.
TIERS = [
    ("capital",    "Capital",     800, 0.2250, "#F0784E", True),
    ("harbour",    "Harbour",     500, 0.1350, "#3FB8AF", True),
    ("township",   "Township",    400, 0.1050, "#9B7BF5", True),
    ("outskirts",  "Outskirts",   220, 0.0560, "#C08FB0", True),
    ("coast",      "Coast",       200, 0.0525, "#4FA3C7", True),
    ("farmland",   "Farmland",    150, 0.0375, "#8FBF6B", True),
    ("woodland",   "Woodland",    120, 0.0300, "#3F7F3A", True),
    ("jungle",     "Jungle",      130, 0.0330, "#1C6B40", True),
    ("savannah",   "Savannah",     90, 0.0225, "#CBBF5C", True),
    ("swamp",      "Swamp",        80, 0.0210, "#6D8F7C", True),
    ("desert",     "Desert",       70, 0.0182, "#E7D3A1", True),
    ("highland",   "Highland",     60, 0.0165, "#A9A396", True),
    ("tundra",     "Tundra",       55, 0.0150, "#EDF3F7", True),
    ("volcanic",   "Volcanic",     45, 0.0158, "#CF3D1A", True),
    ("wasteland",  "Wasteland",    40, 0.0148, "#6A5046", True),
    ("water",      "Open water",   50, 0.0090, "#4A7FA5", False),
    ("offmap",     "Unsurveyed",    0, 0.0,    "#2A2E38", False),
]
TIER_IX = {t[0]: i for i, t in enumerate(TIERS)}

MAGIC = b"GDP1"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", type=Path)
    ap.add_argument("-o", "--outdir", type=Path, required=True)
    ap.add_argument("--cell", type=int, default=8)
    ap.add_argument("--lulc", type=Path, default=None,
                    help="lulc_final.tif; also emits lulc.bin so the plugin can "
                         "name the land cover class of a tile, not just the "
                         "district tier of its parcel")
    args = ap.parse_args()
    args.outdir.mkdir(parents=True, exist_ok=True)

    rows_ = list(csv.DictReader(args.csv.open(newline="", encoding="utf-8")))
    cols = max(int(r["px"]) for r in rows_) + 1
    rows = max(int(r["py"]) for r in rows_) + 1
    if cols * rows != len(rows_):
        raise SystemExit(f"grid is not dense: {cols}x{rows} != {len(rows_)} parcels")

    min_x = min(int(r["x_min"]) for r in rows_)
    max_y = max(int(r["y_max"]) for r in rows_)

    # flat[py * cols + px] -- matches ParcelGrid.indexOf() in the plugin
    flat = bytearray(cols * rows * 3)
    counts = {}
    for r in rows_:
        px, py = int(r["px"]), int(r["py"])
        tier = r["tier"]
        if tier not in TIER_IX:
            raise SystemExit(f"unknown tier {tier!r} -- TIERS is out of sync with build_grid.py")
        ti = TIER_IX[tier]
        price = int(r["price"])
        if price > 0xFFFF:
            raise SystemExit(f"price {price} overflows u16 at parcel {r['pid']}")
        off = (py * cols + px) * 3
        flat[off] = ti
        struct.pack_into("<H", flat, off + 1, price)
        counts[tier] = counts.get(tier, 0) + 1

    header = struct.pack("<4sHHBHH", MAGIC, cols, rows, args.cell, min_x, max_y)
    binpath = args.outdir / "parcels.bin"
    binpath.write_bytes(header + bytes(flat))

    tiers = [
        {"code": i, "key": k, "name": n, "price": p, "rps": y, "color": c, "buyable": b}
        for i, (k, n, p, y, c, b) in enumerate(TIERS)
    ]
    (args.outdir / "tiers.json").write_text(
        json.dumps(tiers, indent=2), encoding="utf-8")

    buyable = sum(v for k, v in counts.items() if TIERS[TIER_IX[k]][5])
    print(f"{binpath}  {binpath.stat().st_size:,} bytes")
    print(f"  grid    {cols} x {rows} @ {args.cell} game tiles/parcel")
    print(f"  origin  min_x={min_x} max_y={max_y}")
    print(f"  parcels {len(rows_):,} total, {buyable:,} buyable")

    if args.lulc:
        export_lulc(args.lulc, args.outdir)


def export_lulc(tif: Path, outdir: Path) -> None:
    """Ship the land cover raster itself, deflated.

    The plugin already carries district tiers, which are a game concept derived
    from this. But someone correcting the map is correcting the land cover, and
    being shown "Woodland" (a tier) while the buttons offer "Forest" (a class)
    is just confusing. So it carries both, and the flag readout speaks the same
    language as the thing being fixed.

    7.37 MB raw, 156 KB deflated. The plugin inflates it lazily, so a player who
    never touches the map tools never pays for it.
    """
    import zlib

    import rasterio

    with rasterio.open(tif) as ds:
        arr = ds.read(1)
        t = ds.transform
    h, w = arr.shape
    body = zlib.compress(arr.tobytes(), 9)
    header = struct.pack("<4sHHHHI", b"GDL1", w, h, int(t.c), int(t.f), len(body))
    dest = outdir / "lulc.bin"
    dest.write_bytes(header + body)
    print(f"{dest}  {dest.stat().st_size:,} bytes  ({w}x{h} tiles, deflated)")


if __name__ == "__main__":
    main()
