# CamPDF

Free, fully offline document scanner and PDF maker (Android, Kotlin + Jetpack Compose).

- No INTERNET permission, no accounts, no ads, no analytics
- Scan with CameraX or import images, auto edge detection, manual corner crop
- Filters (Magic, Enhance, No shadow, B&W, Gray, Lighten), rotate, reorder, delete, add pages
- Draw a signature once and stamp it on any page
- Compact PDFs (High / Balanced / Compact), share sheet, save to phone, rename, search
- Sinhala / Tamil / English, light / dark theme, optional biometric app lock

## Build with GitHub Actions
1. Create a GitHub repo and push this folder to `main`.
2. Open the **Actions** tab, run **Build CamPDF APK** (it also runs on every push).
3. Download the `CamPDF-release-apk` artifact and install the APK.

Optional release signing: add repository secrets `KEYSTORE_BASE64` (base64 of your .jks),
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them the release APK is debug-signed
(still installable).

## Local build
Install Android Studio (JDK 17) and open the folder, or run `gradle assembleDebug`.

## What is new in 1.1.0 (scanner pipeline)
All of this is pure Kotlin, still fully offline, no extra libraries.

- **Smarter edge detection** (`Imaging.detectCorners`): tries three ways of finding the page
  (bright blob, colour different from the picture border, paper-white / low-colour area), fits the
  best four-corner shape to each (convex hull, max-area quadrilateral), and keeps the most
  convincing one. Works on coloured tables and uneven light much better than a single brightness
  threshold. Corners can still be dragged by hand.
- **True page proportions** (`Imaging.warp`): the real width/height of the page is estimated from the
  perspective of the four corners (Zhang and He, "Whiteboard scanning"), so a tilted photo no longer
  comes out stretched. New setting **Straighten to A4**: pages within 5% of A4 become exact A4.
- **New filters**: *Enhance* (shadow removal + whitening + light sharpening for crisp thin text) and
  *No shadow* (shadow removal that keeps natural colours).

## Algorithm test scripts
`tools/prototype/` has the Python (OpenCV/NumPy) versions of the detector and the aspect-ratio
estimator, with synthetic test scenes (`python3 synth.py`, `python3 aspect.py`). Use them to tune
thresholds quickly before porting changes to Kotlin.
