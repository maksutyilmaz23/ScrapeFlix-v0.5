"""Generates a premium gradient badge logo for ScrapeFlix and exports all
required Android launcher icon assets (legacy mipmaps + adaptive icon layers)."""
import math
import os
from PIL import Image, ImageDraw, ImageFilter

RES = "/home/claude/scrapeflix/app/src/main/res"

RED_LIGHT = (235, 32, 45)
RED_MID = (180, 8, 20)
RED_DARK = (30, 3, 6)
GOLD = (212, 175, 55)
BG_DARK = (10, 10, 10)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def radial_gradient_circle(size, inner, outer):
    """Radial gradient disc, light near upper-left (glossy) fading to dark edges."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    px = img.load()
    cx, cy = size * 0.38, size * 0.32  # light source offset (glossy highlight)
    max_r = math.hypot(max(cx, size - cx), max(cy, size - cy))
    for y in range(size):
        for x in range(size):
            dx, dy = x - size / 2, y - size / 2
            dist_center = math.hypot(dx, dy)
            if dist_center > size / 2:
                continue
            dl = math.hypot(x - cx, y - cy)
            t = min(1.0, dl / max_r)
            color = lerp(inner, outer, t)
            px[x, y] = (color[0], color[1], color[2], 255)
    return img


def circle_mask(size):
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse((0, 0, size - 1, size - 1), fill=255)
    return mask


def make_badge(size):
    """Composited premium badge: gradient disc + gold ring + white play glyph."""
    badge = radial_gradient_circle(size, RED_LIGHT, RED_DARK)
    mask = circle_mask(size)
    badge.putalpha(mask)

    # subtle darker rim for depth
    rim = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rd = ImageDraw.Draw(rim)
    rim_w = max(2, size // 48)
    rd.ellipse((rim_w, rim_w, size - rim_w, size - rim_w), outline=(0, 0, 0, 110), width=rim_w)
    badge = Image.alpha_composite(badge, rim)

    # gold outer ring (premium accent)
    ring = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    rgd = ImageDraw.Draw(ring)
    ring_w = max(3, size // 34)
    pad = ring_w // 2 + 1
    rgd.ellipse((pad, pad, size - pad, size - pad), outline=GOLD + (255,), width=ring_w)
    badge = Image.alpha_composite(badge, ring)

    # glossy highlight (soft light ellipse upper-left)
    gloss = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    gd = ImageDraw.Draw(gloss)
    gx0, gy0 = size * 0.16, size * 0.10
    gx1, gy1 = size * 0.62, size * 0.42
    gd.ellipse((gx0, gy0, gx1, gy1), fill=(255, 255, 255, 55))
    gloss = gloss.filter(ImageFilter.GaussianBlur(size / 22))
    badge = Image.alpha_composite(badge, gloss)

    # play triangle (white, slightly right-offset for optical centering)
    tri = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    td = ImageDraw.Draw(tri)
    t_h = size * 0.40
    t_w = size * 0.34
    cx, cy = size / 2 + size * 0.03, size / 2
    p1 = (cx - t_w / 2, cy - t_h / 2)
    p2 = (cx - t_w / 2, cy + t_h / 2)
    p3 = (cx + t_w * 0.62, cy)
    # soft shadow behind triangle
    shadow = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shadow)
    off = size * 0.012
    sd.polygon([(p1[0] + off, p1[1] + off), (p2[0] + off, p2[1] + off), (p3[0] + off, p3[1] + off)],
                fill=(0, 0, 0, 120))
    shadow = shadow.filter(ImageFilter.GaussianBlur(size / 60))
    badge = Image.alpha_composite(badge, shadow)
    td.polygon([p1, p2, p3], fill=(255, 255, 255, 255))
    badge = Image.alpha_composite(badge, tri)

    return badge


def save_foreground(size=432):
    """Transparent-background layer for adaptive icons: badge scaled into the safe zone."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    badge_size = int(size * 0.62)  # keep within ~66dp safe zone of 108dp canvas
    badge = make_badge(badge_size)
    x = (size - badge_size) // 2
    y = (size - badge_size) // 2
    canvas.alpha_composite(badge, (x, y))
    return canvas


def save_legacy(size):
    """Full baked icon (badge on dark rounded background) for legacy launchers."""
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(canvas)
    radius = int(size * 0.22)
    d.rounded_rectangle((0, 0, size - 1, size - 1), radius=radius, fill=BG_DARK + (255,))
    badge_size = int(size * 0.80)
    badge = make_badge(badge_size)
    x = (size - badge_size) // 2
    y = (size - badge_size) // 2
    canvas.alpha_composite(badge, (x, y))
    return canvas


def main():
    mipmap_sizes = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    master_legacy = save_legacy(512)
    for folder, size in mipmap_sizes.items():
        out_dir = os.path.join(RES, folder)
        os.makedirs(out_dir, exist_ok=True)
        resized = master_legacy.resize((size, size), Image.LANCZOS)
        resized.save(os.path.join(out_dir, "ic_launcher.png"))
        resized.save(os.path.join(out_dir, "ic_launcher_round.png"))

    fg = save_foreground(432)
    drawable_dir = os.path.join(RES, "drawable")
    os.makedirs(drawable_dir, exist_ok=True)
    fg.save(os.path.join(drawable_dir, "ic_launcher_foreground.png"))

    # also export a high-res standalone preview for reference
    save_legacy(1024).save("/home/claude/scrapeflix/tools/logo_preview.png")
    print("done")


if __name__ == "__main__":
    main()
