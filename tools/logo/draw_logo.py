#!/usr/bin/env python3
"""Draws the PdaNext mark and writes every raster asset that uses it.

The mark: a "P" whose bowl is a phone (with a small router node on its screen) and whose stem
is a USB cable ending in a plug, white on a violet -> orange squircle. Below 32 px the screen
details are dropped and strokes thickened so it stays readable in the taskbar and tray.

    python3 tools/logo/draw_logo.py      (needs Pillow)

The Android vector drawables (android/app/src/main/res/drawable/ic_launcher.xml, ic_stat_tether.xml)
use the same 256-unit geometry.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
C1, C2 = (91, 33, 182), (249, 115, 22)  # #5B21B6 -> #F97316


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient(w, h):
    g = Image.new("RGB", (w, h))
    px = g.load()
    for y in range(h):
        for x in range(w):
            px[x, y] = lerp(C1, C2, min(1.0, (x * 0.45 * h / w + y * 0.55) / h))
    return g


def mark(draw, k, ox=0.0, oy=0.0, simple=False, color=(255, 255, 255, 255)):
    def r(x0, y0, x1, y1):
        return [ox + x0 * k, oy + y0 * k, ox + x1 * k, oy + y1 * k]

    st = int((26 if simple else 18) * k)
    draw.rounded_rectangle(r(86, 40, 182, 152), radius=int(22 * k), outline=color, width=st)
    if not simple:
        draw.ellipse(r(127, 54, 141, 68), fill=color)
        draw.ellipse(r(124, 104, 144, 124), fill=color)
        draw.arc(r(108, 88, 160, 140), 205, 335, fill=color, width=int(9 * k))
    stem_w = 26 if simple else 18
    draw.rectangle(r(86, 120, 86 + stem_w, 196), fill=color)
    draw.rounded_rectangle(r(74, 190, 116 + (6 if simple else 0), 220), radius=int(6 * k), fill=color)
    if not simple:
        draw.rectangle(r(82, 220, 90, 232), fill=color)
        draw.rectangle(r(100, 220, 108, 232), fill=color)


def icon(size):
    s = max(size, 64) * 4
    k = s / 256
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=int(58 * k), fill=255)
    img.paste(gradient(s, s), (0, 0), mask)
    mark(ImageDraw.Draw(img), k, simple=size < 32)
    return img.resize((size, size), Image.LANCZOS)


def font(px):
    for name in ("DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "arialbd.ttf"):
        try:
            return ImageFont.truetype(name, px)
        except OSError:
            pass
    return ImageFont.load_default()


def main():
    sizes = [16, 20, 24, 32, 40, 48, 64, 128, 256]
    icons = [icon(n) for n in sizes]
    icons[-1].save(ROOT / "windows/assets/app.ico", sizes=[(n, n) for n in sizes], append_images=icons[:-1])
    icon(512).save(ROOT / "docs/logo.png")

    # MSI dialog artwork (WixUI): banner 493x58 and the side image of the welcome dialog 493x312.
    banner = Image.new("RGB", (493, 58), (255, 255, 255))
    banner.paste(icon(44), (436, 7), icon(44))
    banner.save(ROOT / "windows/installer/banner.bmp")

    dialog = Image.new("RGB", (493, 312), (255, 255, 255))
    side = gradient(164, 312)
    dialog.paste(side, (0, 0))
    d = ImageDraw.Draw(dialog)
    mark(d, 0.42, ox=28, oy=70)
    d.text((22, 250), "PdaNext", fill=(255, 255, 255), font=font(26))
    dialog.save(ROOT / "windows/installer/dialog.bmp")
    print("wrote app.ico, docs/logo.png, installer bitmaps")


if __name__ == "__main__":
    main()
