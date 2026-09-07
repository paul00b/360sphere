#!/usr/bin/env python3
"""
Fabrique une session de capture synthétique à partir d'un panorama équirectangulaire :
rend une photo perspective par position de la grille de capture, avec les défauts d'une prise
en main levée (jitter d'orientation, translation parasite = parallaxe, variation d'exposition,
bruit, flou de mouvement). Écrit shot_*.jpg + meta.json au format attendu par SphereStitcher.

Usage :
  python3 make_test_capture.py --pano room.jpg --out /tmp/session1 [options]

Convention identique à l'app : monde X=est, Y=nord, Z=haut ; équirectangulaire avec yaw 0
au centre de l'image, yaw croissant vers la droite, pitch positif vers le haut.
"""
import argparse, json, math, os
import numpy as np
from PIL import Image, ImageFilter


def build_grid(hfov, vfov, overlap=0.40):
    """Réplique care.primary.sphere360.capture.CaptureGrid.build."""
    hf = min(max(hfov, 30.0), 100.0)
    vf = min(max(vfov, 35.0), 110.0)
    e1 = min(max(vf * 0.70, 30.0), 55.0)
    rows = [0.0, e1, -e1]
    targets = []
    for ri, pitch in enumerate(rows):
        eff = hf / math.cos(math.radians(pitch))
        n = max(4, math.ceil(360.0 / (eff * (1 - overlap))))
        step = 360.0 / n
        off = 0.0 if ri == 0 else step / 2
        for j in range(n):
            yaw = ((j * step + off) % 360.0)
            if yaw > 180.0:
                yaw -= 360.0
            targets.append({"index": len(targets), "row": ri, "yaw": yaw, "pitch": pitch})
    return targets


def dir_from(yaw, pitch):
    return np.array([math.sin(yaw) * math.cos(pitch), math.cos(yaw) * math.cos(pitch), math.sin(pitch)])


def camera_axes(yaw_deg, pitch_deg, roll_deg):
    """Axes de la caméra dans le repère monde (X=est, Y=nord, Z=haut)."""
    yaw, pitch, roll = map(math.radians, (yaw_deg, pitch_deg, roll_deg))
    f = dir_from(yaw, pitch)
    right = np.array([math.cos(yaw), -math.sin(yaw), 0.0])
    up = np.array([-math.sin(yaw) * math.sin(pitch), -math.cos(yaw) * math.sin(pitch), math.cos(pitch)])
    cr, sr = math.cos(roll), math.sin(roll)
    right, up = right * cr - up * sr, up * cr + right * sr
    return f, right, up


def device_rotation(yaw_deg, pitch_deg, roll_deg):
    """Matrice de rotation Android (appareil -> monde), colonnes = (droite, haut écran, -avant),
    aplatie ligne par ligne : exactement ce que fournit SensorManager.getRotationMatrixFromVector."""
    f, right, up = camera_axes(yaw_deg, pitch_deg, roll_deg)
    m = np.stack([right, up, -f], axis=1)
    return [float(v) for v in m.reshape(-1)]


def render_view(pano, yaw_deg, pitch_deg, roll_deg, hfov, vfov, w, h, translate=None):
    """Rend une vue perspective. translate = déplacement de la caméra en unités de rayon de pièce."""
    f, right, up = camera_axes(yaw_deg, pitch_deg, roll_deg)
    down = -up

    tx = math.tan(math.radians(hfov) / 2)
    ty = math.tan(math.radians(vfov) / 2)
    xs = (np.arange(w) + 0.5) / w * 2 - 1
    ys = (np.arange(h) + 0.5) / h * 2 - 1
    X, Y = np.meshgrid(xs * tx, ys * ty)
    d = f[None, None, :] + X[..., None] * right[None, None, :] + Y[..., None] * down[None, None, :]

    if translate is not None:
        # parallaxe : la scène est à distance ~1 (mur unité), on décale l'origine du rayon
        t = np.asarray(translate, dtype=np.float64)
        d = d / np.linalg.norm(d, axis=2, keepdims=True) - t[None, None, :]

    n = np.linalg.norm(d, axis=2, keepdims=True)
    d = d / n
    yaw_s = np.arctan2(d[..., 0], d[..., 1])
    pitch_s = np.arcsin(np.clip(d[..., 2], -1, 1))

    PH, PW, _ = pano.shape
    u = (yaw_s / (2 * math.pi) + 0.5) * PW
    v = (0.5 - pitch_s / math.pi) * PH
    # bilinéaire, u circulaire
    u0 = np.floor(u).astype(np.int64); v0 = np.floor(v).astype(np.int64)
    fu = (u - u0)[..., None]; fv = (v - v0)[..., None]
    u0m = u0 % PW; u1m = (u0 + 1) % PW
    v0c = np.clip(v0, 0, PH - 1); v1c = np.clip(v0 + 1, 0, PH - 1)
    out = (pano[v0c, u0m] * (1 - fu) * (1 - fv) + pano[v0c, u1m] * fu * (1 - fv)
           + pano[v1c, u0m] * (1 - fu) * fv + pano[v1c, u1m] * fu * fv)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pano", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--hfov", type=float, default=50.0)
    ap.add_argument("--vfov", type=float, default=0.0,
                    help="0 = déduit de --hfov et du format (objectif rectilinéaire, pixels carrés)")
    ap.add_argument("--width", type=int, default=960)
    ap.add_argument("--height", type=int, default=1280)
    ap.add_argument("--jitter-deg", type=float, default=0.0, help="erreur d'orientation aléatoire (écart-type)")
    ap.add_argument("--roll-deg", type=float, default=0.0, help="roulis aléatoire (écart-type)")
    ap.add_argument("--translate", type=float, default=0.0, help="translation parasite en fraction de la distance aux murs")
    ap.add_argument("--exposure", type=float, default=0.0, help="variation d'exposition (écart-type, fraction)")
    ap.add_argument("--noise", type=float, default=0.0, help="bruit gaussien (écart-type en niveaux)")
    ap.add_argument("--blur", type=float, default=0.0, help="flou gaussien (rayon px)")
    ap.add_argument("--quality", type=int, default=93)
    ap.add_argument("--limit", type=int, default=0, help="ne rendre que les N premières positions")
    ap.add_argument("--seed", type=int, default=7)
    a = ap.parse_args()

    rng = np.random.default_rng(a.seed)
    if a.vfov <= 0:
        f = (a.width / 2) / math.tan(math.radians(a.hfov) / 2)
        a.vfov = math.degrees(2 * math.atan((a.height / 2) / f))
    pano = np.asarray(Image.open(a.pano).convert("RGB")).astype(np.float32)
    targets = build_grid(a.hfov, a.vfov)
    if a.limit:
        targets = targets[:a.limit]
    os.makedirs(a.out, exist_ok=True)
    for f in os.listdir(a.out):
        if f.startswith("shot_") or f == "meta.json":
            os.remove(os.path.join(a.out, f))

    shots = []
    for t in targets:
        jy = rng.normal(0, a.jitter_deg); jp = rng.normal(0, a.jitter_deg)
        roll = rng.normal(0, a.roll_deg)
        yaw = t["yaw"] + jy; pitch = t["pitch"] + jp
        tr = None
        if a.translate > 0:
            tr = rng.normal(0, a.translate, 3); tr[2] *= 0.3
        img = render_view(pano, yaw, pitch, roll, a.hfov, a.vfov, a.width, a.height, tr)
        if a.exposure > 0:
            img = img * (1 + rng.normal(0, a.exposure))
        if a.noise > 0:
            img = img + rng.normal(0, a.noise, img.shape)
        pil = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
        if a.blur > 0:
            pil = pil.filter(ImageFilter.GaussianBlur(a.blur))
        name = "shot_%03d.jpg" % len(shots)
        pil.save(os.path.join(a.out, name), quality=a.quality)
        shots.append({"file": name, "yaw": yaw, "pitch": pitch, "roll": roll, "target": t["index"],
                      "rot": device_rotation(yaw, pitch, roll)})

    meta = {
        "id": os.path.basename(a.out.rstrip("/")), "createdAt": 0, "name": "Test",
        "camera": {"hfov": a.hfov, "vfov": a.vfov, "width": a.width, "height": a.height, "jpegRotation": 90},
        "shots": shots, "state": "CAPTURED", "error": None, "attempts": 0, "targetCount": len(targets),
    }
    with open(os.path.join(a.out, "meta.json"), "w") as fh:
        json.dump(meta, fh, indent=1)
    print(f"{len(shots)} photos {a.width}x{a.height} hfov={a.hfov:.2f} vfov={a.vfov:.2f} -> {a.out}")


if __name__ == "__main__":
    main()
