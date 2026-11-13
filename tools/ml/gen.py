"""
CamPDF synthetic training data: document photos on varied backgrounds + exact page masks.
Every sample = a random document page (text in Sinhala / Tamil / English, tables, stamps, signatures, logos)
placed in perspective on a random background (cloth with folds, wood, wall, tiles, notebook, dark table,
real background crops), with hands, shadows, uneven light, blur, noise and JPEG.
Output: N pairs  img_XXXXX.jpg (S x S)  +  msk_XXXXX.png (S x S, 255 = page)  +  corners.txt
Usage: python3 gen.py OUT_DIR N [--size 256] [--bg DIR_WITH_BACKGROUND_PHOTOS] [--seed 1]
Only numpy + Pillow + OpenCV (training-time tools; nothing of this goes into the app).
"""
import argparse, glob, math, os, random
import numpy as np
import cv2
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = sorted(glob.glob(os.path.join(HERE, "fonts", "*.ttf")))

SI = "ශ්‍රී ලංකා පොලිස් අධිකාරී කාර්යාලය මොනරාගල කොට්ඨාශය විමර්ශන වාර්තාවක් කැඳවීම සම්පූර්ණ නම ලිපිනය ජාතික හැඳුනුම්පත් අංකය දුරකථන අංකයන් සම්බන්ධතා පවත්වන ලබන පුද්ගලයන්ගේ නම් හා ලිපිනයන් රැකියාව සහ දැනට"
TA = "இலங்கை பொலிஸ் அலுவலகம் விசாரணை அறிக்கை முழுப் பெயர் முகவரி தேசிய அடையாள அட்டை இலக்கம் தொலைபேசி"
EN = "Occupational Tax and Registration Return for Wagering The Company agrees that disputes relating to any breach of the covenants contained in this Agreement Date Signature Name Address Total Amount Received"
DIG = "0123456789/.-:"


def rnd(a, b):
    return random.uniform(a, b)


# ---------------------------------------------------------------- documents

def paper_colour():
    base = random.choice([(250, 250, 248), (245, 245, 240), (240, 242, 248), (248, 244, 230), (235, 232, 220),
                          (252, 252, 252), (238, 240, 236), (246, 238, 220)])
    return tuple(int(min(255, c + rnd(-6, 6))) for c in base)


def ink_colour():
    r = random.random()
    if r < 0.6:
        g = random.randint(0, 60)
        return (g, g, g)
    if r < 0.85:
        return (random.randint(10, 60), random.randint(20, 70), random.randint(120, 200))  # blue pen
    if r < 0.93:
        return (random.randint(90, 140), random.randint(40, 80), random.randint(130, 190))  # purple stamp
    return (random.randint(150, 200), random.randint(20, 50), random.randint(20, 50))  # red


def make_document(w, h):
    img = Image.new("RGB", (w, h), paper_colour())
    d = ImageDraw.Draw(img)
    kind = random.random()
    margin = int(w * rnd(0.06, 0.12))
    y = int(h * rnd(0.04, 0.10))
    if kind < 0.2:  # coloured header band (receipts / bills)
        bh = int(h * rnd(0.05, 0.12))
        col = random.choice([(30, 60, 160), (20, 90, 160), (160, 30, 40), (20, 120, 60), (40, 40, 40)])
        d.rectangle([0 if random.random() < 0.5 else margin // 2, y, w - margin // 2, y + bh], fill=col)
        y += bh + int(h * 0.02)
    if random.random() < 0.4:  # logo
        r = int(w * rnd(0.04, 0.08))
        d.ellipse([margin, y, margin + 2 * r, y + 2 * r], fill=(20, 20, 20) if random.random() < 0.6 else None,
                  outline=(20, 20, 20), width=3)
        y += 2 * r + 8
    lines = random.randint(10, 40)
    fs_base = int(h * rnd(0.012, 0.025))
    table = random.random() < 0.35
    rows_drawn = 0
    while y < h * 0.9 and rows_drawn < lines:
        fs = max(8, int(fs_base * rnd(0.8, 1.6) if random.random() < 0.15 else fs_base))
        try:
            font = ImageFont.truetype(random.choice(FONTS), fs)
        except Exception:
            font = ImageFont.load_default()
        src = random.choice([SI, SI, TA, EN])
        start = random.randint(0, max(1, len(src) - 60))
        txt = src[start:start + random.randint(15, 70)]
        x = margin + (random.randint(0, int(w * 0.3)) if random.random() < 0.2 else 0)
        col = ink_colour() if random.random() < 0.25 else (random.randint(0, 50),) * 3
        d.text((x, y), txt, fill=col, font=font)
        if table and random.random() < 0.7:
            d.line([margin // 2, y + fs + 4, w - margin // 2, y + fs + 4], fill=(40, 40, 40), width=1)
        y += int(fs * rnd(1.3, 2.0))
        rows_drawn += 1
    if table:
        top = int(h * rnd(0.2, 0.5))
        for cx in sorted(random.sample(range(margin, w - margin), random.randint(2, 5))):
            d.line([cx, top, cx, min(h - margin, top + int(h * rnd(0.2, 0.45)))], fill=(40, 40, 40), width=1)
        if random.random() < 0.5:
            d.rectangle([margin // 2, top, w - margin // 2, min(h - margin, top + int(h * 0.4))], outline=(30, 30, 30), width=2)
    if random.random() < 0.6:  # signature
        sx, sy = int(w * rnd(0.1, 0.6)), int(h * rnd(0.7, 0.9))
        pts = [(sx + i * 6, sy + int(15 * math.sin(i * rnd(0.3, 0.9)) + rnd(-5, 5))) for i in range(random.randint(8, 25))]
        d.line(pts, fill=ink_colour(), width=2)
    if random.random() < 0.4:  # round stamp
        cx, cy, r = int(w * rnd(0.2, 0.8)), int(h * rnd(0.6, 0.9)), int(w * rnd(0.06, 0.12))
        col = random.choice([(110, 60, 160), (60, 70, 170), (170, 40, 50)])
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=col, width=3)
        d.ellipse([cx - r + 8, cy - r + 8, cx + r - 8, cy + r - 8], outline=col, width=2)
    if random.random() < 0.3:  # ruled notebook page
        for yy in range(int(h * 0.1), h, int(h * rnd(0.025, 0.04))):
            d.line([0, yy, w, yy], fill=(150, 170, 220), width=1)
        if random.random() < 0.7:
            d.line([int(w * 0.12), 0, int(w * 0.12), h], fill=(220, 120, 140), width=2)
    arr = np.asarray(img).astype(np.float32)
    if random.random() < 0.3:  # stains / ageing
        for _ in range(random.randint(1, 4)):
            m = np.zeros((h, w), np.float32)
            cv2.circle(m, (random.randint(0, w), random.randint(0, h)), random.randint(10, w // 4), 1.0, -1)
            m = cv2.GaussianBlur(m, (0, 0), w / 30)
            arr -= m[..., None] * np.array([10, 25, 45]) * rnd(0.3, 1.0)
    if random.random() < 0.3:  # fold line
        if random.random() < 0.5:
            yy = int(h * rnd(0.3, 0.7))
            arr[max(0, yy - 1):yy + 2] *= rnd(0.75, 0.92)
        else:
            xx = int(w * rnd(0.3, 0.7))
            arr[:, max(0, xx - 1):xx + 2] *= rnd(0.75, 0.92)
    return np.clip(arr, 0, 255).astype(np.uint8)


# ---------------------------------------------------------------- backgrounds

def smooth_noise(h, w, scale, octaves=3):
    out = np.zeros((h, w), np.float32)
    amp = 1.0
    for o in range(octaves):
        sh, sw = max(2, int(h / scale) + 2), max(2, int(w / scale) + 2)
        n = np.random.rand(sh, sw).astype(np.float32)
        out += cv2.resize(n, (w, h), interpolation=cv2.INTER_CUBIC) * amp
        scale /= 2.0
        amp /= 2.0
    out -= out.min()
    return out / max(1e-6, out.max())


def bg_cloth(h, w):
    base = np.array(random.choice([(235, 230, 215), (240, 240, 235), (200, 120, 150), (170, 90, 110), (120, 150, 190),
                                   (60, 60, 70), (210, 200, 170), (230, 225, 230), (90, 120, 90), (30, 30, 35)]), np.float32)
    base = base + np.random.uniform(-15, 15, 3)
    folds = smooth_noise(h, w, rnd(40, 120), 3)
    folds = np.sin(folds * rnd(6, 18)) * 0.5 + 0.5  # creases
    shade = 0.75 + 0.3 * folds
    weave = (np.random.rand(h, w).astype(np.float32) - 0.5) * rnd(4, 14)
    img = base[None, None, :] * shade[..., None] + weave[..., None]
    return img


def bg_wood(h, w):
    base = np.array(random.choice([(180, 130, 80), (150, 100, 60), (200, 160, 110), (110, 70, 40), (210, 180, 140)]), np.float32)
    ang = rnd(0, math.pi)
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float32)
    t = xx * math.cos(ang) + yy * math.sin(ang)
    warp = smooth_noise(h, w, rnd(30, 80), 2) * rnd(10, 40)
    grain = np.sin((t + warp) / rnd(2.0, 6.0)) * 0.5 + 0.5
    grain = 0.85 + 0.15 * grain + (smooth_noise(h, w, 20, 2) - 0.5) * 0.1
    return base[None, None, :] * grain[..., None]


def bg_wall(h, w):
    base = np.array(random.choice([(235, 235, 230), (225, 220, 210), (200, 210, 220), (245, 245, 245), (190, 180, 160)]), np.float32)
    g = smooth_noise(h, w, rnd(100, 300), 2)
    img = base[None, None, :] * (0.85 + 0.2 * g)[..., None] + (np.random.rand(h, w, 1) - 0.5) * 6
    if random.random() < 0.4:  # tiles
        step = random.randint(30, 90)
        img[::step, :] *= 0.8
        img[:, ::step] *= 0.8
    return img


def bg_dark(h, w):
    v = rnd(10, 70)
    base = np.array([v, v * rnd(0.9, 1.1), v * rnd(0.9, 1.2)], np.float32)
    return base[None, None, :] * (0.8 + 0.4 * smooth_noise(h, w, 80, 2))[..., None]


def bg_book(h, w):
    """another page / open book around the document: paper-coloured with text."""
    doc = make_document(w, h).astype(np.float32)
    return doc * rnd(0.85, 1.0)


REAL_BG = []


def bg_real(h, w):
    f = random.choice(REAL_BG)
    im = cv2.imread(f)
    if im is None:
        return bg_cloth(h, w)
    im = cv2.cvtColor(im, cv2.COLOR_BGR2RGB)
    ih, iw = im.shape[:2]
    s = max(h / ih, w / iw) * rnd(1.0, 1.6)
    im = cv2.resize(im, (int(iw * s) + 1, int(ih * s) + 1))
    y0 = random.randint(0, im.shape[0] - h)
    x0 = random.randint(0, im.shape[1] - w)
    out = im[y0:y0 + h, x0:x0 + w].astype(np.float32)
    k = random.randint(0, 3)
    if k:
        out = np.ascontiguousarray(np.rot90(out, k))
    if random.random() < 0.5:
        out = np.ascontiguousarray(out[:, ::-1])
    out = out * rnd(0.7, 1.25) * np.array([rnd(0.93, 1.07), 1.0, rnd(0.93, 1.07)], np.float32)
    return out


def bg_white_cloth(h, w):
    """the hardest case: white / cream cloth around a white page (low contrast, folds)."""
    base = np.array(random.choice([(238, 236, 228), (242, 240, 232), (230, 228, 215), (245, 245, 242), (225, 222, 205)]), np.float32)
    base = base + np.random.uniform(-6, 6, 3)
    folds = smooth_noise(h, w, rnd(30, 90), 3)
    folds = np.sin(folds * rnd(5, 14)) * 0.5 + 0.5
    img = base[None, None, :] * (0.78 + 0.25 * folds)[..., None] + (np.random.rand(h, w, 1) - 0.5) * rnd(3, 8)
    return img


def background(h, w):
    fns = [bg_cloth, bg_cloth, bg_white_cloth, bg_white_cloth, bg_wood, bg_wall, bg_dark, bg_book]
    if REAL_BG:
        fns += [bg_real] * 12
    return np.clip(random.choice(fns)(h, w), 0, 255)


# ---------------------------------------------------------------- composition

def random_quad(S):
    """Page corners TL,TR,BR,BL in an S x S frame (may run slightly out of the frame)."""
    portrait = random.random() < 0.7
    aspect = rnd(1.25, 1.5) if portrait else 1 / rnd(1.25, 1.5)
    if random.random() < 0.1:
        aspect = rnd(2.0, 3.0)  # receipt
    area = rnd(0.2, 0.9)
    if random.random() < 0.08:
        area = rnd(1.0, 1.6)  # page fills / overflows the frame (close-up, flatbed scans, screenshots)
    ph = math.sqrt(area * S * S * aspect)
    pw = ph / aspect
    cx, cy = S / 2 + rnd(-0.15, 0.15) * S, S / 2 + rnd(-0.15, 0.15) * S
    ang = math.radians(rnd(-25, 25) if random.random() < 0.8 else rnd(-90, 90))
    pts = []
    for dx, dy in [(-1, -1), (1, -1), (1, 1), (-1, 1)]:
        x, y = dx * pw / 2, dy * ph / 2
        pts.append([cx + x * math.cos(ang) - y * math.sin(ang), cy + x * math.sin(ang) + y * math.cos(ang)])
    pts = np.array(pts, np.float32)
    # perspective: move corners independently
    pts += np.random.uniform(-0.08, 0.08, (4, 2)).astype(np.float32) * S
    return pts


def draw_hand(img, S):
    """skin-coloured finger / thumb blob entering from a border."""
    skin = np.array(random.choice([(90, 60, 45), (130, 90, 70), (170, 120, 95), (60, 40, 30), (200, 160, 130)]), np.float32)
    m = np.zeros((S, S), np.uint8)
    side = random.randint(0, 3)
    t = rnd(0.2, 0.8) * S
    L = rnd(0.2, 0.45) * S
    wdt = int(rnd(0.07, 0.14) * S)
    if side == 0:
        p0, p1 = (0, t), (L, t + rnd(-0.1, 0.1) * S)
    elif side == 1:
        p0, p1 = (S, t), (S - L, t + rnd(-0.1, 0.1) * S)
    elif side == 2:
        p0, p1 = (t, S), (t + rnd(-0.1, 0.1) * S, S - L)
    else:
        p0, p1 = (t, 0), (t + rnd(-0.1, 0.1) * S, L)
    cv2.line(m, (int(p0[0]), int(p0[1])), (int(p1[0]), int(p1[1])), 255, wdt)
    cv2.circle(m, (int(p1[0]), int(p1[1])), wdt // 2, 255, -1)
    if random.random() < 0.6:  # palm
        cv2.circle(m, (int(p0[0]), int(p0[1])), int(wdt * rnd(1.5, 3)), 255, -1)
    a = cv2.GaussianBlur(m.astype(np.float32) / 255, (0, 0), 1.2)[..., None]
    shade = 0.75 + 0.35 * smooth_noise(S, S, 30, 2)[..., None]
    img[:] = img * (1 - a) + skin * shade * a
    return m > 127


def apply_light(img, S):
    yy, xx = np.mgrid[0:S, 0:S].astype(np.float32) / S
    g = 1 + rnd(-0.35, 0.15) * (xx * math.cos(rnd(0, 6.28)) + yy * math.sin(rnd(0, 6.28)))
    img *= g[..., None]
    if random.random() < 0.55:  # hard shadow (hand / phone / head)
        m = np.zeros((S, S), np.uint8)
        n = random.randint(3, 7)
        cx, cy = rnd(0, S), rnd(0, S)
        r = rnd(0.15, 0.5) * S
        poly = [(cx + r * rnd(0.4, 1.2) * math.cos(a), cy + r * rnd(0.4, 1.2) * math.sin(a))
                for a in sorted(rnd(0, 6.28) for _ in range(n))]
        cv2.fillPoly(m, [np.array(poly, np.int32)], 255)
        s = cv2.GaussianBlur(m.astype(np.float32) / 255, (0, 0), rnd(0.8, 6))[..., None]
        img *= 1 - s * rnd(0.25, 0.65)
    temp = np.array([rnd(0.9, 1.08), 1.0, rnd(0.85, 1.1)], np.float32)
    img *= temp[None, None, :]
    img *= rnd(0.55, 1.15)
    return img


def sample(S):
    bg = background(S, S)
    quad = random_quad(S)
    dh = random.randint(500, 900)
    dw = int(dh / rnd(1.25, 1.5))
    doc = make_document(dw, dh).astype(np.float32)
    if random.random() < 0.3:
        # low-contrast case: paper about as bright as the background around it (white page on white cloth)
        bl = float(np.percentile(bg.mean(2), 60))
        pl = float(np.percentile(doc.mean(2), 80))
        if bl > 110:
            doc *= np.clip((bl + rnd(-12, 22)) / max(1.0, pl), 0.6, 1.3)
    src = np.array([[0, 0], [dw, 0], [dw, dh], [0, dh]], np.float32)
    M = cv2.getPerspectiveTransform(src, quad)
    warped = cv2.warpPerspective(doc, M, (S, S), flags=cv2.INTER_AREA)
    mask = cv2.warpPerspective(np.full((dh, dw), 255, np.uint8), M, (S, S), flags=cv2.INTER_LINEAR)
    # paper edge: thin bright rim / slight drop shadow on the background
    a = mask.astype(np.float32)[..., None] / 255
    if random.random() < 0.6:
        sh = cv2.GaussianBlur(mask.astype(np.float32) / 255, (0, 0), rnd(1, 4))
        off = np.roll(np.roll(sh, random.randint(1, 4), 0), random.randint(-3, 3), 1)
        bg *= (1 - 0.35 * off * rnd(0.3, 1.0))[..., None]
    img = bg * (1 - a) + warped * a
    hand = None
    if random.random() < 0.4:
        hand = draw_hand(img, S)
    img = apply_light(img, S)
    if random.random() < 0.3:
        k = random.choice([3, 5])
        img = cv2.GaussianBlur(img, (k, k), 0)
    img += np.random.normal(0, rnd(1, 6), img.shape)
    img = np.clip(img, 0, 255).astype(np.uint8)
    q = random.randint(35, 90)
    ok, enc = cv2.imencode(".jpg", cv2.cvtColor(img, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, q])
    img = cv2.cvtColor(cv2.imdecode(enc, 1), cv2.COLOR_BGR2RGB)
    lab = (mask > 127).astype(np.uint8) * 255  # the page is the label even under a hand / shadow
    return img, lab, quad


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("n", type=int)
    ap.add_argument("--size", type=int, default=256)
    ap.add_argument("--bg", default=None)
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--start", type=int, default=0, help="first sample index (to run several generators in parallel)")
    a = ap.parse_args()
    random.seed(a.seed)
    np.random.seed(a.seed)
    if a.bg:
        REAL_BG.extend(sorted(glob.glob(os.path.join(a.bg, "*.jp*g")) + glob.glob(os.path.join(a.bg, "*.png"))))
    os.makedirs(a.out, exist_ok=True)
    with open(os.path.join(a.out, f"corners_{a.start}.txt"), "w") as cf:
        for i in range(a.start, a.start + a.n):
            img, lab, quad = sample(a.size)
            Image.fromarray(img).save(os.path.join(a.out, f"img_{i:05d}.jpg"), quality=92)
            Image.fromarray(lab).save(os.path.join(a.out, f"msk_{i:05d}.png"))
            cf.write(f"{i} " + " ".join(f"{v / a.size:.4f}" for v in quad.flatten()) + "\n")
            if i % 500 == 0:
                print(i, flush=True)


if __name__ == "__main__":
    main()
