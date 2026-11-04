package com.trainbooking.ticket.ticket.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Draws a QR code as a PNG: dark modules on white, a quiet zone of {@value #QUIET_ZONE_MODULES} modules (the minimum the standard asks for),
 * and every module a whole number of pixels, so the image is crisp and is never resampled by this code. The ticket page and the PDF use this one
 * method for the same token; only the size differs.
 */
public final class QrImages {

    /** Modules of white around the code. */
    public static final int QUIET_ZONE_MODULES = 4;
    /** The size asked for the image on the ticket page (about 6 pixels per module; shown at its own size). */
    public static final int PAGE_SIZE = 280;
    /** The size asked for the PDF: many pixels per module, so the print stays sharp when the page is scaled down. */
    public static final int PRINT_SIZE = 900;

    private QrImages() {
    }

    /** At most {@code size} pixels wide and high (the largest whole number of pixels per module that fits). */
    public static byte[] png(String content, int size) {
        try {
            // Asking for a 1 x 1 image gives the code at one pixel per module, quiet zone included.
            BitMatrix modules = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 1, 1,
                    Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, QUIET_ZONE_MODULES));
            int scale = Math.max(1, size / modules.getWidth());
            int side = modules.getWidth() * scale;
            BufferedImage image = new BufferedImage(side, side, BufferedImage.TYPE_BYTE_BINARY);
            for (int y = 0; y < side; y++) {
                for (int x = 0; x < side; x++) {
                    image.setRGB(x, y, modules.get(x / scale, y / scale) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            throw new IllegalStateException("The QR code could not be drawn.", e);
        }
    }
}
