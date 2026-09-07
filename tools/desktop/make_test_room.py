#!/usr/bin/env python3
"""
Panoramas équirectangulaires de test à haute résolution, avec deux niveaux de texture :
  --texture rich   : murs couverts d'affiches, étagères, motifs (beaucoup de points d'intérêt)
  --texture weak   : murs quasi unis (cas limite : peu de texture, l'alignement doit échouer proprement)
Usage : python3 make_test_room.py --out room_rich.jpg --texture rich [--width 8192]
"""
import argparse, math
import numpy as np
from PIL import Image, ImageDraw

CAM_H = 1.5


def grain(shape, rng, amp):
    """Micro-texture multi-octave : simule le grain d'une peinture murale et le bruit capteur
    (les aplats de couleur pure ne donnent aucun point d'intérêt, contrairement à une photo réelle)."""
    h, w = shape[:2]
    out = np.zeros((h, w), np.float32)
    a = amp
    for octave in range(4):
        sh, sw = max(2, h >> octave), max(2, w >> octave)
        n = rng.normal(0, 1, (sh, sw)).astype(np.float32)
        img = np.asarray(Image.fromarray(n, mode="F").resize((w, h), Image.BILINEAR))
        out += img * a
        a *= 0.6
    return out[..., None]


def wall(width_m, height_m, base, texture, rng, px=420):
    tw, th = int(width_m * px), int(height_m * px)
    img = Image.new("RGB", (tw, th), base)
    d = ImageDraw.Draw(img)
    d.rectangle([0, th - int(0.11 * px), tw, th], fill=(66, 60, 55))
    if texture == "rich":
        for _ in range(int(width_m * 3)):
            x = rng.integers(0, max(1, tw - 400)); y = rng.integers(int(th * 0.12), int(th * 0.62))
            w = int(rng.integers(200, 420)); h = int(rng.integers(200, 420))
            col = tuple(int(c) for c in rng.integers(30, 230, 3))
            d.rectangle([x, y, x + w, y + h], fill=col, outline=(30, 28, 26), width=8)
            for _ in range(14):  # motif interne haute fréquence
                a = int(rng.integers(x + 12, x + w - 12)); b = int(rng.integers(y + 12, y + h - 12))
                r = int(rng.integers(8, 44))
                c2 = tuple(int(c) for c in rng.integers(20, 250, 3))
                if rng.random() < 0.5:
                    d.ellipse([a - r, b - r, a + r, b + r], fill=c2)
                else:
                    d.rectangle([a - r, b - r, a + r, b + r], fill=c2)
        for _ in range(int(width_m)):  # étagère avec livres
            sx = int(rng.integers(0, max(1, tw - 900))); sy = int(rng.integers(int(th * 0.5), int(th * 0.75)))
            d.rectangle([sx, sy, sx + 900, sy + 26], fill=(120, 90, 60))
            bx = sx + 10
            while bx < sx + 880:
                bw = int(rng.integers(26, 60)); bh = int(rng.integers(120, 230))
                d.rectangle([bx, sy - bh, bx + bw, sy], fill=tuple(int(c) for c in rng.integers(40, 220, 3)))
                bx += bw + 4
    else:
        for _ in range(max(1, int(width_m / 3))):  # à peine une prise électrique
            x = int(rng.integers(0, max(1, tw - 100))); y = int(th * 0.72)
            d.rectangle([x, y, x + 60, y + 90], fill=tuple(int(c) for c in np.array(base) - 12))
    arr = np.asarray(img).astype(np.float32)
    arr += grain(arr.shape, rng, 9.0 if texture == "rich" else 4.0)
    return np.clip(arr, 0, 255)


def floor_tex(lx, ly, texture, rng, px=300):
    tw, th = int(lx * px), int(ly * px)
    img = Image.new("RGB", (tw, th), (150, 120, 85))
    d = ImageDraw.Draw(img)
    plank = int(0.16 * px)
    for y in range(0, th, plank):
        x = -int(rng.integers(0, 400))
        while x < tw:
            L = int(rng.integers(600, 1100)); b = int(rng.integers(-20, 20))
            d.rectangle([x, y, x + L - 4, y + plank - 3], fill=(168 + b, 128 + b, 88 + b // 2))
            x += L
    if texture == "rich":
        d.rectangle([tw * 0.2, th * 0.25, tw * 0.8, th * 0.75], fill=(150, 70, 62))
        for i in range(9):
            k = i / 9
            d.rectangle([tw * (0.22 + k * 0.03), th * (0.27 + k * 0.03), tw * (0.78 - k * 0.03), th * (0.73 - k * 0.03)],
                        outline=(230, 200, 150) if i % 2 else (60, 40, 80), width=14)
    arr = np.asarray(img).astype(np.float32) + grain((th, tw), rng, 8.0)
    return np.clip(arr, 0, 255)


def ceil_tex(lx, ly, rng, px=140):
    tw, th = int(lx * px), int(ly * px)
    img = Image.new("RGB", (tw, th), (238, 236, 230))
    d = ImageDraw.Draw(img)
    r = int(0.3 * px)
    d.ellipse([tw // 2 - r, th // 2 - r, tw // 2 + r, th // 2 + r], fill=(255, 252, 238), outline=(198, 194, 186), width=8)
    return np.clip(np.asarray(img).astype(np.float32) + grain((th, tw), rng, 5.0), 0, 255)


def sample(tex, u, v):
    th, tw, _ = tex.shape
    x = np.clip(u * (tw - 1), 0, tw - 1); y = np.clip((1 - v) * (th - 1), 0, th - 1)
    x0 = np.floor(x).astype(np.int64); y0 = np.floor(y).astype(np.int64)
    x1 = np.minimum(x0 + 1, tw - 1); y1 = np.minimum(y0 + 1, th - 1)
    fx = (x - x0)[..., None]; fy = (y - y0)[..., None]
    return (tex[y0, x0] * (1 - fx) * (1 - fy) + tex[y0, x1] * fx * (1 - fy)
            + tex[y1, x0] * (1 - fx) * fy + tex[y1, x1] * fx * fy)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True)
    ap.add_argument("--texture", choices=["rich", "weak"], default="rich")
    ap.add_argument("--width", type=int, default=8192)
    ap.add_argument("--seed", type=int, default=3)
    a = ap.parse_args()
    W, H = a.width, a.width // 2
    rng = np.random.default_rng(a.seed)
    lx, ly, hz = 5.0, 4.2, 2.7
    base = (216, 206, 188) if a.texture == "rich" else (214, 210, 202)
    texs = {
        "north": wall(lx, hz, base, a.texture, rng), "south": wall(lx, hz, base, a.texture, rng),
        "east": wall(ly, hz, base, a.texture, rng), "west": wall(ly, hz, base, a.texture, rng),
        "floor": floor_tex(lx, ly, a.texture, rng), "ceil": ceil_tex(lx, ly, rng),
    }
    px = (np.arange(W) + 0.5) / W; py = (np.arange(H) + 0.5) / H
    Yaw, Pitch = np.meshgrid((px - 0.5) * 2 * math.pi, (0.5 - py) * math.pi)
    dx = np.sin(Yaw) * np.cos(Pitch); dy = np.cos(Yaw) * np.cos(Pitch); dz = np.sin(Pitch)
    cam = np.array([0.0, 0.0, CAM_H]); INF = 1e9

    def tp(n, k):
        den = n[0] * dx + n[1] * dy + n[2] * dz
        t = (k - float(np.dot(n, cam))) / np.where(np.abs(den) < 1e-9, 1e-9, den)
        return np.where(t > 1e-6, t, INF)

    planes = [("east", (1, 0, 0), lx / 2), ("west", (-1, 0, 0), lx / 2), ("north", (0, 1, 0), ly / 2),
              ("south", (0, -1, 0), ly / 2), ("ceil", (0, 0, 1), hz), ("floor", (0, 0, -1), 0.0)]
    ts = np.stack([tp(n, k) for _, n, k in planes])
    idx = np.argmin(ts, axis=0); t = np.take_along_axis(ts, idx[None], 0)[0]
    hx = cam[0] + t * dx; hy = cam[1] + t * dy; hzp = cam[2] + t * dz
    out = np.zeros((H, W, 3), np.float32)
    for i, (name, _, _) in enumerate(planes):
        m = idx == i
        if not m.any():
            continue
        if name == "east":    u = 1 - (hy[m] + ly / 2) / ly; v = hzp[m] / hz
        elif name == "west":  u = (hy[m] + ly / 2) / ly; v = hzp[m] / hz
        elif name == "north": u = (hx[m] + lx / 2) / lx; v = hzp[m] / hz
        elif name == "south": u = 1 - (hx[m] + lx / 2) / lx; v = hzp[m] / hz
        else:                 u = (hx[m] + lx / 2) / lx; v = (hy[m] + ly / 2) / ly
        col = sample(texs[name], u, v)
        d2 = (hx[m]) ** 2 + (hy[m]) ** 2 + (hzp[m] - (hz - 0.05)) ** 2
        shade = 0.6 + 0.7 / (1 + 0.1 * d2)
        out[m] = col * shade[..., None]
    Image.fromarray(np.clip(out, 0, 255).astype(np.uint8)).save(a.out, quality=94)
    print(f"{a.out} {W}x{H} texture={a.texture}")


if __name__ == "__main__":
    main()
