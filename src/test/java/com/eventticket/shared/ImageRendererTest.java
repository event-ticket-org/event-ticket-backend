package com.eventticket.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.shared.storage.ImageRenderer;
import com.eventticket.shared.storage.ImageType;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * requirements/003 criterion 22, and the half of it that is arithmetic rather than plumbing.
 *
 * <p>Real images through a real encoder: what is being checked is that the bytes coming out
 * are an image of the right size, and a mocked ImageIO would assert that the mock was
 * configured the way the test configured it.
 */
class ImageRendererTest {

    private final ImageRenderer renderer = new ImageRenderer();

    @Test
    @DisplayName("renders each requested width, and reports what it produced")
    void rendersTheRequestedWidths() throws IOException {
        byte[] poster = jpeg(1600, 900);

        var renderings = renderer.renderingsOf(poster, 320, 640, 1280);

        assertThat(renderings).extracting(ImageRenderer.Rendering::width)
                .containsExactly(320, 640, 1280);
        for (var rendering : renderings) {
            assertThat(decode(rendering.content()).getWidth()).isEqualTo(rendering.width());
            // The point of the exercise: a rendering that is not smaller than the file it came
            // from would be work done to save nothing.
            assertThat(rendering.content().length).isLessThan(poster.length);
        }
    }

    @Test
    @DisplayName("the aspect ratio survives, so nothing is stretched")
    void theAspectRatioIsKept() throws IOException {
        var rendering = renderer.renderingsOf(jpeg(1600, 900), 320).get(0);

        BufferedImage out = decode(rendering.content());
        assertThat(out.getWidth()).isEqualTo(320);
        assertThat(out.getHeight()).isEqualTo(180);
    }

    /**
     * Nothing is enlarged. An upscale invents detail and charges bandwidth for the invention,
     * so a small upload simply has fewer renderings - and an Event may end up with none, which
     * is why the contract says the list can be empty.
     */
    @Test
    @DisplayName("a small original is never enlarged")
    void nothingIsEnlarged() throws IOException {
        assertThat(renderer.renderingsOf(jpeg(400, 225), 320, 640, 1280))
                .extracting(ImageRenderer.Rendering::width)
                .containsExactly(320);

        assertThat(renderer.renderingsOf(jpeg(200, 120), 320, 640, 1280)).isEmpty();
    }

    /**
     * JPEG has no alpha channel, so a cover with transparency would come back with the
     * transparent parts filled in - black, on most encoders. PNG for those, JPEG for the
     * photographs that everything else is.
     */
    @Test
    @DisplayName("transparency decides the format, so an alpha cover is not filled in")
    void transparencyIsKept() throws IOException {
        var transparent = renderer.renderingsOf(pngWithAlpha(800, 450), 320).get(0);
        assertThat(transparent.type()).isEqualTo(ImageType.PNG);
        assertThat(decode(transparent.content()).getColorModel().hasAlpha()).isTrue();

        assertThat(renderer.renderingsOf(jpeg(800, 450), 320).get(0).type())
                .isEqualTo(ImageType.JPEG);
    }

    /**
     * The behaviour ADR-0006 promises for AVIF, checked with bytes no reader recognises: an
     * optimisation that cannot be performed costs a visitor bandwidth, never a picture. It
     * must not throw, because the caller is in the middle of accepting somebody's upload.
     */
    @Test
    @DisplayName("a format nothing can read produces nothing, and no exception")
    void anUndecodableFormatIsSilent() {
        assertThat(renderer.renderingsOf("not an image at all".getBytes(StandardCharsets.UTF_8),
                320, 640)).isEmpty();
        assertThat(renderer.renderingsOf(new byte[0], 320)).isEmpty();
    }

    // ---- images to work on ----

    private static byte[] jpeg(int width, int height) throws IOException {
        return encode(paint(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)), "jpg");
    }

    private static byte[] pngWithAlpha(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        return encode(paint(image), "png");
    }

    /** Detail rather than a flat fill, so a scaled copy is not compressible to nothing. */
    private static BufferedImage paint(BufferedImage image) {
        Graphics2D canvas = image.createGraphics();
        for (int x = 0; x < image.getWidth(); x += 7) {
            canvas.setColor(new Color((x * 37) % 255, (x * 11) % 255, (x * 53) % 255));
            canvas.fillRect(x, 0, 7, image.getHeight());
        }
        canvas.dispose();
        return image;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }

    private static BufferedImage decode(byte[] content) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(content));
    }
}
