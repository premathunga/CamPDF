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
