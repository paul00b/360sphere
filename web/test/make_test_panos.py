"""Panoramas équirectangulaires synthétiques pour tester le viewer (repères d'azimut lisibles)."""
import sys, math
from PIL import Image, ImageDraw, ImageFont

def make(path, base, name):
    W, H = 2048, 1024
    img = Image.new("RGB", (W, H), base)
    d = ImageDraw.Draw(img)
    # ciel / sol
    d.rectangle([0, 0, W, H // 2 - 60], fill=tuple(min(255, c + 60) for c in base))
    d.rectangle([0, H // 2 + 60, W, H], fill=tuple(max(0, c - 60) for c in base))
    try:
        font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", 70)
    except Exception:
        font = ImageFont.load_default()
    marks = [(0.5, "0° (centre)"), (0.75, "+90° droite"), (0.25, "-90° gauche"), (0.0, "180°"), (1.0, "180°")]
    for fx, text in marks:
        x = int(fx * W)
        d.line([x, 0, x, H], fill=(255, 255, 255), width=4)
        d.text((x + 12 if fx < 1 else x - 400, H // 2 - 40), text, fill=(255, 255, 255), font=font)
    d.text((W // 2 - 300, H // 2 + 120), name, fill=(255, 230, 80), font=font)
    img.save(path, quality=85)

make(sys.argv[1] + "/room_a.jpg", (60, 90, 140), "Salon (A)")
make(sys.argv[1] + "/room_b.jpg", (140, 80, 60), "Cuisine (B)")
print("ok")
