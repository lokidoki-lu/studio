/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.core.v1.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Image conversion limits")
class ImageConversionLimitsTest {

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY), "png", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("converts an ordinary 320x240 image")
    void convertsOrdinaryImage() throws IOException {
        assertTrue(ImageConversion.anyToBitmap(png(320, 240)).length > 0);
    }

    @Test
    @DisplayName("refuses an image wider than 4096 pixels, from its header")
    void refusesOversizedImage() throws IOException {
        byte[] wide = png(ImageConversion.MAX_DIMENSION + 1, 1);

        IOException thrown = assertThrows(IOException.class, () -> ImageConversion.anyToBitmap(wide));
        assertTrue(thrown.getMessage().contains("dimensions"), thrown.getMessage());
        assertThrows(IOException.class, () -> ImageConversion.anyToRLECompressedBitmap(wide));
    }

    @Test
    @DisplayName("fails with an IOException, not a NullPointerException, on bytes no reader understands")
    void refusesGarbage() {
        assertThrows(IOException.class, () -> ImageConversion.anyToBitmap(new byte[]{1, 2, 3, 4}));
        assertThrows(IOException.class, () -> ImageConversion.bitmapToPng(new byte[0]));
    }
}
