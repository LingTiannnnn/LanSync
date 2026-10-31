# -*- coding: utf-8 -*-
"""上色策略对比：B3 全白 / 琥珀尖 / 琥珀台阶，A2 双色；合成对比表。"""
import os
import subprocess
from PIL import Image, ImageDraw, ImageFont
from concepts import cells_to_path, rot180, head, bar, svg, TEAL, AMBER, WHITE, OUT, HERE
from variants import b3_cells, emit

RENDERS = os.path.join(HERE, "renders")
RENDERER = r"C:\Users\LingTian\.zcode\skills\logo-design\scripts\render_png.py"


def head_cols(widths, col0, row_center, step):
    """head 的 {col: rows}，便于按列拆色。"""
    cols = {}
    for i, w in enumerate(widths):
        r0 = row_center - w // 2
        cols[col0 + step * i] = set(range(r0, r0 + w))
    return cols


def cells_of(cols):
    return {(c, r) for c, rs in cols.items() for r in rs}


def b3_parts():
    b3 = b3_cells()
    hl = head_cols((7, 5, 3, 1), 3, 12, -1)
    hr = head_cols((7, 5, 3, 1), 12, 3, 1)
    body = b3 - cells_of(hl) - cells_of(hr)
    return body, hl, hr


if __name__ == "__main__":
    body, hl, hr = b3_parts()
    full = body | cells_of(hl) | cells_of(hr)
    riser = {(c, r) for c in range(6, 10) for r in range(1, 15)}
    tip_l = {(c, r) for c in (1, 2) for r in hl[c]}
    tip_r = {(c, r) for c in (14, 13) for r in hr[c]}

    emit("c-b3-white.svg", [(full, WHITE)])
    emit("c-b3-tip.svg", [(body, WHITE), (tip_l | tip_r, AMBER)])
    emit("c-b3-riser.svg", [(full, WHITE), (riser, AMBER)])
    emit("c-b3-riser-tip.svg", [(body, WHITE), (riser | tip_l | tip_r, AMBER)])

    names = ["c-b3-white", "c-b3-tip", "c-b3-riser", "c-b3-riser-tip"]
    for n in names:
        subprocess.run(["python", RENDERER, os.path.join(OUT, f"{n}.svg"),
                        "--out-dir", RENDERS, "--size", "512", "--bg", TEAL],
                       check=True, capture_output=True)

    tile, pad, label_h = 192, 24, 30
    W = pad * 2 + (tile + 24) * len(names)
    H = pad + label_h + tile + 16 + 96 + 16 + 48 + pad
    sheet = Image.new("RGB", (W, H), "#0E1416")
    draw = ImageDraw.Draw(sheet)
    font = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 14)
    labels = ["全白", "琥珀尖", "琥珀台阶", "台阶+尖"]
    for i, n in enumerate(names):
        img = Image.open(os.path.join(RENDERS, f"{n}.png")).convert("RGBA")
        x0 = pad + i * (tile + 24)
        for size, dy in ((tile, 0), (96, tile + 16), (48, tile + 16 + 96 + 16)):
            im = img.resize((size, size), Image.LANCZOS)
            sheet.paste(im, (x0 + (tile - size) // 2, pad + label_h + dy), im)
        draw.text((x0 + tile // 2, pad + 4), labels[i], fill="#97F0EB",
                  font=font, anchor="ma")
    out = os.path.join(RENDERS, "color-compare.png")
    sheet.save(out)
    print("sheet:", out)
