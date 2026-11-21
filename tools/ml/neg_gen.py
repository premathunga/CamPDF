"""v6 hard negatives: plain backgrounds (cloth, walls, wood, tables, floor) with an EMPTY mask = "no page here".
usage: neg_gen.py OUT_DIR START PER_IMAGE holdout,names img1 img2 ..."""
import sys, random, math, numpy as np
from PIL import Image, ImageFilter, ImageEnhance
out, start, per = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
hold = set(sys.argv[4].split(','))
random.seed(6); np.random.seed(6); k = start
zero = Image.new('L', (256, 256), 0)
for p in sys.argv[5:]:
    name = p.split('/')[-1].rsplit('.', 1)[0]
    if name in hold: continue
    im = Image.open(p).convert('RGB')
    W, H = im.size
    for _ in range(per):
        s = random.uniform(0.35, 1.0) * min(W, H)
        cx = random.uniform(s / 2, W - s / 2); cy = random.uniform(s / 2, H - s / 2)
        c = im.crop((int(cx - s / 2), int(cy - s / 2), int(cx + s / 2), int(cy + s / 2)))
        c = c.rotate(random.choice([0, 90, 180, 270]) + random.uniform(-10, 10), resample=Image.BILINEAR, expand=False)
        c = c.crop((int(s * 0.08), int(s * 0.08), int(s * 0.92), int(s * 0.92))).resize((256, 256), Image.BILINEAR)
        c = ImageEnhance.Brightness(c).enhance(random.uniform(0.6, 1.35))
        c = ImageEnhance.Color(c).enhance(random.uniform(0.6, 1.3))
        a = np.asarray(c).astype(np.float32) + np.random.normal(0, random.uniform(0, 5), (256, 256, 3))
        c = Image.fromarray(np.clip(a, 0, 255).astype(np.uint8))
        if random.random() < 0.3: c = c.filter(ImageFilter.GaussianBlur(random.uniform(0.5, 1.5)))
        c.save(f'{out}/img_{k:05d}.jpg', quality=90); zero.save(f'{out}/msk_{k:05d}.png'); k += 1
print('wrote', k - start)
