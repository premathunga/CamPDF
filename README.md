CamPDF

A free, fully offline document scanner and PDF maker for Android, written in Kotlin with Jetpack Compose.

Take a photo of a page (or import images), let the app find the page edges and straighten it, clean up the background, and save or share a PDF. Everything happens on the phone: the app does not declare the INTERNET permission, has no account system, no ads and no analytics.

සාරාංශය (Sinhala): CamPDF යනු සම්පූර්ණයෙන්ම නොබැඳිව (offline) ක්‍රියා කරන, නොමිලේ ලබාදෙන ලේඛන ස්කෑනරයක් සහ PDF සාදනයකි. කඩදාසියේ කොන් හඳුනාගෙන කෙලින් කර, පසුබිම සුදු කර, අකුරු පැහැදිලි කර PDF එකක් සාදයි. ගිණුමක් අවශ්‍ය නැත, අන්තර්ජාල අවසරයක් නැත, ඔබේ ලේඛන දුරකථනයෙන් පිටතට නොයයි.

Features

Capture

CameraX capture with a portrait page guide (corner brackets and thirds), flash toggle, and focus/exposure metering at the centre of the guide when the camera starts
Import one or many images from the gallery; each image goes through the crop step in turn

Page detection and crop

Automatic page-edge detection, fully offline (see Page detection)
Manual correction: drag the four corners, with a 3x magnifier that follows the corner under your finger; handles cannot cross or create a degenerate shape
Auto re-runs detection, Full selects the whole photo
When detection is not confident, the app places an inset rectangle and shows a hint to adjust the corners
Perspective correction that restores the page proportions (see Perspective correction)

Review and edit pages

Add pages (camera or gallery), rotate, re-crop, delete, move left / right
Per-page filter with live thumbnails (see Filters)
Draw a signature once and place it on any page by dragging; resize with a slider
Discard confirmation if you leave an unsaved scan

Documents

"All Documents" list with thumbnail, date, page count, size, search, rename, delete and share
Built-in PDF viewer (scroll through pages, zoom buttons, double-tap to zoom) — no external app needed
Edit pages on a saved document: add, reorder, rotate, delete, filter, sign, then save back to the same file
Export screen: rename, change quality (High / Balanced / Compact), share sheet, save to a location you choose, open in another app

Settings

Language: English, Sinhala, Tamil
Theme: dark (default), light, system
Default PDF quality, auto-detect on/off
Optional app lock (fingerprint / screen lock) at app start
Filters

Every page has its own filter; new pages use Magic. All filters run on the device.

Filter	What it does
Original	No processing.
Lighten	Brightness lift (colour matrix, gain 1.12, offset +22).
Enhance	Automatic levels from robust percentiles of the image, extra contrast for darker pixels, light sharpening. Colours are kept.
No shadow	Estimates the local paper colour for each channel and divides by it (removes shadows and colour casts), whitens the page, deepens dark text with a contrast curve, light sharpening. Colours are kept.
B&W	Same illumination correction, then a soft black/white threshold applied in the corrected domain (about ≤163 black, ≥227 white). Blue ballpoint ink is treated specially so it does not turn pale grey. Sharpening before the threshold.
Magic (default)	Illumination correction, then levels with black point 135 and white point 215 (gamma 1.2) applied through one common gain so colours are preserved; near-paper pixels are pushed to white to remove the remaining tint. Aims for white paper, dark text, coloured ink kept.

The illumination estimate is computed on a small copy of the page (at most 80 px on the long side) using dilation (to ignore dark text) followed by box blurs, then interpolated back to full size.

Page detection

Implemented in DocDetect.kt in pure Kotlin (no OpenCV, no ML model). The image is analysed at 400 px.

Candidate regions. Several independent masks are built and each one is turned into a candidate quadrilateral:

brightness (Otsu threshold) — light page on a darker surface
distance from the colour of the photo border — page that differs from the surroundings
the intersection of the two masks above
chroma — neutral paper against a coloured cloth or table
closed edge contours with flood fill from the border — also works for white paper on a light table
ink density — printed text marks the page area (white page on a white wall); weighted ×0.85

A heuristic skin-tone mask removes fingers holding the page from the brightness / border / chroma / ink masks.

Geometry. For each mask: largest connected region → boundary points → convex hull → simplification (Visvalingam) → best 4-vertex polygon by area → corners ordered TL, TR, BR, BL. A candidate is rejected if the region fills less than 70 % of its hull, an interior angle is outside 45°–135°, or a side is shorter than 10 % of the image's smaller dimension.

Scoring. The best candidate is chosen by:

Term	Weight
Fill (region area / hull area)	0.30
Page size in the frame	0.24
Edge strength along the sides	0.15
Closeness to the image centre	0.13
Plausible physical aspect ratio (0.55–1.65)	0.12
Shape (opposite sides similar, aspect range)	0.06

Each corner that sits on the frame edge multiplies the score by 0.85. Below a score of 0.20 the detector returns "not found" and the UI falls back as described above. The accepted quadrilateral is finally snapped: each side moves up to ±8 px (at 400 px scale) onto the strongest gradient ridge and the corners are re-intersected.

Perspective correction
The four corners are validated (finite, convex, minimum area); invalid input falls back to a safe transform.
The page's real width/height ratio is estimated from the perspective distortion (Zhang & He, "whiteboard scanning" method) and used only when it stays within a limited range of the measured side lengths; otherwise the measured sides are used.
The warp output starts white, is limited to 3508 px on the long side, and thin strips of background left along the borders (torn or folded edges) are trimmed — at most 5 % per side and only when the page itself is light.
PDF output
Written by a small built-in PDF 1.4 writer (PdfWriter.kt): one JPEG image per page, pages processed one at a time to keep memory low. No PDF library is used.
Page size: if the scan's aspect ratio is between 1.33 and 1.50 (A4-like, portrait or landscape) the page is ISO A4 (595.28 × 841.89 pt); otherwise the page is 595.28 pt wide with a proportional height. The image keeps its own proportions inside the page (centred, never stretched).
Files are replaced only after a complete PDF has been written next to them.
Quality	Long side	JPEG quality	Intended use
Compact	2000 px	82	Smallest files, long documents
Balanced (default)	3000 px	92	Sharing by messaging apps or email
High	3508 px	94	Official filing and printing

The PDFs contain page images only (no text layer).

Privacy and storage
Permissions: CAMERA only. There is no INTERNET permission, so the app cannot send anything over the network.
Documents live in the app's private storage: files/docs/<name>.pdf, plus a .pages file (page count) and a .jpg thumbnail. The signature is stored as files/signature.png. allowBackup is off.
Sharing uses the Android share sheet through a FileProvider; saving to the phone uses the system file picker. Nothing leaves the app unless you share or save it.
App lock uses BiometricPrompt (biometric or device credential on Android 11+, biometric only on older versions) when the app is opened.
Build

Requirements: JDK 17, Android SDK (compileSdk 34). minSdk 26 (Android 8.0), targetSdk 34. Kotlin 1.9.24, Android Gradle Plugin 8.5.2, Jetpack Compose (BOM 2024.06.00), CameraX 1.3.4.

GitHub Actions (.github/workflows/build.yml)

Push the repository to GitHub (main).
Open Actions → Build CamPDF APK (it also runs on every push and can be started manually).
Download the artifacts: CamPDF-debug-apk, CamPDF-release-apk, and build-logs (a "Show compile errors" step prints the error lines if the build fails).

The workflow installs Gradle 8.7 itself, so no Gradle wrapper is needed in the repository.

Release signing (optional). Add the repository secrets KEYSTORE_BASE64 (the .jks file, base64-encoded), KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD. Without them the release APK is signed with the debug key (installable, but not suitable for publishing).

Local build. Open the folder in Android Studio, or run gradle assembleDebug with Gradle 8.7.

Project layout
app/src/main/java/com/campdf/app/
  MainActivity.kt        activity, theme, app lock, navigation host
  AppViewModel.kt        scan session, crop queue, PDF build / edit, settings
  DocDetect.kt           page-edge detection
  Imaging.kt             loading, perspective warp, border trim, filters, signature stamp
  PdfWriter.kt           PDF 1.4 writer          PdfHolder.kt   PdfRenderer wrapper (viewer / edit)
  DocRepo.kt             document storage        Prefs.kt       settings storage
  ui/                    Compose screens: Welcome, Home, Camera, Crop, Review, Sign, Export, Viewer, Settings
app/src/main/res/        strings (en, si, ta), themes, launcher icon
tools/prototype/         Python prototypes (not part of the app) — see below

tools/prototype/ holds Python prototypes used to experiment with detection and aspect-ratio estimation (pip install -r requirements.txt; needs numpy and opencv-python). They are separate re-implementations, not the Kotlin code that ships in the app, so their results do not transfer directly.

Known limitations
Detection is heuristic. It can still fail on low-contrast backgrounds (a white page on a white or cream surface), strongly patterned cloth, or pages cut off by the frame. No accuracy figures are published for the current detector; the manual corner handles with the magnifier are the fallback.
Creases and punch holes. In test scans of a folded dot-matrix pay slip, fold lines came out as dark streaks in Magic and especially B&W (a crease is darker than the surrounding paper, so it is treated like ink), and punch holes became solid black dots. Very faint dot-matrix print stays light grey in Magic. The other filters keep creases subtle and show the surface seen through the holes.
Editing a saved PDF renders each page back to an image (2000 px wide, JPEG 92) with the Original filter and without the original photo or corner positions, so re-cropping works on the flattened page and repeated edits re-encode the images.
No OCR and no text layer — the PDFs are not searchable.
The signature is an image stamp drawn into the page, not a cryptographic or legally qualified digital signature.
App lock is checked when the app starts; the PDFs themselves are not encrypted on disk.
Importing existing PDFs from other apps is not supported (images only). The app runs in portrait only.
There are no automated tests in the repository; behaviour has been checked by building with GitHub Actions and trying scans on a device.
Ideas not implemented

OCR (Sinhala / Tamil / English) with a searchable PDF layer, letterhead and stamp templates, a crease-aware B&W mode, importing PDFs from other apps.
