# -*- coding: utf-8 -*-
"""LanSync 图标定稿生成器（v2「方波 S」）。

运行 `python final.py` 一键产出：
- app/src/main/res/drawable/{ic_launcher_foreground,ic_launcher_monochrome,ic_notification}.xml
- design/icon/ 下的母版（SVG）与 Play Store 512、预览图

几何：256 单位设计画布上的 16×16 像素网格（格距 U=16），定义在
concepts.py/variants.py 的格子集合里。标志外接圆半径 rmax 实测后按
安全区半径 31dp（66dp 安全区圆留 2dp 余量）等比缩放映射到 108dp 自适应层。
"""
import os
import subprocess
import math
from PIL import Image, ImageDraw, ImageFont

from concepts import U, cells_to_path, svg, TEAL, AMBER, WHITE, HERE, OUT
from variants import b3_cells
from colors import head_cols, cells_of

RES = os.path.normpath(os.path.join(HERE, "..", "..", "app", "src", "main", "res"))
RENDERER = r"C:\Users\LingTian\.zcode\skills\logo-design\scripts\render_png.py"

HEAD_W = (7, 5, 3, 1)
SAFE_R_DP = 31.0      # 安全区圆半径 33dp，留 2dp 余量
NOTIFY_DP = 18.0      # 通知小图标中标志的视觉边长（24dp 画布内）


def final_cells():
    b3 = b3_cells()
    hl = head_cols(HEAD_W, 3, 12, -1)
    hr = head_cols(HEAD_W, 12, 3, 1)
    return b3 - cells_of(hl) - cells_of(hr) | cells_of(hl) | cells_of(hr)


def max_radius(cells):
    """标志上离画布中心 (128,128) 最远的角点距离（矩形最远点必在角上）。"""
    rmax = 0.0
    for c, r in cells:
        for x in (c * U, (c + 1) * U):
            for y in (r * U, (r + 1) * U):
                rmax = max(rmax, math.hypot(x - 128, y - 128))
    return rmax


def xml_vector(width, viewport, scale, translate, path, fill, comment):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{width}"
    android:height="{width}"
    android:viewportWidth="{viewport}"
    android:viewportHeight="{viewport}">
    <group android:scaleX="{scale:.6f}"
        android:scaleY="{scale:.6f}"
        android:translateX="{translate:.3f}"
        android:translateY="{translate:.3f}">
        <path
            android:fillColor="{fill}"
            android:pathData="{path}"/>
    </group>
</vector>
"""


def xml_vector_flat(path, fill, comment):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="{fill}"
        android:pathData="{path}"/>
</vector>
"""


def emit_android(path_data, k, tx, notify_k, notify_t, mark_dp):
    os.makedirs(os.path.join(RES, "drawable"), exist_ok=True)
    fg_comment = (
        f"LanSync 品牌图标（v2「方波 S」，像素语言）。\n"
        f"     几何与 design/icon/lansync-mark.svg 同源：256 单位设计画布上的 16×16 像素网格，\n"
        f"     经下面这一组 group 映射到 108dp 自适应层；标志 {mark_dp:.1f}dp 见方，\n"
        f"     外接圆半径 {SAFE_R_DP:.0f}dp，落在 66dp 安全区圆内（余量 {33 - SAFE_R_DP:.0f}dp）。"
    )
    with open(os.path.join(RES, "drawable", "ic_launcher_foreground.xml"), "w",
              encoding="utf-8", newline="\n") as f:
        f.write(xml_vector("108dp", 108, k, tx, path_data, "#FFFFFFFF", fg_comment))

    mono_comment = (
        "LanSync 主题图标单色层（Android 13+）。前景本就是单色剪影，\n"
        "     单色层取同一几何，由系统按壁纸着色。"
    )
    with open(os.path.join(RES, "drawable", "ic_launcher_monochrome.xml"), "w",
              encoding="utf-8", newline="\n") as f:
        f.write(xml_vector("108dp", 108, k, tx, path_data, "#FF000000", mono_comment))

    ntf_comment = (
        "LanSync 通知小图标。通知栏只接受单色 alpha 剪影，\n"
        "     与启动器前景同一几何（24dp 画布内标志 18dp 见方）。"
    )
    with open(os.path.join(RES, "drawable", "ic_notification.xml"), "w",
              encoding="utf-8", newline="\n") as f:
        f.write(xml_vector("24dp", 24, notify_k, notify_t, path_data, "#FFFFFFFF", ntf_comment))
    print("android drawables written")


def emit_masters(path_data, cells, k, tx):
    # 母版（256 设计画布）
    svg([(path_data, "#000000")], os.path.join(HERE, "lansync-mark.svg"))
    svg([(path_data, "#FFFFFF")], os.path.join(HERE, "lansync-mark-white.svg"))
    svg([(path_data, "#000000")], os.path.join(HERE, "lansync-monochrome.svg"))

    # 应用图标（teal 底 + 白标志，256 画布）
    appicon = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256" '
               'width="256" height="256">\n'
               f'  <rect width="256" height="256" fill="{TEAL}"/>\n'
               f'  <path d="{path_data}" fill="{WHITE}"/>\n</svg>\n')
    with open(os.path.join(HERE, "lansync-appicon.svg"), "w", encoding="utf-8") as f:
        f.write(appicon)

    # Play Store 512：512px = 108dp 层，同一映射放大
    s512 = k * 512 / 108
    t512 = 256 - 128 * s512
    play = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 512 512" '
            'width="512" height="512">\n'
            f'  <rect width="512" height="512" fill="{TEAL}"/>\n'
            f'  <g transform="translate({t512:.3f} {t512:.3f}) scale({s512:.6f})">'
            f'<path d="{path_data}" fill="{WHITE}"/></g>\n</svg>\n')
    with open(os.path.join(HERE, "lansync-appicon-512.svg"), "w", encoding="utf-8") as f:
        f.write(play)
    _render(os.path.join(HERE, "lansync-appicon-512.svg"),
            os.path.join(HERE, "lansync-play-512.png"), 512)
    white_png = os.path.join(HERE, "renders", "_mark-white-512.png")
    os.makedirs(os.path.dirname(white_png), exist_ok=True)
    _render(os.path.join(HERE, "lansync-mark-white.svg"), white_png, 512)
    print("masters written")
    return white_png


def _render(src, dst, size):
    subprocess.run(["python", RENDERER, src, "-o", dst, "--size", str(size)],
                   check=True, capture_output=True)


def circle_mask(im, radius_ratio=0.5):
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).ellipse([0, 0, im.size[0] - 1, im.size[1] - 1], fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def rounded_mask(im, ratio):
    mask = Image.new("L", im.size, 0)
    w = im.size[0]
    rad = int(w * ratio)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, w - 1], radius=rad, fill=255)
    out = Image.new("RGBA", im.size, (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def emit_preview(icon512, mark_white_png):
    """launcher 遮罩模拟 + 尺寸阶梯 + 主题图标/通知模拟。"""
    base = Image.open(icon512).convert("RGBA")
    # 剪影 alpha 取自白色标志母版（透明底）——appicon 渲染是整幅不透明图，无 alpha 可用
    mark = Image.open(mark_white_png).convert("RGBA")
    alpha = mark.split()[3]

    def paste(dst, im, x, y, size):
        dst.paste(im.resize((size, size), Image.LANCZOS), (x, y), im.resize((size, size), Image.LANCZOS))

    W, H = 1180, 560
    sheet = Image.new("RGB", (W, H), "#0E1416")
    d = ImageDraw.Draw(sheet)
    font = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 15)
    font_s = ImageFont.truetype(r"C:\Windows\Fonts\msyh.ttc", 13)

    # 第 1 行：遮罩模拟（圆形 / 圆角方 / 方形）120px
    d.text((30, 24), "遮罩模拟（自适应图标由桌面决定形状）", fill="#97F0EB", font=font)
    masks = [("circle", circle_mask(base)),
             ("squircle", rounded_mask(base, 0.36)),
             ("rounded", rounded_mask(base, 0.22))]
    x = 30
    for name, im in masks:
        paste(sheet, im, x, 54, 120)
        d.text((x + 60, 180), name, fill="#8A9391", font=font_s, anchor="ma")
        x += 140

    # 第 2 行：尺寸阶梯（圆形遮罩，模拟桌面 48dp 网格）
    d.text((30, 220), "尺寸阶梯（圆形遮罩）", fill="#97F0EB", font=font)
    circ = circle_mask(base)
    x = 30
    for size in (96, 72, 56, 48, 40, 32):
        paste(sheet, circ, x, 250, size)
        d.text((x + size // 2, 250 + size + 6), f"{size}px", fill="#8A9391", font=font_s, anchor="ma")
        x += size + 26

    # 第 3 行：主题图标模拟（Android 13 单色层着色）+ 通知剪影
    d.text((560, 220), "主题图标（单色层着色模拟）", fill="#97F0EB", font=font)
    tint_pairs = [("浅色壁纸", "#C8F8F5", "#00504D"), ("深色壁纸", "#003734", "#97F0EB")]
    x = 560
    for label, bg, fg in tint_pairs:
        tile = Image.new("RGBA", base.size, bg)
        fgim = Image.new("RGBA", base.size, fg)
        tile.paste(fgim, (0, 0), alpha)
        tile = circle_mask(tile)
        paste(sheet, tile, x, 250, 96)
        d.text((x + 48, 250 + 96 + 6), label, fill="#8A9391", font=font_s, anchor="ma")
        x += 116

    d.text((812, 220), "通知栏剪影（24dp）", fill="#97F0EB", font=font)
    bar = Image.new("RGBA", (350, 96), "#1B1B1B")
    ntf = Image.new("RGBA", base.size, (0, 0, 0, 0))
    white = Image.new("RGBA", base.size, "#FFFFFF")
    ntf.paste(white, (0, 0), alpha)
    ntf24 = ntf.resize((24, 24), Image.LANCZOS)
    bar.paste(ntf24, (24, 36), ntf24)
    d2 = ImageDraw.Draw(bar)
    d2.text((58, 40), "LanSync · 应用同步服务", fill="#E0E3E2", font=font_s)
    sheet.paste(bar.convert("RGB"), (812, 250))

    out = os.path.join(HERE, "lansync-icon-preview.png")
    sheet.save(out)
    print("preview:", out)


if __name__ == "__main__":
    cells = final_cells()
    path_data = cells_to_path(cells)
    rmax = max_radius(cells)
    k = SAFE_R_DP / rmax
    tx = 54 - 128 * k
    mark_dp = 256 * k
    notify_k = NOTIFY_DP / 256
    notify_t = (24 - NOTIFY_DP) / 2
    print(f"cells={len(cells)}  rmax={rmax:.2f}u  k={k:.6f}  mark={mark_dp:.1f}dp  tx={tx:.3f}")
    emit_android(path_data, k, tx, notify_k, notify_t, mark_dp)
    white_png = emit_masters(path_data, cells, k, tx)
    emit_preview(os.path.join(HERE, "lansync-play-512.png"), white_png)
