# CamPDF

Free, fully offline document scanner and PDF maker (Android, Kotlin + Jetpack Compose).

- No INTERNET permission, no accounts, no ads, no analytics
- Scan with CameraX or import images; offline edge detection (blur + Sobel + contour/hull quad fitting), perspective correction with true page proportions, manual corner crop
- In-app PDF viewer; re-open any saved PDF to add / reorder / rotate / delete pages and save
- Filters with live thumbnails: Original, Lighten, Enhance, No shadow, B&W (shadow removal + adaptive threshold + sharpening)
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


## CamPDF scan engine update (Priority 1–3)

This source update focuses on the existing offline scanning pipeline:

- **Document detection (Priority 1):** candidate scoring now includes a document-shape plausibility score in addition to edge strength, component fill, and detected area. Portrait, landscape, receipt, and near-square documents remain eligible.
- **Image enhancement (Priority 2):** the BW filter threshold is derived from robust image percentile levels instead of relying on one fixed threshold. This is intended to adapt to dim captures and varied paper/ink density while retaining a soft transition for fine strokes. The existing local background/shadow estimation and color-preserving `NO_SHADOW` path remain offline.
- **Perspective correction (Priority 3):** perspective warp now validates normalized corner input, checks convexity and minimum quadrilateral area, limits aspect-ratio correction, initializes output as white, and uses a safe fallback for invalid transform matrices.

## CamPDF professional enhancement engine update (Priority 4)

- **Adaptive illumination correction & local shadow removal:** Replaced the coarse background map with morphological closing (dilation $r=3$ + erosion $r=1$) and separable 2D box smoothing. This captures true paper illumination without halo artifacts around text blocks and normalizes harsh phone/hand shadows.
- **Handwriting handling:** `NO_SHADOW` divides each colour channel by its own paper-colour estimate, so blue ballpoint ink keeps its colour while the paper turns white. The explicit blue-ink check (red-channel absorption) is used only in the `BW` filter so that blue handwriting does not turn pale grey.
- **Text-aware contrast enhancement:** Dark printed text is deepened using a non-linear contrast curve (`CONTRAST_LUT`), and blue ballpoint ink is enriched by deepening complementary channels, producing vivid, crisp text and signatures.
- **Folds / creases:** these fade as a side effect of the illumination division and the white-gain curve; there is no separate crease detector.
- **Standardized PDF page sizing:** Near-A4 scanned documents (aspect 1.33-1.50) get an ISO 216 A4 page (595.28 x 841.89 pt). The scan keeps its own proportions inside the page (centred, no stretching).

These are algorithmic improvements, not a claim of parity with CamScanner. Test on a range of real devices and document samples (white paper on bright/dark surfaces, receipts, folded sheets, handwritten forms, and landscape documents) before release. This environment did not have an Android SDK/Gradle build tool configured, so a compile/device test could not be run here.

## Review notes (v1.5.1)
- Detection: quads whose corners sit on the frame edge are penalised (x0.85 per corner). On a real photo with a light-green striped cloth this widens the margin between the correct page candidate and the wrong "page + cloth" candidate from 0.02 to about 0.29.
- Crop: leftover background strips (torn / folded edges) along the borders are trimmed after the perspective warp.
- PDF: A4 page size no longer stretches the image.
