package com.trainbooking.ticket.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfObject;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PRStream;
import com.lowagie.text.pdf.PdfArray;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfNumber;
import com.trainbooking.ticket.testsupport.DatabaseTest;
import com.trainbooking.ticket.ticket.service.QrImages;
import com.trainbooking.ticket.ticket.service.TicketPdfRenderer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.time.ZoneId;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** The QR code of a ticket: scannable, drawn the same way on the page and in the PDF, and carrying exactly the token. */
class QrImagesTest {

    private static final String TOKEN = "TB-ABC234.k3J9xQ2mP0aZ.4f6c1a9e7d2b8035c1e4a7f09b3d6c28e5a1f4b7c0d3e6a92b5c8f1d4e7a0b3c";

    private static String decode(BufferedImage image) throws Exception {
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)))).getText();
    }

    @Test
    void theImageDecodesToTheToken_inBothSizes() throws Exception {
        for (int size : new int[] {QrImages.PAGE_SIZE, QrImages.PRINT_SIZE}) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(QrImages.png(TOKEN, size)));
            assertThat(decode(image)).isEqualTo(TOKEN);
            assertThat(image.getWidth()).isEqualTo(image.getHeight()).isLessThanOrEqualTo(size);
        }
        assertThat(ImageIO.read(new ByteArrayInputStream(QrImages.png(TOKEN, QrImages.PAGE_SIZE))).getWidth()).isGreaterThanOrEqualTo(168);
    }

    @Test
    void darkModulesOnWhite_withAQuietZoneOfAtLeastFourModules_andWholePixelModules() throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(QrImages.png(TOKEN, QrImages.PAGE_SIZE)));
        int side = image.getWidth();
        int firstDark = -1;
        int runEnd = -1;
        int middle = side / 2;
        for (int x = 0; x < side; x++) {
            int rgb = image.getRGB(x, middle) & 0xFFFFFF;
            assertThat(rgb == 0x000000 || rgb == 0xFFFFFF).as("only black and white pixels").isTrue();
            if (rgb == 0 && firstDark < 0) firstDark = x;
        }
        // The finder pattern is 7 modules wide on its first dark row: use the first row of dark pixels to measure one module.
        for (int y = 0; y < side && runEnd < 0; y++) {
            int start = -1;
            for (int x = 0; x < side; x++) {
                boolean dark = (image.getRGB(x, y) & 0xFFFFFF) == 0;
                if (dark && start < 0) start = x;
                if (!dark && start >= 0) { runEnd = x - start; break; }
            }
        }
        int module = runEnd / 7;
        assertThat(module * 7).as("the finder pattern is 7 whole modules wide").isEqualTo(runEnd);
        assertThat(side % module).as("whole pixels per module").isZero();
        int firstDarkAnywhere = side;
        for (int y = 0; y < side; y++) for (int x = 0; x < side; x++) if ((image.getRGB(x, y) & 0xFFFFFF) == 0) firstDarkAnywhere = Math.min(firstDarkAnywhere, x);
        assertThat(firstDarkAnywhere).as("quiet zone in pixels").isGreaterThanOrEqualTo(4 * module);
    }

    @Test
    void theQrCodeInThePdf_decodesToTheSameToken() throws Exception {
        byte[] pdf = new TicketPdfRenderer(ZoneId.of("Africa/Cairo"), null).render(DatabaseTest.ticketRequest("TB-ABC234"), TOKEN);
        PdfReader reader = new PdfReader(pdf);
        try {
            var images = reader.getPageN(1).getAsDict(PdfName.RESOURCES).getAsDict(PdfName.XOBJECT);
            PdfObject object = PdfReader.getPdfObject(images.get(images.getKeys().iterator().next()));
            assertThat(decode(imageOf((PRStream) object))).isEqualTo(TOKEN);
        } finally {
            reader.close();
        }
    }

    /** Rebuilds the picture from the image object of the PDF: raw samples: 1 bit, or 8 bits grey, palette or RGB (what the library makes of the PNG). */
    private static BufferedImage imageOf(PRStream stream) throws Exception {
        byte[] data = PdfReader.getStreamBytes(stream);
        int width = ((PdfNumber) stream.get(PdfName.WIDTH)).intValue();
        int height = ((PdfNumber) stream.get(PdfName.HEIGHT)).intValue();
        int bits = ((PdfNumber) stream.get(PdfName.BITSPERCOMPONENT)).intValue();
        PdfObject space = PdfReader.getPdfObject(stream.get(PdfName.COLORSPACE));
        byte[] palette = null;
        if (space instanceof PdfArray array) {
            PdfObject lookup = PdfReader.getPdfObject(array.getPdfObject(3));
            palette = lookup instanceof PRStream s ? PdfReader.getStreamBytes(s) : com.lowagie.text.pdf.PdfString.class.cast(lookup).getBytes();
        }
        int components = bits == 8 && data.length == width * height * 3 ? 3 : 1;
        int rowBytes = (width * bits * components + 7) / 8;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int value = bits == 1 ? (data[y * rowBytes + x / 8] >> (7 - x % 8)) & 1 : data[y * rowBytes + x * components] & 0xFF;
                int grey;
                if (palette != null) grey = palette[value * 3] & 0xFF;
                else grey = bits == 1 ? value * 255 : value;
                image.setRGB(x, y, (grey << 16) | (grey << 8) | grey);
            }
        }
        return image;
    }
}
