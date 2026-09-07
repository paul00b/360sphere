#!/usr/bin/env python3
"""
Génère la visite de démo : 3 pièces synthétiques (équirectangulaires 2048x1024) rendues par lancer
de rayons dans une boîte texturée, avec des portes placées exactement dans la direction des portails,
et le fichier demo.json décrivant sphères et portails (yaw/pitch en radians, convention Photo Sphere
Viewer : yaw 0 = centre de l'image = nord, yaw croissant vers la droite = est).

Usage : python3 tools/make-demo-panos.py app/src/main/assets/demo
Dépendances : numpy, pillow.
"""
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

W, H = 2048, 1024
CAM_H = 1.5
TEX = 1024  # px par mètre… non : taille de texture par face (carrée)

def font(size):
    for p in ["/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf"]:
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()

def noise(shape, amp, seed):
    rng = np.random.default_rng(seed)
    return rng.normal(0, amp, shape)

def wall_texture(width_m, height_m, color, name=None, doors=(), windows=(), pictures=(), tiles=False, seed=0):
    """Texture d'un mur (u : gauche→droite vu de l'intérieur, v : bas→haut). Retourne un tableau HxWx3 float."""
    px_per_m = 220
    tw, th = int(width_m * px_per_m), int(height_m * px_per_m)
    img = Image.new("RGB", (tw, th), color)
    d = ImageDraw.Draw(img)
    if tiles:  # crédence carrelée sur la moitié basse
        tile = int(0.15 * px_per_m)
        for y in range(int(th * 0.55), th, tile):
            for x in range(0, tw, tile):
                shade = 235 + ((x // tile + y // tile) % 2) * 10
                d.rectangle([x, y, x + tile - 2, y + tile - 2], fill=(shade, shade, shade - 5))
    # plinthe (v=0 est le bas ; dans l'image PIL, le bas est y=th)
    d.rectangle([0, th - int(0.10 * px_per_m), tw, th], fill=(70, 62, 56))
    for (u, w, hgt, label) in doors:  # u : position du centre en mètres depuis la gauche
        x0, x1 = int((u - w / 2) * px_per_m), int((u + w / 2) * px_per_m)
        y0, y1 = th - int(hgt * px_per_m), th
        d.rectangle([x0 - 14, y0 - 14, x1 + 14, y1], fill=(92, 78, 66))          # encadrement
        d.rectangle([x0, y0, x1, y1], fill=(150, 112, 78))                         # panneau
        d.rectangle([x0 + 20, y0 + 25, x1 - 20, y0 + int((y1 - y0) * 0.45)], outline=(120, 88, 60), width=6)
        d.rectangle([x0 + 20, y0 + int((y1 - y0) * 0.52), x1 - 20, y1 - 30], outline=(120, 88, 60), width=6)
        d.ellipse([x1 - 60, (y0 + y1) // 2 - 12, x1 - 36, (y0 + y1) // 2 + 12], fill=(220, 190, 90))  # poignée
        if label:
            f = font(34)
            d.text((x0 + 8, y0 - 60), label, fill=(60, 55, 50), font=f)
    for (u, v, w, hgt) in windows:  # centre (u,v) en mètres
        x0, x1 = int((u - w / 2) * px_per_m), int((u + w / 2) * px_per_m)
        y1 = th - int((v - hgt / 2) * px_per_m); y0 = th - int((v + hgt / 2) * px_per_m)
        d.rectangle([x0 - 12, y0 - 12, x1 + 12, y1 + 12], fill=(245, 245, 240))
        d.rectangle([x0, y0, x1, y1], fill=(196, 222, 240))
        # ciel dégradé + croisillon
        for i in range(y0, y1):
            t = (i - y0) / max(1, (y1 - y0))
            c = (int(170 + 60 * t), int(205 + 30 * t), int(240 - 20 * t))
            d.line([x0, i, x1, i], fill=c)
        d.line([(x0 + x1) // 2, y0, (x0 + x1) // 2, y1], fill=(245, 245, 240), width=10)
        d.line([x0, (y0 + y1) // 2, x1, (y0 + y1) // 2], fill=(245, 245, 240), width=10)
    for (u, v, w, hgt, col) in pictures:
        x0, x1 = int((u - w / 2) * px_per_m), int((u + w / 2) * px_per_m)
        y1 = th - int((v - hgt / 2) * px_per_m); y0 = th - int((v + hgt / 2) * px_per_m)
        d.rectangle([x0 - 8, y0 - 8, x1 + 8, y1 + 8], fill=(40, 36, 34))
        d.rectangle([x0, y0, x1, y1], fill=col)
        d.ellipse([x0 + (x1 - x0) // 4, y0 + (y1 - y0) // 4, x1 - (x1 - x0) // 4, y1 - (y1 - y0) // 4], fill=tuple(min(255, c + 50) for c in col))
    if name:
        f = font(int(0.28 * px_per_m))
        tw_, th_ = d.textbbox((0, 0), name, font=f)[2:]
        d.text(((tw - tw_) / 2, th * 0.22 - th_ / 2), name, fill=(255, 255, 255), font=f, stroke_width=4, stroke_fill=(60, 60, 60))
    arr = np.asarray(img).astype(np.float32)
    arr += noise(arr.shape, 2.5, seed)
    return np.clip(arr, 0, 255)

def floor_texture(lx, ly, kind, seed):
    px_per_m = 200
    tw, th = int(lx * px_per_m), int(ly * px_per_m)
    img = Image.new("RGB", (tw, th), (150, 120, 85))
    d = ImageDraw.Draw(img)
    rng = np.random.default_rng(seed)
    if kind == "wood":
        plank = int(0.14 * px_per_m)
        for y in range(0, th, plank):
            off = int(rng.integers(0, 6)) * 60
            base = int(rng.integers(-18, 18))
            x = -off
            while x < tw:
                L = int(rng.integers(500, 900))
                c = (168 + base, 128 + base, 88 + base // 2)
                d.rectangle([x, y, x + L - 3, y + plank - 2], fill=c)
                x += L
    elif kind == "tiles":
        tile = int(0.4 * px_per_m)
        for y in range(0, th, tile):
            for x in range(0, tw, tile):
                s = 205 + ((x // tile + y // tile) % 2) * 22
                d.rectangle([x, y, x + tile - 3, y + tile - 3], fill=(s, s - 6, s - 14))
        img.paste((120, 118, 115), [0, 0, tw, th], Image.new("L", (tw, th), 0))
    else:  # moquette
        img = Image.new("RGB", (tw, th), (120, 132, 150))
        d = ImageDraw.Draw(img)
        # tapis
        d.rectangle([tw * 0.25, th * 0.3, tw * 0.75, th * 0.7], fill=(178, 92, 80))
        d.rectangle([tw * 0.28, th * 0.34, tw * 0.72, th * 0.66], outline=(230, 200, 150), width=8)
    arr = np.asarray(img).astype(np.float32)
    arr += noise(arr.shape, 4.0, seed + 1)
    return np.clip(arr, 0, 255)

def ceiling_texture(lx, ly, seed):
    px_per_m = 120
    tw, th = int(lx * px_per_m), int(ly * px_per_m)
    img = Image.new("RGB", (tw, th), (236, 234, 228))
    d = ImageDraw.Draw(img)
    r = int(0.25 * px_per_m)
    d.ellipse([tw // 2 - r, th // 2 - r, tw // 2 + r, th // 2 + r], fill=(255, 250, 235), outline=(200, 195, 185), width=6)
    arr = np.asarray(img).astype(np.float32)
    return np.clip(arr + noise(arr.shape, 2.0, seed), 0, 255)

def sample(tex, u, v):
    """Échantillonnage bilinéaire simple (u, v dans [0,1], v=0 bas)."""
    th, tw, _ = tex.shape
    x = np.clip(u * (tw - 1), 0, tw - 1)
    y = np.clip((1 - v) * (th - 1), 0, th - 1)
    x0 = np.floor(x).astype(int); y0 = np.floor(y).astype(int)
    x1 = np.minimum(x0 + 1, tw - 1); y1 = np.minimum(y0 + 1, th - 1)
    fx = (x - x0)[..., None]; fy = (y - y0)[..., None]
    return (tex[y0, x0] * (1 - fx) * (1 - fy) + tex[y0, x1] * fx * (1 - fy) + tex[y1, x0] * (1 - fx) * fy + tex[y1, x1] * fx * fy)

def render_room(spec, out_path):
    lx, ly, hz = spec["size"]
    # directions des rayons (x est, y nord, z haut)
    px = (np.arange(W) + 0.5) / W
    py = (np.arange(H) + 0.5) / H
    yaw = (px - 0.5) * 2 * math.pi
    pitch = (0.5 - py) * math.pi
    Yaw, Pitch = np.meshgrid(yaw, pitch)
    dx = np.sin(Yaw) * np.cos(Pitch); dy = np.cos(Yaw) * np.cos(Pitch); dz = np.sin(Pitch)
    cam = np.array([0.0, 0.0, CAM_H])
    # intersections avec les 6 plans
    INF = 1e9
    def t_plane(n, k):  # plan n·p = k
        denom = n[0] * dx + n[1] * dy + n[2] * dz
        t = (k - (n[0] * cam[0] + n[1] * cam[1] + n[2] * cam[2])) / np.where(np.abs(denom) < 1e-9, 1e-9, denom)
        return np.where(t > 1e-6, t, INF)
    planes = [
        ("east", (1, 0, 0), lx / 2), ("west", (-1, 0, 0), lx / 2),
        ("north", (0, 1, 0), ly / 2), ("south", (0, -1, 0), ly / 2),
        ("ceil", (0, 0, 1), hz), ("floor", (0, 0, -1), 0.0),
    ]
    ts = np.stack([t_plane(n, k) for _, n, k in planes])
    idx = np.argmin(ts, axis=0)
    t = np.take_along_axis(ts, idx[None], 0)[0]
    hx = cam[0] + t * dx; hy = cam[1] + t * dy; hz_ = cam[2] + t * dz
    out = np.zeros((H, W, 3), np.float32)
    texs = spec["textures"]
    for i, (name, _, _) in enumerate(planes):
        m = idx == i
        if not m.any():
            continue
        if name == "east":    u = (hy[m] + ly / 2) / ly; u = 1 - u; v = hz_[m] / hz  # vu de l'intérieur : gauche = nord
        elif name == "west":  u = (hy[m] + ly / 2) / ly; v = hz_[m] / hz             # gauche = sud
        elif name == "north": u = (hx[m] + lx / 2) / lx; v = hz_[m] / hz             # gauche = ouest
        elif name == "south": u = 1 - (hx[m] + lx / 2) / lx; v = hz_[m] / hz         # gauche = est
        elif name == "ceil":  u = (hx[m] + lx / 2) / lx; v = (hy[m] + ly / 2) / ly
        else:                 u = (hx[m] + lx / 2) / lx; v = (hy[m] + ly / 2) / ly
        col = sample(texs[name], u, v)
        # éclairage : plafonnier au centre + ambiance ; atténuation avec la distance
        lp = np.array([0.0, 0.0, hz - 0.05])
        d2 = (hx[m] - lp[0]) ** 2 + (hy[m] - lp[1]) ** 2 + (hz_[m] - lp[2]) ** 2
        shade = 0.52 + 0.52 / (1 + 0.16 * d2)
        if name == "floor": shade *= 0.92
        if name == "ceil": shade *= 0.95
        out[m] = col * shade[..., None]
    out = np.clip(out, 0, 255).astype(np.uint8)
    img = Image.fromarray(out)
    img.save(out_path, quality=82, optimize=True)
    img.resize((640, 320), Image.LANCZOS).save(out_path.replace(".jpg", "_thumb.jpg"), quality=82)

def portal_angles(room, door):
    """Yaw/pitch (radians, convention PSV) du centre haut d'une porte vue depuis la caméra."""
    lx, ly, hz = room["size"]
    wall, u_m = door["wall"], door["u"]
    # position 3D du point visé (milieu de la porte à 1,3 m)
    z = 1.3
    if wall == "east":   x, y = lx / 2, ly / 2 - u_m
    elif wall == "west": x, y = -lx / 2, -ly / 2 + u_m
    elif wall == "north": x, y = -lx / 2 + u_m, ly / 2
    else:                 x, y = lx / 2 - u_m, -ly / 2
    yaw = math.atan2(x, y)
    pitch = math.atan2(z - CAM_H, math.hypot(x, y))
    return yaw, pitch

def build(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    rooms = {
        "salon": {"name": "Salon", "size": (5.0, 4.0, 2.6), "wall": (222, 208, 186), "floor": "wood",
                   "doors": {"east": [{"u": 2.0, "to": "cuisine"}], "west": [{"u": 2.0, "to": "chambre"}]},
                   "windows": {"south": [(2.5, 1.5, 1.6, 1.2)]}, "pictures": {"north": [(1.2, 1.5, 0.8, 0.6, (90, 120, 160)), (3.8, 1.5, 0.8, 0.6, (160, 100, 80))]}},
        "cuisine": {"name": "Cuisine", "size": (3.6, 3.6, 2.6), "wall": (214, 228, 214), "floor": "tiles", "tiles": True,
                     "doors": {"west": [{"u": 1.8, "to": "salon"}]},
                     "windows": {"north": [(1.8, 1.6, 1.2, 1.0)]}, "pictures": {}},
        "chambre": {"name": "Chambre", "size": (4.0, 3.6, 2.6), "wall": (196, 210, 228), "floor": "carpet",
                     "doors": {"east": [{"u": 1.8, "to": "salon"}]},
                     "windows": {"south": [(2.0, 1.5, 1.4, 1.1)]}, "pictures": {"north": [(2.0, 1.6, 1.4, 0.7, (120, 90, 140))]}},
    }
    seed = 1
    tour = {"spheres": []}
    for key, room in rooms.items():
        lx, ly, hz = room["size"]
        texs = {}
        for wall in ["north", "south", "east", "west"]:
            wl = lx if wall in ("north", "south") else ly
            doors = [(d["u"], 0.9, 2.1, "→ " + rooms[d["to"]]["name"]) for d in room["doors"].get(wall, [])]
            texs[wall] = wall_texture(wl, hz, room["wall"], name=room["name"] if wall == "north" else None,
                                      doors=doors, windows=room["windows"].get(wall, []), pictures=room["pictures"].get(wall, []),
                                      tiles=room.get("tiles", False) and wall in ("east", "south"), seed=seed)
            seed += 1
        texs["floor"] = floor_texture(lx, ly, room["floor"], seed); seed += 1
        texs["ceil"] = ceiling_texture(lx, ly, seed); seed += 1
        room["textures"] = texs
        render_room(room, os.path.join(out_dir, key + ".jpg"))
        portals = []
        for wall, doors in room["doors"].items():
            for d in doors:
                yaw, pitch = portal_angles(room, {"wall": wall, "u": d["u"]})
                portals.append({"id": f"demo-{key}-{d['to']}", "to": f"demo-{d['to']}", "yaw": round(yaw, 4), "pitch": round(pitch, 4), "label": rooms[d["to"]]["name"]})
        tour["spheres"].append({"id": f"demo-{key}", "name": room["name"], "file": key + ".jpg", "thumb": key + "_thumb.jpg",
                                "width": W, "height": H, "defaultYaw": 0.0, "defaultPitch": 0.0, "portals": portals})
        print(f"{key}: {os.path.getsize(os.path.join(out_dir, key + '.jpg')) // 1024} Ko, portails {[(p['label'], p['yaw'], p['pitch']) for p in portals]}")
    with open(os.path.join(out_dir, "demo.json"), "w", encoding="utf-8") as f:
        json.dump(tour, f, ensure_ascii=False, indent=2)

if __name__ == "__main__":
    build(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/demo")
