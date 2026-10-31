# -*- coding: utf-8 -*-
"""概念变体与上色测试：B2 / B3 / A2，teal 底 + 白 + 琥珀箭尖。"""
import os
from concepts import (U, cells_to_path, rot180, head, bar, svg,
                      TEAL, AMBER, WHITE, HERE, OUT)


def b3_cells():
    """「阶梯链路」加粗版：带 5 格厚、台阶 4 格宽、头基 7 格（出带 1 格）。"""
    cells = set()
    bar(cells, 3, 9, 10, 14)            # 底带 cols3..9 rows10..14
    head(cells, 3, 12, (7, 5, 3, 1), step=-1)   # 左箭头 cols3..0
    bar(cells, 6, 12, 1, 5)             # 顶带 cols6..12 rows1..5
    head(cells, 12, 3, (7, 5, 3, 1), step=1)    # 右箭头 cols12..15
    bar(cells, 6, 9, 1, 14)             # 台阶 cols6..9 rows1..14
    return cells


def emit(name, groups):
    """groups: [(cells_or_paths, fill)] —— cells 元组自动转 pathData。"""
    paths = []
    for item, fill in groups:
        if isinstance(item, set):
            item = cells_to_path(item)
        paths.append((item, fill))
    svg(paths, os.path.join(OUT, name))


if __name__ == "__main__":
    from concepts import concept_a2, concept_b
    # 从 concepts 拿 B2 的格子：直接重建（头 5→3→1）
    b2 = set()
    bar(b2, 2, 8, 10, 12)
    head(b2, 2, 11, (5, 3, 1), step=-1)
    bar(b2, 7, 13, 3, 5)
    head(b2, 13, 4, (5, 3, 1), step=1)
    bar(b2, 7, 8, 3, 12)

    a2 = concept_a2()[0][0]  # pathData（单色）

    # B2：白体 + 琥珀双箭头
    b2_body = {(c, r) for c in range(2, 9) for r in range(10, 13)} | \
              {(c, r) for c in range(7, 9) for r in range(3, 13)} | \
              {(c, r) for c in range(7, 14) for r in range(3, 6)}
    b2_head_l = head(set(), 2, 11, (5, 3, 1), step=-1)
    b2_head_r = head(set(), 13, 4, (5, 3, 1), step=1)
    emit("v-b2-teal.svg", [(b2_body, WHITE), (b2_head_l | b2_head_r, AMBER)])

    # B3：白体 + 琥珀双箭头
    b3 = b3_cells()
    b3_body = b3 - head(set(), 3, 12, (7, 5, 3, 1), step=-1) - \
        head(set(), 12, 3, (7, 5, 3, 1), step=1)
    emit("v-b3-teal.svg", [(b3_body, WHITE),
                           (head(set(), 3, 12, (7, 5, 3, 1), step=-1) |
                            head(set(), 12, 3, (7, 5, 3, 1), step=1), AMBER)])

    # A2：上箭头白、下箭头琥珀（180° 对称的两半）
    a2_top = set()
    bar(a2_top, 1, 12, 3, 5)
    head(a2_top, 13, 4, (5, 3, 1), step=1)
    a2_bot = rot180(a2_top)
    emit("v-a2-teal.svg", [(a2_top, WHITE), (a2_bot, AMBER)])

    print("colored variants written")
