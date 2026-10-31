# -*- coding: utf-8 -*-
"""LanSync 图标概念几何（v4，像素/棱角方向）。

256 单位设计画布。像素概念建立在 16×16 网格上（格距 U=16），
所有形状都是整格矩形 —— 天然棱角分明，且任意尺寸下渲染都干净。

v4 修订：
- 箭头按 Material `arrow_right_alt` 比例重做：杆 3 格粗，头基 5 或 7 格
  （≈2× 杆高）、头长 3~4 格，直接收尖 —— v3 的菱形头（最宽 7、离杆远）
  读作哑铃。
- 概念 C 改水平朝向：45° 对角在圆形安全区内吃亏（外接圆利用率低）。
"""
import math
import os

U = 16  # 像素格距（256 画布 / 16 格）

# 品牌色（与 app/src/main/java/com/lansync/app/ui/theme/Color.kt 同源）
TEAL = "#00696B"      # TealPrimary40，launcher 底色
AMBER = "#F4E1A6"     # Amber90（可更新语义）
WHITE = "#FFFFFF"

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "concepts")


def rot(x, y, deg=0.0, cx=128.0, cy=128.0):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    dx, dy = x - cx, y - cy
    return (cx + dx * c - dy * s, cy + dx * s + dy * c)


def fmt(v):
    s = f"{v:.2f}".rstrip("0").rstrip(".")
    return s if s else "0"


def cells_to_path(cells):
    rows = {}
    for c, r in cells:
        rows.setdefault(r, []).append(c)
    d = []
    for r in sorted(rows):
        cols = sorted(rows[r])
        start = prev = None
        for c in cols + [None]:
            if c is not None and start is None:
                start = prev = c
            elif c is not None and c == prev + 1:
                prev = c
            elif start is not None:
                x, y, w = start * U, r * U, (prev - start + 1) * U
                d.append(f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{U}h-{fmt(w)}z")
                start = prev = None
    return "".join(d)


def rot180(cells):
    return {(15 - c, 15 - r) for c, r in cells}


def head(cells, col0, row_center, widths, step=1):
    """箭头：从 col0 起按 widths 逐列取宽（向右 step=1 / 向左 step=-1）。"""
    for i, w in enumerate(widths):
        r0 = row_center - w // 2
        for r in range(r0, r0 + w):
            cells.add((col0 + step * i, r))
    return cells


def bar(cells, c0, c1, r0, r1):
    """实心条：cols c0..c1 × rows r0..r1。"""
    for c in range(c0, c1 + 1):
        for r in range(r0, r1 + 1):
            cells.add((c, r))
    return cells


def svg(paths, out):
    body = "".join(f'  <path d="{d}" fill="{f}"/>\n' for d, f in paths)
    doc = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" '
           'width="256" height="256">\n' + body + "</svg>\n")
    with open(out, "w", encoding="utf-8") as f:
        f.write(doc)
    return doc


# ---------------------------------------------------------------- 概念 A
def _a(widths):
    top = bar(set(), 1, 15 - len(widths), 3, 5)   # 箭杆：rows3..5，右端留出头
    head(top, 16 - len(widths), 4, widths, step=1)
    cells = top | rot180(top)
    return cells


def concept_a():
    """「对穿」变体 1：头基 7 格（7→5→3→1，4 列）。"""
    return [(cells_to_path(_a((7, 5, 3, 1))), "#000000")]


def concept_a2():
    """「对穿」变体 2：头基 5 格（5→3→1，3 列）。"""
    return [(cells_to_path(_a((5, 3, 1))), "#000000")]


# ---------------------------------------------------------------- 概念 B
def _b(widths):
    cells = set()
    n = len(widths)
    bar(cells, n - 1, 8, 10, 12)        # 底带：左端接左箭头基座
    head(cells, n - 1, 11, widths, step=-1)   # 左箭头（向左收尖）
    bar(cells, 7, 16 - n, 3, 5)         # 顶带：右端接右箭头基座
    head(cells, 16 - n, 4, widths, step=1)    # 右箭头（向右收尖）
    bar(cells, 7, 8, 3, 12)             # 台阶 cols7..8（居中对称）
    return cells


def concept_b():
    """「阶梯链路」变体 1：头基 7 格。"""
    return [(cells_to_path(_b((7, 5, 3, 1))), "#000000")]


def concept_b2():
    """「阶梯链路」变体 2：头基 5 格。"""
    return [(cells_to_path(_b((5, 3, 1))), "#000000")]


# ---------------------------------------------------------------- 概念 C
def concept_c():
    """「棱面飞镖」：低多边形双棱面投掷镖，水平朝右 —— 快、有棱角。"""
    nose = (220.0, 128.0)
    top_t = (36.0, 80.0)
    notch = (104.0, 128.0)
    bot_t = (36.0, 176.0)
    upper = (f"M{fmt(nose[0])} {fmt(nose[1])}L{fmt(top_t[0])} {fmt(top_t[1])}"
             f"L{fmt(notch[0])} {fmt(notch[1])}Z")
    lower = (f"M{fmt(nose[0])} {fmt(nose[1])}L{fmt(notch[0])} {fmt(notch[1])}"
             f"L{fmt(bot_t[0])} {fmt(bot_t[1])}Z")
    return [(upper, "#000000"), (lower, "#00000055")]


if __name__ == "__main__":
    out = OUT
    os.makedirs(out, exist_ok=True)
    for name, fn in (("concept-a", concept_a),
                     ("concept-a2", concept_a2),
                     ("concept-b", concept_b),
                     ("concept-b2", concept_b2),
                     ("concept-c", concept_c)):
        svg(fn(), os.path.join(out, f"{name}.svg"))
        print("wrote", name)
