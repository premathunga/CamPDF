Run the REAL Imaging.kt / DocDetect.kt on a PC (no Android SDK needed): small JVM stubs for android.graphics.*.
The stubs are only for testing (bilinear resampling approximates Android's); they are never part of the app build.

Build (Kotlin compiler 2.x, JDK 17):
  S=app/src/main/java/com/campdf/app
  kotlinc tools/jvmtest/stubs/*.kt $S/Imaging.kt $S/DocDetect.kt $S/LineQuad.kt $S/PageNet.kt $S/Models.kt tools/jvmtest/Main.kt -include-runtime -d t.jar
Run (auto corner detection, writes <prefix>_quad.jpg with the detected page + one PNG per filter):
  java -Xmx3g -jar t.jar photo.jpg out/photo MAGIC,BW,ENHANCE,NO_SHADOW
Tuning knobs (optional): -DbgDim=400 -DbgDil=2 -DbgMed=7 -DbgMode=1 -DbwK=0.14 -DbwWin=22 -Dglo=30 -Dgsp=40 -Digm=2.4
Detection only, many photos at once (writes <name>_quad.jpg into outDir, prints the score):
  kotlinc tools/jvmtest/stubs/*.kt $S/Imaging.kt $S/DocDetect.kt $S/LineQuad.kt $S/PageNet.kt $S/Models.kt tools/jvmtest/Detect.kt -include-runtime -d d.jar
  java -Xmx3g -jar d.jar outDir photo1.jpg photo2.jpg ...
Keep real test photos in /tune (git-ignored) - never commit private documents.
Note: the EXIF stub ignores orientation, so pass photos that are already upright.
  Optional network: add -Dmodel=tools/ml/pagenet.bin (Detect.kt / Main.kt); -Dnonet=1 disables it.
