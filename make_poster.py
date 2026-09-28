#!/usr/bin/env python3
"""竖版 3:4 旅行摄影海报：上半原图，下半米色纸面 + Risograph 风插画 + 文字。"""
import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageFilter

SRC = "VCG211557430940.jpg"
OUT = "poster.png"
W, H = 3000, 4000          # 3:4
HALF = H // 2              # 2000

TITLE = "SPRING EXPRESS"
KEYWORDS = "rapeseed / railway / spring"

PAPER = (246, 239, 227)    # warm ivory / cream

photo = Image.open(SRC).convert("RGB")
pw, ph = photo.size        # 8616 x 4308

# ---------- 上半：原照片裁成 3:2，像素级保留 ----------
target_ratio = W / HALF    # 1.5
crop_h = ph
crop_w = int(crop_h * target_ratio)          # 6462
# 主体(列车+高架曲线)偏画面中右，裁剪窗中心放在 0.55 宽度处
cx = int(pw * 0.55)
x0 = max(0, min(pw - crop_w, cx - crop_w // 2))
top = photo.crop((x0, 0, x0 + crop_w, crop_h)).resize((W, HALF), Image.LANCZOS)

canvas = Image.new("RGB", (W, H), PAPER)
canvas.paste(top, (0, 0))

# ---------- 插画：从照片提取主体区域，固定 4 色手工调色板 + 抖动 + 噪点 ----------
# 取列车、高架桥与油菜花田（相对坐标，基于整图）
ix0, iy0, ix1, iy1 = 0.18, 0.14, 0.80, 0.99
illus_src = photo.crop((int(pw*ix0), int(ph*iy0), int(pw*ix1), int(ph*iy1)))

small_w = 420                                   # 先缩到很小再做像素级处理
small_h = int(illus_src.height * small_w / illus_src.width)
small = illus_src.resize((small_w, small_h), Image.LANCZOS)

# 直接从原图采样 4 个色彩记忆点：天空 / 列车蓝 / 油菜花黄 / 桥体混凝土
def sample(rx, ry, r=6):
    x, y = int(pw*rx), int(ph*ry)
    a = np.array(photo.crop((x-r, y-r, x+r, y+r))).reshape(-1, 3).mean(axis=0)
    return tuple(int(v) for v in a)

sw, sh = small.size
palette_src = [
    sample(0.30, 0.05),   # 天空
    sample(0.73, 0.27),   # 列车蓝
    sample(0.40, 0.93),   # 油菜花黄
    sample(0.30, 0.55),   # 桥体混凝土象牙灰
]

def mute(c):
    r, g, b = c
    lum = 0.299*r + 0.587*g + 0.114*b
    m = 0.06
    return (int((r*0.78 + lum*0.22)*(1-m) + PAPER[0]*m),
            int((g*0.78 + lum*0.22)*(1-m) + PAPER[1]*m),
            int((b*0.78 + lum*0.22)*(1-m) + PAPER[2]*m))

palette = [mute(c) for c in palette_src]
print("palette:", palette)

# 用固定调色板量化（带抖动），确保蓝与黄进入最终配色
pal_img = Image.new("P", (1, 1))
pal_img.putpalette([v for c in palette for v in c] + [0]*(768 - len(palette)*3))
q = small.quantize(palette=pal_img, dither=Image.FLOYDSTEINBERG)
rgb = np.array(q.convert("RGB")).astype(np.int16)

# 轻微套色错位（riso misregistration）：把蓝色通道平移 1px
rgb[..., 2] = np.roll(rgb[..., 2], 1, axis=1)

# 低保真噪点颗粒
noise = np.random.default_rng(7).normal(0, 9, rgb.shape)
grainy = np.clip(rgb + noise, 0, 255).astype(np.uint8)
illus = Image.fromarray(grainy)

# 放大到目标尺寸（最近邻，保留网点颗粒感）
# 插画高度 ≈ 上半部分高度的 0.42（在 1/3 ~ 1/2 之间）
illus_h = int(HALF * 0.42)
illus_w = int(illus.width * illus_h / illus.height)
if illus_w > int(W * 0.72):
    illus_w = int(W * 0.72)
    illus_h = int(illus.height * illus_w / illus.width)
illus = illus.resize((illus_w, illus_h), Image.NEAREST)

# 底部区域中央偏上放置
ix = (W - illus_w) // 2
iy = HALF + int(HALF * 0.13)
canvas.paste(illus, (ix, iy))

# ---------- 文字 ----------
draw = ImageDraw.Draw(canvas)
font_title = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 64)
font_kw = ImageFont.truetype("/System/Library/Fonts/Menlo.ttc", 44)
ink = (74, 68, 60)   # 深灰墨

def draw_spaced(y, text, font, spacing, fill):
    widths = [draw.textlength(ch, font=font) for ch in text]
    total = sum(widths) + spacing * (len(text) - 1)
    x = (W - total) / 2
    for ch, w in zip(text, widths):
        draw.text((x, y), ch, font=font, fill=fill)
        x += w + spacing

y_title = iy + illus_h + int(HALF * 0.10)
draw_spaced(y_title, TITLE, font_title, 14, ink)
y_kw = y_title + 110
draw_spaced(y_kw, KEYWORDS, font_kw, 6, (140, 130, 118))

canvas.save(OUT)
print("saved", OUT, canvas.size)
