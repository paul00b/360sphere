#!/usr/bin/env python3
"""
Compare un équirectangulaire assemblé à son panorama de référence.

Deux chiffres, les seuls qui aient servi à trancher entre deux versions du composeur :

  - l'écart moyen en niveaux de gris, mesuré sur la bande réellement photographiée (les calottes
    polaires sont extrapolées, les y compter mesurerait l'extrapolation et non l'assemblage) ;
  - la netteté relative, énergie du gradient du résultat divisée par celle de la référence. Un
    dédoublement translucide ou un flou de fusion la fait tomber ; un raccord net la garde
    proche de 1.

Un décalage global en azimut est cherché puis compensé avant la mesure : selon le chemin
d'assemblage, la sphère peut sortir tournée de quelques degrés sans que ce soit un défaut.

Usage :
  python3 compare_pano.py --result out/equirect.jpg --reference room.jpg [--pitch-limit 60]
"""
import argparse
import numpy as np
from PIL import Image


def load_gray(path, size):
    img = Image.open(path).convert("L")
    if img.size != size:
        img = img.resize(size, Image.LANCZOS)
    return np.asarray(img).astype(np.float32)


def band(a, pitch_limit):
    """Lignes dont la latitude reste sous la limite, en degrés."""
    h = a.shape[0]
    rows = np.arange(h)
    pitch = 90.0 - (rows + 0.5) / h * 180.0
    keep = np.abs(pitch) <= pitch_limit
    return a[keep]


def best_shift(result, reference, max_shift):
    """Décalage en colonnes qui minimise l'écart, cherché grossièrement puis affiné."""
    w = result.shape[1]
    best = (None, 0)
    step = max(1, max_shift // 16)
    coarse = range(-max_shift, max_shift + 1, step)
    for s in coarse:
        err = np.abs(np.roll(result, s, axis=1) - reference).mean()
        if best[0] is None or err < best[0]:
            best = (err, s)
    for s in range(best[1] - step, best[1] + step + 1):
        err = np.abs(np.roll(result, s, axis=1) - reference).mean()
        if err < best[0]:
            best = (err, s)
    return best[1] % w if best[1] >= 0 else best[1]


def gradient_energy(a):
    gx = np.diff(a, axis=1)
    gy = np.diff(a, axis=0)
    return float(np.sqrt((gx * gx).mean() + (gy * gy).mean()))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--result", required=True)
    ap.add_argument("--reference", required=True)
    ap.add_argument("--pitch-limit", type=float, default=60.0,
                    help="latitude maximale prise en compte, en degrés")
    ap.add_argument("--max-shift-deg", type=float, default=12.0,
                    help="décalage en azimut cherché avant la mesure")
    a = ap.parse_args()

    res_img = Image.open(a.result)
    size = res_img.size
    result = np.asarray(res_img.convert("L")).astype(np.float32)
    reference = load_gray(a.reference, size)

    shift = best_shift(band(result, a.pitch_limit), band(reference, a.pitch_limit),
                       int(size[0] * a.max_shift_deg / 360))
    aligned = np.roll(result, shift, axis=1)

    r = band(aligned, a.pitch_limit)
    g = band(reference, a.pitch_limit)
    # Un écart d'exposition global n'est pas un défaut de raccord : on le retire avant de mesurer.
    r = r - (r.mean() - g.mean())
    error = float(np.abs(r - g).mean())
    rmse = float(np.sqrt(((r - g) ** 2).mean()))
    sharp = gradient_energy(r) / max(1e-6, gradient_energy(g))
    print("taille        %d x %d" % size)
    print("recalage      %+d colonnes (%.1f°)" % (shift, shift * 360.0 / size[0]))
    print("écart moyen   %.2f niveaux" % error)
    print("rmse          %.2f niveaux" % rmse)
    print("netteté       %.3f de la référence" % sharp)


if __name__ == "__main__":
    main()
