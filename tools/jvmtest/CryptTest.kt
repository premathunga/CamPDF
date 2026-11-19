// v3.5 PdfCrypt test (PC): writes a PDF from JPEGs with PdfWriter, locks it with PdfCrypt, then check with
// independent readers, e.g.:
//   kotlinc CryptTest.kt ../../app/src/main/java/com/campdf/app/PdfCrypt.kt ../../app/src/main/java/com/campdf/app/PdfWriter.kt -include-runtime -d c.jar
//   java -cp c.jar CryptTestKt plain.pdf locked.pdf a.jpg b.jpg
//   qpdf --password='සිංහල pass 123' --check locked.pdf        (R = 6, AES-256, no errors)
//   pdftoppm -upw 'සිංහල pass 123' -r 20 -png locked.pdf r     (same pixels as plain.pdf)
//   qpdf --password=wrong --check locked.pdf                     (invalid password)
import com.campdf.app.PdfCrypt
import com.campdf.app.PdfPage
import com.campdf.app.PdfWriter
import java.io.File

fun main(a: Array<String>) {
    val jpgs = a.drop(2).map { File(it) }
    val src = File(a[0])
    PdfWriter.write(src, jpgs.size) { i ->
        val img = javax.imageio.ImageIO.read(jpgs[i])
        PdfPage(jpgs[i].readBytes(), img.width, img.height)
    }
    PdfCrypt.lock(src, File(a[1]), "සිංහල pass 123")
    println("locked: " + a[1])
}
