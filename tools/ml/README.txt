PageNet - CamPDF's own page-finding network (training tools only; nothing here is part of the app build)

The app runs the trained network with plain Kotlin (app/src/main/java/com/campdf/app/PageNet.kt), offline,
no ML library. It only becomes active when app/src/main/assets/pagenet.bin exists; without that file the app
uses the straight-edge detector (LineQuad.kt) alone.

1. One-time setup on the Mac (M1/M2: PyTorch trains on the GPU through "mps"):
     cd tools/ml
     python3 -m venv .venv && source .venv/bin/activate
     pip install torch numpy opencv-python pillow
     mkdir -p fonts && cd fonts
     for f in NotoSansSinhala/hinted/ttf/NotoSansSinhala-Regular.ttf NotoSansSinhala/hinted/ttf/NotoSansSinhala-Bold.ttf \
              NotoSerifSinhala/hinted/ttf/NotoSerifSinhala-Regular.ttf NotoSansTamil/hinted/ttf/NotoSansTamil-Regular.ttf \
              NotoSerif/hinted/ttf/NotoSerif-Regular.ttf NotoSans/hinted/ttf/NotoSans-Bold.ttf; do
       curl -sSfLO "https://raw.githubusercontent.com/notofonts/notofonts.github.io/main/fonts/$f"; done
     cd ..

2. Real backgrounds (most important for white cloth / beds / tables / walls / books):
   put 30-100 photos of EMPTY backgrounds (no document) in ../../tune/bg/  (git-ignored).

3. Synthetic training data (documents on backgrounds, hands, shadows, folds, blur):
     python3 gen.py data 20000 --bg ../../tune/bg
   (several in parallel: --start 0 / --start 10000 with different --seed)

4. Train (writes pagenet.pt and pagenet.bin):
     python3 train.py data --epochs 30
   Resume / fine-tune: run again with the same --out (it loads pagenet.pt); --lr 1e-3 for fine-tuning.

5. Use in the app: copy pagenet.bin to app/src/main/assets/pagenet.bin
   PC check before that: tools/jvmtest/Detect.kt with -Dmodel=tools/ml/pagenet.bin on the /tune photos.

Model: tiny U-Net, 15 conv3x3 layers (BatchNorm folded at export), 248k parameters, ~0.5 MB float16,
256x256 RGB in -> 64x64 page probability out. Kotlin output was checked against PyTorch (max diff 6e-4).
