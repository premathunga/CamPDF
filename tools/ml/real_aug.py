"""Augmented training samples from REAL labelled photos (corners known): random perspective, flips, colour.
usage: real_aug.py OUT_DIR START_INDEX PER_PHOTO corners.txt PHOTO_DIR  (corners: 'name ... C x0,y0,...,x3,y3' normalised)"""
import sys, random, numpy as np, cv2, math
out, start, per, cfile, pdir = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), sys.argv[4], sys.argv[5]
names = set(sys.argv[6].split(','))
S = 256; random.seed(5); np.random.seed(5); k = start
for line in open(cfile):
    name = line.split()[0]
    if name not in names: continue
    c = np.array([float(v) for v in line.split(' C ')[1].split(',')], np.float32).reshape(4, 2)
    im = cv2.cvtColor(cv2.imread(f'{pdir}/{name}.jpg'), cv2.COLOR_BGR2RGB)
    H, W = im.shape[:2]
    h = int(H * 0.93)  # drop the camera watermark strip
    im = im[:h]; q = c * [W, H]
    mask = np.zeros((h, W), np.uint8); cv2.fillPoly(mask, [q.astype(np.int32)], 255)
    for _ in range(per):
        src = np.array([[0, 0], [W, 0], [W, h], [0, h]], np.float32)
        z = random.uniform(0.75, 1.25); cx, cy = S / 2 + random.uniform(-25, 25), S / 2 + random.uniform(-25, 25)
        a = math.radians(random.uniform(-20, 20) + random.choice([0, 0, 0, 90, 180, 270]))
        dst = []
        for dx, dy in [(-1, -1), (1, -1), (1, 1), (-1, 1)]:
            x, y = dx * S / 2 * z, dy * S / 2 * z
            dst.append([cx + x * math.cos(a) - y * math.sin(a) + random.uniform(-14, 14), cy + x * math.sin(a) + y * math.cos(a) + random.uniform(-14, 14)])
        M = cv2.getPerspectiveTransform(src, np.array(dst, np.float32))
        o = cv2.warpPerspective(im, M, (S, S), flags=cv2.INTER_AREA, borderMode=cv2.BORDER_REFLECT_101).astype(np.float32)
        m = cv2.warpPerspective(mask, M, (S, S), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REFLECT_101)
        if random.random() < 0.5: o = o[:, ::-1]; m = m[:, ::-1]
        o = o * random.uniform(0.6, 1.3) * np.array([random.uniform(0.9, 1.1), 1, random.uniform(0.9, 1.1)], np.float32)
        o += np.random.normal(0, random.uniform(0, 5), o.shape)
        if random.random() < 0.3: o = cv2.GaussianBlur(o, (3, 3), 0)
        cv2.imwrite(f'{out}/img_{k:05d}.jpg', cv2.cvtColor(np.clip(o, 0, 255).astype(np.uint8), cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, random.randint(50, 92)])
        cv2.imwrite(f'{out}/msk_{k:05d}.png', ((np.ascontiguousarray(m) > 127) * 255).astype(np.uint8)); k += 1
print('real samples', k - start)
