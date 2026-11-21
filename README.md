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

## v2.5.1 - automatic shadow strength + finger removal (Imaging.kt)
- **Auto shadow strength (all filters).** The illumination map is now built for every filter (ENHANCE too).
  Per pixel, a lighting variation under ~4% of the paper level is ignored (the page is divided by one constant paper
  level, so no halos / faded text), from ~12% it is corrected fully, and in between it fades in. `shadowStrength()`
  (0..1, from the depth and width of the shadows) additionally scales the sharpening and ENHANCE's text deepening.
  ENHANCE is now colour-preserving *and* shadow-corrected (same gain on R, G, B); before it only stretched levels.
- **Finger removal** (`detectFingers`, Settings -> "Remove fingers", default on): skin-coloured blobs (warm hue, moderate
  saturation) that touch the page border and have a plausible size/shape are whitened with a soft edge, in every filter.
  Thin red / brown ink, stamps inside the page, and yellowed or brown paper (too much skin-like area) are left alone.
  Limits: a finger covering text hides that text for real; very pale or very dark skin under strong colour casts may be
  missed; a skin-coloured table strip left along the page border would also be whitened (usually harmless).
- Python references: `tools/prototype/v2_5/`. Not compiled here (no Android SDK in the sandbox).

## v2.6.0 - CamScanner-inspired Color-Preserving Magic Engine & Professional Enhancements
- **Color-Preserving Magic Color (`FilterMode.MAGIC`):**
  - Pixels are classified into colored ink vs. neutral printed text vs. paper background.
  - Handwritten blue pens, red pens, green/purple notes, and colored official rubber stamps are explicitly preserved and saturation-boosted (+35%), preventing ink from turning to pure black or fading out.
  - Neutral printed text and pencil strokes are deepened into solid jet black using a high-contrast curve.
  - Paper background and subtle shadows smoothly blend into 100% spotless white (255, 255, 255).
- **Anti-Bleaching & Faint Text Preservation (v2.6.1):**
  - Solved heading/sub-heading bleaching on bright document regions using a stroke-aware Sigmoid contrast curve.
  - Faint Sinhala/Tamil headings, sub-titles, and thin table grid lines (luminance 160–210) are deepened rather than washed into paper white.
  - Sensitive blue ink threshold (`normB - normR > 4f`) ensures faint ballpoint pen notes and header numbers stay visible in vivid blue.
  - Added edge obstruction removal (< 55 brightness) in `detectFingers` to clean dark binder clips and margin thumb smudges.
- **Red ink & stamp protection in B&W mode (`FilterMode.BW`):**
  - Added red-channel absorption check so red seals and red pen marks are rendered as solid crisp black rather than disappearing into the white background.
- **Enhanced Edge Snapping (`DocDetect.kt`):**
  - Extended boundary refinement search range from $\pm 8\text{ px}$ to $\pm 14\text{ px}$ with 40-step subpixel gradient sampling, locking tightly onto true document boundaries even over shadow gradients.
- **Auto-Straightening / Orientation (`Imaging.kt` & `AppViewModel.kt`):**
  - Added text line gradient variance detector (`detectSidewaysOrientation`) that automatically detects and straightens 90-degree sideways captures during crop confirmation.
- **Print-Safe Margins (`PdfWriter.kt`):**
  - Added standard 12 pt safe margins on standardized A4 exports, preventing physical printers from clipping document headers and footers.

## v2.8.0 - correctness fixes + new B&W engine
Tested on 8 real phone photos (forms, receipt, table, handwritten notes, notepad) with the PC harness in `tools/jvmtest`.
- **Auto-rotate removed (it was rotating correct pages).** The content-based detector (v2.6.3-v2.6.5) turned an upright
  tax form upside-down and an upright receipt sideways, and it ran again on every preview/export, so preview, thumbnail and
  PDF could disagree and the Rotate button behaved unpredictably. Pages are now rotated only by the user.
- **Camera orientation from the phone's sensor.** The activity is locked to portrait, so photos taken with the phone held
  sideways came out sideways. `CameraScreen` now sets CameraX's target rotation from `OrientationEventListener`; the
  EXIF tag makes the page upright. Lying flat over the page keeps the last known orientation.
- **Signatures are stamps, not pixels.** They are drawn after the filter (B&W / Magic / finger removal can no longer
  alter or whiten them), each stamp keeps its own copy of the signature, and the last one can be removed
  ("Remove sign" in Review). The placement screen now shows the filtered page.
- **New B&W filter (local adaptive threshold).** Light correction, then Sauvola thresholding over a window of ~1/22 of
  the page with a soft 1-2 px edge. Tinted table cells, highlighted areas and wrinkled/shaded paper no longer turn into
  black patches; text inside tinted cells stays black; coloured pens/stamps are pulled towards their darkest channel so
  they stay black.
- **Finer illumination map** (160 px instead of 80 px, closing radius 5): creases and wrinkle shading on receipts are
  removed much better in all colour filters.
- **Magic: neutral black text.** A small colour cast on printed text (warm light, toner) is dropped (chroma dead-zone
  ~4%), real pen/stamp colours keep the full boost.
- Known limits (next steps): page detection still fails when the page fills the whole frame or for small documents on
  a textured table; camera has no live edge overlay / auto-capture yet.

## v2.9.0 - new page detection + shadow / washed-out print handling
Tuned on 29 real phone photos of Sinhala documents (letters, police forms, receipts, notebook, folded/stained pages,
hand and phone shadows, white / pink / dark cloth backgrounds) plus 8 English samples. Photos stay in `/tune` (git-ignored).
- **Page detection rewritten around straight edges (`LineQuad.kt`).** Colour gradient (luminance + two colour axes),
  thin edges, Hough lines; every pair of horizontal x vertical lines (plus the frame borders) is a candidate quad.
  A side only counts where the image has a *sharp step* with a consistent sign (printed table lines, folds and soft
  shadow borders do not), and a side is penalised when the area just beyond it is still the same paper.
  The old mask-based candidates are re-scored with the same function. The two colour axes weigh 2x more than
  brightness (white page on cream / white cloth differs mostly in hue; cloth folds are brightness-only) and weaker
  edges are kept (80th percentile), so low-contrast page borders still become lines. Result on the test set:
  correct page on ~27/29 Sinhala photos and 8/8 English samples (v2.8: ~14/29 and 6/8); left: a crumpled receipt
  with a heavy coloured header (t19) and a page held in the hand on white cloth running out of the picture (t01).
- **Sub-pixel corners.** Each side is re-fitted on a 1600 px copy (colour-step search across the side, robust line fit,
  corners = intersections). The coarse quad is found on 400 px, where one pixel is ~8-10 px of the photo.
- **Shadow-proof paper map.** 400 px map, small dilation + 2-D median (edge preserving) instead of box blur, so the
  sharp edge of a hand / phone shadow no longer leaves a grey ghost.
- **Local ink level (Magic, No shadow, B&W).** Washed-out print (lamp / direct light) and ink inside shadows is
  stretched back to near-black from the local ink darkness; faint features (ruled lines, paper grain) and shadow
  borders get no gain.
- **Coloured areas keep their colour.** Where the paper map is clearly coloured (tinted cell / box) only its brightness
  is corrected.
- B&W threshold k 0.20 -> 0.14 (thin pen strokes in notebooks stay connected).
- Known limits: a very blurred photo (t12) loses most of its faint pen writing; faded purple stamps next to a shadow
  border can lose parts in B&W; upside-down captures from the gallery must be rotated by hand.

## v3.0.0 - own page-finding network, PageNet
- `PageNet.kt`: a tiny U-Net (248k parameters, 0.5 MB float16, `app/src/main/assets/pagenet.bin`) run in plain
  Kotlin - no ML library, offline, checked against PyTorch (max difference 6e-4). It marks where the page is.
- `DocDetect` fuses it with the straight-edge finder: a quad with four strongly supported sides that agrees with
  the network is kept as it is; otherwise the candidates (edge quads + quads fitted to the network's outline) are
  ranked by edge support x agreement with the network. Corners are still refined to sub-pixel on the photo.
  Without the asset the app behaves exactly like v2.9.
- Training (`tools/ml`): 7,000 synthetic documents (Sinhala / Tamil / English) on real background photos
  (`tune/bg`) and generated cloth / wood / wall / book backgrounds with hands, shadows, folds and blur, plus
  augmented copies of the 29 labelled `tune` photos (`real_aug.py`).
- Result: all 29 `tune` photos correct (they were part of the training, so this is not a fair test); on photos
  the network never saw - 6 new Sinhala photos and the 8 English samples - 14/14 pages found (v2.9 missed one of
  them). Of the training photos, the white page on white cloth with a hand (t01) and the crumpled receipt with a
  coloured header (t19) were wrong in v2.9 and are right now.
- Not measured yet: speed on a phone (about 0.3 s per photo on a 2-core PC).

## v3.1.0 - side-by-side with a CamScanner scan of the same photo
Reference: one 3000x4000 photo of a filled-in form, CamScanner's PDF and CamPDF v3.0's PDF, registered pixel to pixel.
- **Crop no longer eats the page (`trimBorders`).** The border clean-up treated a shadow lying on the page and
  handwriting in coloured pen as "background strip": the bottom ~8% of the page and a red reference number at the
  top were cut off. A strip is now trimmed only when it is clearly dark (< 42% of the paper level) or coloured over
  at least 45% of the row / column.
- **Magic tone matched to the reference.** Text was ~35% heavier than in the CamScanner scan (dark-pixel share
  7.3% vs 5.4%) and small print filled in. New curve (black 0.25, white 0.82, gamma 0.95), gentler local ink gain
  (target 175), sharpen 0.55: dark-pixel share is now 5.7%. Colour boost 2.8 -> 1.7 (blue pen is navy, not neon).
- **Pale coloured ink survives.** The tone of a pixel is taken darker in proportion to how coloured it is, so red
  pen and faded stamps are not washed out with the paper (still fainter than in the CamScanner scan).
- **Finger removal leaves dark objects alone.** Only skin and dark *warm* blobs at the border are whitened; a
  dark blue binding thread used to be half erased.
- **A4 pages.** Scans with a ratio of 1.28-1.56 get an A4 page (was 1.33-1.50).

## v3.2.0 - top sheet of a pile, true A4 output, clean borders
Same reference photo as v3.1 (a form lying on a pile of papers in a file, phone shadow across the bottom).
- **Top sheet of a pile.** The page lay on other white sheets shifted a few millimetres; v3.1 took the whole pile, so
  the result had strips of the lower sheets and of the folder. The generator (`tools/ml/gen.py --stack`) now puts
  1-4 other sheets under the page in 45% of the samples and labels only the top one; PageNet was retrained on
  that. `DocDetect` then fits a quad directly to the network's page area (`fitToProb`, corner-by-corner search)
  when no well-supported edge quad agrees with the network; edge quads that do agree are kept as before.
- **True A4.** A page whose proportions come out within ~8% of A4 is rendered at exactly 1 : 1.4142 (`snapA4`).
- **Border clean-up (`cleanBorders`, Magic and B&W).** Blobs that touch the border are erased when they stay
  inside a 1.5% band, or inside a 7% band when they are a soft colourless smudge / a thin colourless streak
  (shadow ends, page-edge lines). Text, coloured pen near the edge, stamps and binding thread are not touched.
- **Red ink.** Red pixels get a stronger tone correction than other colours, so a red reference number stays
  readable (still thinner than in the CamScanner scan).
- Checked on the 29 `tune` photos, 6 unseen Sinhala photos and 8 English samples: no page lost compared with
  v3.1. Known leftovers on the reference photo: a strip of the folder above the top-left corner, a streak from
  the shadow edge at the bottom, and the crop runs to the bottom of the photo instead of the sheet's own edge.

## v3.3.0 - live page outline, auto-capture, multi-core filters
- **Live page detection in the camera.** A small 4:3 analysis stream (~640x480, newest frame only) runs the same
  detector as the crop screen (`DocDetect.detectBitmap`: PageNet + straight edges, no hi-res refine) on a
  background thread; the page outline is drawn over the preview and turns green while the page is held steady.
  If a camera cannot run preview + photo + analysis together, the app falls back to preview + photo.
- **Auto-capture** (on by default, "Auto" chip in the camera top bar, remembered): the photo is taken when the
  page has been steady for 1.2 s (no corner moving more than 2.5% of the frame) and covers at least 12% of the
  frame. A ring around the shutter shows the countdown. `LiveTracker.kt` holds this logic (smoothing, misses,
  steadiness) and is unit-tested on the PC (`tools/jvmtest/LiveTest.kt`).
- The crop screen still runs the full detection (with sub-pixel refinement) on the real photo.
- **Multi-core filters.** Magic / Enhance / No-shadow process 32-row blocks on all cores (up to 8); B&W and the
  border clean-up are split by rows. Output is bit-for-bit identical to the single-thread version (checked on
  4 filters x 3 photos). On a 2-core PC: Magic 1.3 s -> 0.85 s, B&W 0.8 s -> 0.6 s; phones with 8 cores gain more.
- Live detection takes ~0.3 s per frame on a 2-core PC (about 3 outline updates per second); phone speed not
  measured yet.

## v3.3.1 - live detection fixes after the first phone test
Phone test: the live outline was slow and often wrong, auto-capture took very long, and the live-captured photo
came out smaller (page 1210 px wide instead of ~1900) with colour fringes; a receipt total at the bottom edge was cut.
- **Fast live detector (`DocDetect.detectLive`).** Only PageNet runs on preview frames; its page map is turned into
  a quad (largest region -> best 4-gon -> fitted to the map) and the sides are snapped to the frame's edges with
  one Sobel pass. PC: 77 ms per frame instead of 160 ms; corners within 1.5% (median) of the full detector.
  The crop screen still runs the full detector on the photo.
- **PageNet ~2.2x faster** (three kernel taps per pass): 285 ms -> 129 ms per run on the PC, same output (max
  difference vs PyTorch unchanged, 0.0026).
- **Auto-capture rewritten (`LiveTracker`).** Measured: detections of a still page wobble by ~2.4% of the frame
  from frame to frame, more than the old 2.5% max-corner tolerance, so the countdown kept restarting. Now:
  steadiness on the smoothed outline, average corner movement, 3.5% tolerance, 1.0 s hold; a single far-off
  frame is ignored. Simulation with real detections of 62 pages, camera noise and hand shake: every page captured
  after 1.0 s (old tracker: median 1.4 s, slowest 9.1 s, one page never).
- **Overlay position.** The outline is mapped into the area the FIT_CENTER preview really fills.
- **Full-resolution photo.** ImageCapture now asks explicitly for the highest 4:3 resolution.
- **Straightening no longer cuts content.** The post-warp straightening kept the old canvas size, so content
  pushed past an edge was lost (the "6430" total at the bottom of the receipt). The canvas now grows to hold it.
- **Border clean-up** also removes slivers of cloth / table that run along a border (long contact, shallow).

## v3.3.2 - page that fills the frame with a large shadow
Phone test: a police message form photographed close-up (page fills the whole frame, white table, large soft
shadow over the lower half). PageNet took the shadowed half for background, so the crop ran diagonally across the
page and the scan came out distorted.
- `LineQuad.minInnerSupport`: the weakest edge evidence among the sides of a quad that are not on the frame border.
- Fusion (`DocDetect`): a candidate with a side across the paper where the image has no edge at all is down-weighted
  (x0.25). On this photo the crop is now the whole page; the 71 other test photos give exactly the same corners.
- Live (`detectLive`): when the network's quad has such a side, or its sides are poorly backed (score < 0.20), the
  frame goes to the full detector. Live speed unchanged (66 ms median on the PC); auto-capture simulation: 63/63
  pages captured, median 1.0 s.

## v3.3.3 - tinted shadows
Phone test (v3.3.2, police message form): live scan now gives a full-resolution page (2126 px wide, was 1210)
and both live and import crop the whole page. But the import (a photo with a big shadow over the lower half) came
out with the shadow turned into a speckled yellow-green patch.
- Cause 1: the "coloured areas keep their colour" rule (v2.9) also kept the colour of a shadow - shadows are tinted
  (warm room light, light reflected from coloured cloth). An area clearly darker than the paper (< 90% of the paper
  level, fully from 78%) is now treated as a shadow and its tint is removed. Printed coloured cells keep their colour.
- Cause 2: the light correction brightens a shadow and amplifies the camera's colour noise by the same factor. The
  Magic chroma dead-zone now grows with that factor (up to 3x), so the noise is not boosted into colour speckles.
- Reproduced on the PC with a tinted, noisy shadow added to the form photo: patch gone. Other test pages: coloured
  headers and blue / red ink unchanged.

## v3.4.0 - select several documents
- All Documents list: **long-press** a document to start selecting, then tap others to add or remove them.
- The top bar shows how many are selected, with **Select all**, **Share** (all selected PDFs in one share sheet,
  e.g. one WhatsApp or e-mail message) and **Delete** (one confirmation for all).
- Close (X) or the Back button leaves selection mode. Scan / Import buttons are hidden while selecting.

## v3.4.1 - delete a page from its thumbnail
- Review pages: the selected page thumbnail shows a small red **X** in its top-right corner; tap it to delete that
  page (no need to scroll the tool row to the end). The tool-row Delete still works.
- Both ask "Delete page N?" first; deleting the only page warns that the whole scan will be discarded.
- v3.4.2: the tool-row Delete moved from the end to 5th place (Add, Rotate, Crop, Filter, Delete, Sign, Left, Right).

## v3.4.3 - small conveniences
- Settings shows the real app version (read from the installed package) instead of a fixed "v1.0".
- Review pages -> Filter: **Apply <filter> to all N pages** gives every page the current page's filter in one tap.
- All Documents: **Sort** menu (newest / oldest, name A-Z / Z-A, largest / smallest), remembered.
- **Share -> CamPDF** from WhatsApp, Gallery, Files...: shared images (one or several) open straight as a new scan
  (auto edges -> crop -> review). Images only; the app still has no INTERNET permission.
- Removed a stray `0001-v2.7.0-*.patch` file from the source folder.

## v3.5.0 - Tools: ID card copy and PDF password
- Home screen: a **Tools** row above the document list (icon + name tiles, scrolls sideways; more tools later).
- **ID card**: scan the front, the camera comes back for the back side, then both are placed on one A4 page at the
  real card size (ISO ID-1, 85.6 x 54 mm; portrait cards stay portrait), like an office photocopy. Pages keep their
  colours (Enhance filter). Print at 100% / actual size. More than two sides -> two per A4 sheet.
  (`IdCard.kt`; `PdfPage.exactA4` writes the sheet without the A4 safety margin so the size stays exact.)
- **Password** (Tools row, a document's menu, or the PDF-created screen): makes an **AES-256** locked copy
  (PDF 2.0 security handler V5/R6) to share or save; the original stays unlocked inside CamPDF. Own code
  (`PdfCrypt.kt`) using only the platform's AES / SHA-2; the password is never stored. Locked copies are deleted
  the next time the app starts. Checked with qpdf, poppler and pypdf (`tools/jvmtest/CryptTest.kt`), including a
  Sinhala password.

## v3.5.1 - auto-capture only takes documents
Phone test: right after opening the camera, auto-capture took whatever was in view (the ground), and while the
gallery picker was open the camera behind it kept capturing, so importing was impossible.
- Live detection now also says whether the outline looks like a document (`DocDetect.lastLiveAuto`): the network
  must be sure it is a page AND there is writing on it, or it is a separate sheet (3+ sides inside the frame,
  brighter than the background). Other outlines are still drawn ("tap to capture"), but never auto-captured.
  Simulation: 62/63 document photos auto-capture (median 1.0 s); 35 bare backgrounds: 0 false captures.
- `LiveTracker`: the hold timer only runs while recent frames are page-like.
- Camera screen: no auto-capture in the first 1.5 s after the camera opens; live detection is paused while the
  photo picker is open, while the app is not in front, and while an import is being loaded.

## v3.5.2 - tool icons
- Tools row: each tool has its own colour - a two-tone icon in a round badge tinted with that colour, name below
  (up to two lines), no card frame. ID card = teal, Password = green; `ToolColors` holds the palette for later tools.

## v3.6.0 - eleven tools
Home -> Tools (scrolls sideways): **ID card, Import images, Import files, PDF to images, Sign, Merge files,
Extract pages, Reorder pages, Lock, Compress, Print**. All offline, own code, no new library.
- `PdfDoc.kt`: reads CamPDF's own PDFs back page by page (original JPEG bytes + exact page geometry), so merge,
  extract, reorder and edit rewrite files **without re-compressing** (renders are pixel-identical, checked with
  poppler; `tools/jvmtest/PdfDocTest.kt`). PDFs from other apps are rendered with Android's PdfRenderer (~200 dpi).
  `PdfPage.box` keeps each page's exact size and image placement.
- Import files: PDFs from the phone become CamPDF documents (password-protected ones are refused with a message).
- PDF to images: one JPEG per page -> share, or save to the gallery (Pictures/CamPDF, Android 10+).
- Sign: opens the chosen document's pages and goes straight to the signature.
- Merge: tap documents in order (also from multi-select on the home list). Extract / Reorder: a page grid -
  tap pages to copy into a new document, or tap them in their new order.
- Compress: smaller copy (for sharing 1600 px / JPEG 70, or smallest 1200 px / JPEG 55); original unchanged.
- Print: Android's print screen (any printer, or "Save as PDF").
- Editing a saved CamPDF document now reuses its original page images (no quality loss from re-rendering).

## v3.7.0 - ID copy screen
Tools -> ID card now opens an ID copy screen first:
- **What is copied**: ID card (NIC) and Driving licence - front + back on one A4 at 85.6 x 54 mm; Passport photo
  page - one per A4 at 125 x 88 mm; Certificate - the whole document fitted to A4. (No bank cards on purpose.)
  The camera asks for the back side only for two-sided cards.
- **A4 example**: our own drawing of exactly where the copies land (same layout code as the PDF, `IdCard.layout`).
- **"For ... only" watermark**: the purpose the user types + today's date ("For bank account opening only ·
  19/11/2026"), repeated diagonally across each copy in grey, in Sinhala / English / Tamil (follows the app
  language). On by default; kind, switch and last purpose are remembered.

## v3.8.0 - tools grid, Tools page, checkboxes, print
- Home: tools in two rows of four - the first seven tools and **All**, which opens a **Tools page** with every
  tool grouped (Scan & import, Convert, Edit, Utilities).
- Document list: a **checkbox is always shown** on each document - tap it to start selecting (long-press still works).
- **Print** added to a document's menu and to the selection bar (several selected documents are joined losslessly
  into one temporary file and printed as one job).

## v3.8.1 - ID copy fixes (phone test)
- ID copies start with the **Lighten** filter (plain, colour-true brightening). Magic / Enhance re-balance the light
  and turned the security print of a laminated NIC strong orange / yellow; Lighten kept the card as it looks.
- ID screen: **Import** button next to Start scanning - take the card photos from the gallery (front first, then back).
- The "For ... only" watermark is **off** by default (the switch is remembered).

## v3.8.2 - visible back button
- The top-bar back arrow and title had no colour set and were drawn black on the dark background (invisible on
  Tools, ID card, Review, Crop, Settings...). The back arrow is now a round button in the theme's text colour, and
  the whole app sits in a Surface so text without an explicit colour follows the theme.

## v3.8.3 - new app icon
- Adaptive icon from the new artwork: a single 432 px WebP (`mipmap-xxxhdpi/ic_launcher_art.webp`, 12 KB) as the
  full-bleed layer - Android scales it for every screen density, so the APK grows by ~12 KB instead of ~1.4 MB for
  the source PNG. Camera + PDF badge sit inside the 66 dp safe circle (looks right as circle, squircle or rounded
  square). The old vector is kept as the monochrome layer for Android 13+ themed icons.
- `fastlane/metadata/android/en-US/images/icon.png` (512 px): store / F-Droid listing icon - not part of the APK.

## v3.9.0 - review fixes (security, reliability, memory, quality)
Security
- **App lock locks again** after the screen was turned off or the app was away for 1 minute (before: only when the
  app was started). The app stays underneath the lock screen, so an open scan or a picker result is not lost.
- With App lock on, the app is hidden in the recent-apps preview and screenshots are blocked (`FLAG_SECURE`).
- **Backup policy**: documents are excluded from cloud backup *and* from the Android 12+ device-to-device copy
  (`res/xml/data_extraction_rules.xml`). Settings -> **Export all documents** copies every PDF to a folder the user
  picks (phone, SD card, USB stick); Tools -> Import files brings them back.
Bugs
- Re-opening an ID copy (Edit pages / Sign) no longer shrinks the card by 4%: full-A4 sheets keep `exactA4`.
- An unsaved scan survives Android closing the app in the background (low-memory phones): it is saved to
  `session.json` after every change and comes back on the Review screen. Leftover page images from a crash are deleted.
- Long documents: `PdfFile` (shared reader) reads objects straight from the file; merge, extract, reorder,
  compress, PDF to images, print and lock now handle **one page at a time** (`PageSource`) instead of the whole file.
- Home: the tools grid scrolls away with the document list.
- After "Create PDF", going back and creating again updates the same file instead of making a copy.
Quality
- `PdfCrypt` and `PdfDoc` use one PDF reader (`PdfFile.kt`).
- GitHub Actions: new **tests** job (PdfWriter / PdfDoc / PdfCrypt / LiveTracker, checked with qpdf + poppler),
  a lint report artifact and the APK size in the run summary (`tools/jvmtest/PdfTests.kt`).
- Screen-reader labels for all icon-only buttons and the shutter.
- Removed unused strings and the Material Components library (theme now `Theme.AppCompat.DayNight.NoActionBar`).

## v3.10.0 - engine upgrade
Measured on the 63 real document photos and 35 background photos used throughout (tools/jvmtest).
- **Black-and-white pages are stored 1 bit per pixel** (`Bitonal.kt`, PDF FlateDecode with the platform's zlib):
  about **12x smaller** than JPEG (e.g. 281 KB -> 23 KB per page) and sharper letters. Low-resolution pages are
  enlarged with smoothing first, so letter edges stay smooth. "PDF to images" gives lossless 1-bit PNGs for them.
- **Compact quality = MRC** (`Mrc.kt`): documents (Magic) are stored as a sharp 1-bit black text layer over a
  half-size JPEG background (paper, stamps, seals, coloured ink stay in colour) - about **2.4x smaller** than one
  JPEG, letters crisper. Merge / extract / reorder / lock keep both layers.
- **Auto filter**: each new page gets the filter that fits it - cards, coloured certificates and photos get Lighten
  (colour-true), documents get Magic (`Imaging.autoFilter`; 17/17 correct on test pages incl. NIC cards).
- **B&W filter**: Sauvola k 0.14 -> 0.22: thinner strokes, small loops of Sinhala handwriting stay open.
- **Sideways pages** are pointed out after cropping (`Imaging.looksSideways`: 0 false alarms on 63 upright pages,
  41/63 sideways pages recognised). Up/down cannot be decided reliably from the layout (forms, handwriting), so the
  app does not rotate by itself.
- **Sub-pixel page edges**: the full-resolution edge fit now places each edge point between pixels (parabolic peak).
- **PageNet v6** (`assets/pagenet.bin`): v5 fine-tuned with "no page here" examples made from real background photos
  (`tools/ml/neg_gen.py`, 2 epochs, lr 4e-4). Backgrounds called a page: v5 13 of 17 -> v6 **0 of 17**, including
  backgrounds never seen in training; documents: validation IoU 0.9729 -> 0.9744, crop corners unchanged on 59/63
  photos (4 moved slightly, none worse), live auto-capture 62/63 (median 1.0 s, slowest 1.5 s).
- **Book mode** (Review -> Book): splits a photographed two-page spread at the spine (gutter shadow / gap between text
  blocks, within 1.5 % on test spreads) and **straightens curved text lines** of each half (`Imaging.dewarpLines`:
  bends of 8 - 14 px reduced to 0 - 3 px on test pages; flat pages are left alone).
- **PDF/A-1b structure** (`PdfA.kt`): XMP metadata, an sRGB ICC output intent (built in code, 508 bytes, checked with
  LittleCMS), file ID, matching dates. No validator is available offline here - check a file with the free veraPDF
  (https://verapdf.org) before relying on it for archiving.
- Writer: object numbers assigned while writing (pages with an MRC layer have 4 objects); catalog written last.

## v3.11.0 - new "PDF created" screen
- Redesigned: glowing check over a soft wave, "PDF created successfully!", a document card (thumbnail with PDF
  badge, file name with a rename pencil, pages / size / date), Export quality tiles (High - best for details,
  Balanced - recommended, Compact - smallest size), a large Share button, Save to device + Open, and Lock with
  password. Done (top right) finishes, the back arrow returns to the pages.
- **Open now shows the PDF in CamPDF's own viewer** (back returns to this screen) instead of handing the file to
  another app.

## v3.12.0 - new look (redesign)

- Home: CamPDF logo header with a soft blue wave, "All Documents" title and subtitle, rounded search,
  tools in glowing colour circles (7 tools + **All Tools**), and **Recent documents** (the 5 newest) with
  **See all →** that opens the full **All documents** screen (sort, search, multi-select, back button).
  Searching on home looks through every document. Rows show date • time • pages • size.
- Review: "Page X of Y" pill, blue corner brackets around the page, a check mark on the chosen thumbnail
  (the red X still deletes it), a "+" tile to add pages, and square tool tiles that scroll sideways
  (Add, Rotate, Crop, Filter, Delete in red, Sign, Book, Left, Right).
- Every screen's top bar has the same wave; Tools are grouped in cards with icon headers.
- The bottom "Import" button label no longer gets cut off. Works on both dark and light themes.
- Back from a document opened in All documents returns to All documents.
