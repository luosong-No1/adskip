#!/usr/bin/env python3
"""生成传统启动图标 PNG。

Android 8.0 (API 26) 以下不支持自适应图标，必须提供位图，
否则在 API 24/25 的设备上图标会显示成系统默认灰块。

用法：
    python tools/make_icons.py

依赖：Pillow（pip install pillow）
生成物会写入 app/src/main/res/mipmap-<density>/ 下的
ic_launcher.png 与 ic_launcher_round.png。
"""

from __future__ import annotations

import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES_DIR = os.path.join(ROOT, "app", "src", "main", "res")

DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

BRAND = (30, 111, 255, 255)
ACCENT = (255, 216, 74, 255)
WHITE = (255, 255, 255, 255)

# 超采样倍数：先画大图再缩小，得到平滑边缘
SUPERSAMPLE = 8

# 设计稿使用 108x108 的画布，与 res/drawable/ic_launcher_foreground.xml 一致
CANVAS = 108.0


def draw_icon(size: int, round_icon: bool) -> Image.Image:
    """按 108 单位设计稿绘制图标，再缩放到目标像素尺寸。"""
    big = size * SUPERSAMPLE
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    if round_icon:
        draw.ellipse((0, 0, big - 1, big - 1), fill=BRAND)
    else:
        draw.rounded_rectangle(
            (0, 0, big - 1, big - 1), radius=int(big * 0.22), fill=BRAND
        )

    unit = big / CANVAS

    def p(x: float, y: float) -> tuple[float, float]:
        return (x * unit, y * unit)

    # ⏭ 形状：两个三角形 + 一根竖条
    draw.polygon([p(34, 36), p(50, 54), p(34, 72)], fill=WHITE)
    draw.polygon([p(54, 36), p(70, 54), p(54, 72)], fill=WHITE)
    draw.rectangle([p(73, 36), p(78, 72)], fill=WHITE)

    # 黄色斜杠：表示「拦截 / 去掉」
    draw.line([p(30, 78), p(78, 30)], fill=ACCENT, width=max(1, int(6 * unit)))

    return img.resize((size, size), Image.LANCZOS)


def main() -> None:
    for density, size in DENSITIES.items():
        target_dir = os.path.join(RES_DIR, f"mipmap-{density}")
        os.makedirs(target_dir, exist_ok=True)

        for name, round_icon in (("ic_launcher", False), ("ic_launcher_round", True)):
            icon = draw_icon(size, round_icon)
            path = os.path.join(target_dir, f"{name}.png")
            icon.save(path, "PNG", optimize=True)
            print(f"写入 {os.path.relpath(path, ROOT)}  ({size}x{size})")


if __name__ == "__main__":
    main()
