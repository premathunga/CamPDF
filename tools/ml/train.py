"""
CamPDF page-segmentation network: tiny U-Net, 256x256 RGB in -> 64x64 page probability out.
Plain conv3x3 + ReLU layers only (BatchNorm is folded into the convs at export), so the app can run it with a
few hundred lines of pure Kotlin (PageNet.kt) - no ML library on the phone.

Train (Mac M1 uses the GPU through PyTorch "mps" automatically):
    python3 train.py DATA_DIR --epochs 30
    -> pagenet.pt (checkpoint) and pagenet.bin (weights for the app)
Export only:  python3 train.py DATA_DIR --export-only
"""
import argparse, glob, os, struct, time
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
from PIL import Image

CH = [16, 24, 32, 48, 64]  # encoder widths at /2, /4, /8, /16, /32


class CBR(nn.Module):
    def __init__(self, cin, cout, stride=1):
        super().__init__()
        self.conv = nn.Conv2d(cin, cout, 3, stride, 1, bias=False)
        self.bn = nn.BatchNorm2d(cout)

    def forward(self, x):
        return F.relu(self.bn(self.conv(x)))


class PageNet(nn.Module):
    def __init__(self):
        super().__init__()
        c = CH
        self.e1 = nn.Sequential(CBR(3, c[0], 2), CBR(c[0], c[0]))          # 128
        self.e2 = nn.Sequential(CBR(c[0], c[1], 2), CBR(c[1], c[1]))       # 64
        self.e3 = nn.Sequential(CBR(c[1], c[2], 2), CBR(c[2], c[2]))       # 32
        self.e4 = nn.Sequential(CBR(c[2], c[3], 2), CBR(c[3], c[3]))       # 16
        self.e5 = nn.Sequential(CBR(c[3], c[4], 2), CBR(c[4], c[4]), CBR(c[4], c[4]))  # 8 (wide context)
        self.d4 = CBR(c[4] + c[3], c[3])   # 16
        self.d3 = CBR(c[3] + c[2], c[2])   # 32
        self.d2 = CBR(c[2] + c[1], c[1])   # 64
        self.head = nn.Conv2d(c[1], 1, 3, 1, 1)

    def forward(self, x):
        x1 = self.e1(x)
        x2 = self.e2(x1)
        x3 = self.e3(x2)
        x4 = self.e4(x3)
        x5 = self.e5(x4)
        u = F.interpolate(x5, scale_factor=2, mode="nearest")
        u = self.d4(torch.cat([u, x4], 1))
        u = F.interpolate(u, scale_factor=2, mode="nearest")
        u = self.d3(torch.cat([u, x3], 1))
        u = F.interpolate(u, scale_factor=2, mode="nearest")
        u = self.d2(torch.cat([u, x2], 1))
        return self.head(u)  # logits, 64x64


class DS(torch.utils.data.Dataset):
    def __init__(self, root, idx, aug):
        self.root, self.idx, self.aug = root, idx, aug

    def __len__(self):
        return len(self.idx)

    def __getitem__(self, k):
        i = self.idx[k]
        im = np.asarray(Image.open(os.path.join(self.root, f"img_{i:05d}.jpg")).convert("RGB"), np.float32) / 255.0
        m = np.asarray(Image.open(os.path.join(self.root, f"msk_{i:05d}.png")), np.float32) / 255.0
        if self.aug:
            if np.random.rand() < 0.5:
                im = im[:, ::-1]; m = m[:, ::-1]
            if np.random.rand() < 0.5:
                im = im[::-1]; m = m[::-1]
            if np.random.rand() < 0.5:
                im = np.transpose(im, (1, 0, 2)); m = m.T
        x = torch.from_numpy(np.ascontiguousarray(im.transpose(2, 0, 1)))
        y = torch.from_numpy(np.ascontiguousarray(m))[None]
        y = F.avg_pool2d(y[None], 4)[0]  # 64x64 soft target
        return x, y


def fold_bn(conv, bn):
    w = conv.weight.detach().cpu().double()
    g = bn.weight.detach().cpu().double(); b = bn.bias.detach().cpu().double()
    mu = bn.running_mean.detach().cpu().double(); var = bn.running_var.detach().cpu().double()
    s = g / torch.sqrt(var + bn.eps)
    return (w * s[:, None, None, None]).float(), (b - mu * s).float()


def export(model, path):
    """pagenet.bin: magic 'PGN2', int32 nLayers, then per layer: int32 cout, cin, stride, relu,
    float16 weights [cout][cin][3][3], float16 bias[cout] (little endian). Layer order = forward order (PageNet.kt)."""
    model = model.cpu().eval()
    layers = []
    for blk in [model.e1, model.e2, model.e3, model.e4, model.e5]:
        for cbr in blk:
            layers.append((cbr, cbr.conv.stride[0], 1))
    for cbr in [model.d4, model.d3, model.d2]:
        layers.append((cbr, 1, 1))
    with open(path, "wb") as f:
        f.write(b"PGN2")
        f.write(struct.pack("<i", len(layers) + 1))
        for cbr, st, relu in layers:
            w, b = fold_bn(cbr.conv, cbr.bn)
            f.write(struct.pack("<iiii", w.shape[0], w.shape[1], st, relu))
            f.write(w.numpy().astype("<f2").tobytes())
            f.write(b.numpy().astype("<f2").tobytes())
        w = model.head.weight.detach().cpu().float(); b = model.head.bias.detach().cpu().float()
        f.write(struct.pack("<iiii", w.shape[0], w.shape[1], 1, 0))
        f.write(w.numpy().astype("<f2").tobytes())
        f.write(b.numpy().astype("<f2").tobytes())
    print("exported", path, os.path.getsize(path), "bytes")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("data")
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--bs", type=int, default=32)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--out", default="pagenet")
    ap.add_argument("--export-only", action="store_true")
    ap.add_argument("--max-steps", type=int, default=0, help="stop early (smoke test)")
    a = ap.parse_args()
    dev = "mps" if torch.backends.mps.is_available() else ("cuda" if torch.cuda.is_available() else "cpu")
    model = PageNet()
    if os.path.exists(a.out + ".pt"):
        model.load_state_dict(torch.load(a.out + ".pt", map_location="cpu"))
        print("resumed", a.out + ".pt")
    if a.export_only:
        export(model, a.out + ".bin"); return
    print("device", dev, "params", sum(p.numel() for p in model.parameters()))
    n = len(glob.glob(os.path.join(a.data, "img_*.jpg")))
    idx = list(range(n))
    val = idx[: max(50, n // 20)]
    tr = idx[len(val):]
    dl = torch.utils.data.DataLoader(DS(a.data, tr, True), batch_size=a.bs, shuffle=True, num_workers=2, drop_last=True)
    dv = torch.utils.data.DataLoader(DS(a.data, val, False), batch_size=a.bs, num_workers=2)
    model.to(dev)
    opt = torch.optim.AdamW(model.parameters(), lr=a.lr, weight_decay=1e-4)
    total = a.epochs * len(dl)
    sched = torch.optim.lr_scheduler.OneCycleLR(opt, max_lr=a.lr, total_steps=max(1, total))
    step = 0
    best = 1e9
    for ep in range(a.epochs):
        model.train(); t0 = time.time(); tl = 0
        for x, y in dl:
            x, y = x.to(dev), y.to(dev)
            p = model(x)
            # BCE + soft Dice: Dice keeps the page boundary sharp
            bce = F.binary_cross_entropy_with_logits(p, y)
            s = torch.sigmoid(p)
            dice = 1 - (2 * (s * y).sum((1, 2, 3)) + 1) / (s.sum((1, 2, 3)) + y.sum((1, 2, 3)) + 1)
            loss = bce + dice.mean()
            opt.zero_grad(); loss.backward(); opt.step(); sched.step()
            tl += loss.item(); step += 1
            if a.max_steps and step >= a.max_steps:
                break
        model.eval(); vl = 0; iou = 0; nb = 0
        with torch.no_grad():
            for x, y in dv:
                x, y = x.to(dev), y.to(dev)
                s = torch.sigmoid(model(x))
                pb = (s > 0.5).float(); yb = (y > 0.5).float()
                inter = (pb * yb).sum((1, 2, 3)); uni = ((pb + yb) > 0).float().sum((1, 2, 3))
                iou += (inter / uni.clamp(min=1)).mean().item()
                vl += F.binary_cross_entropy(s, y).item(); nb += 1
        vl /= max(1, nb); iou /= max(1, nb)
        print(f"epoch {ep + 1}/{a.epochs} train {tl / max(1, len(dl)):.4f} val_bce {vl:.4f} val_IoU {iou:.4f} ({time.time() - t0:.0f}s)", flush=True)
        if vl < best:
            best = vl
            torch.save(model.state_dict(), a.out + ".pt")
        if a.max_steps and step >= a.max_steps:
            break
    model.load_state_dict(torch.load(a.out + ".pt", map_location="cpu"))
    export(model, a.out + ".bin")


if __name__ == "__main__":
    main()
