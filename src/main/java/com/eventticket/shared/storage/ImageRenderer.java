package com.eventticket.shared.storage;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Smaller copies of a picture, so a client fetches the one it will draw
 * (requirements/003 criterion 22).
 *
 * <p>Everything here is best-effort by design. A rendering that cannot be made is a visitor
 * paying in bandwidth; a rendering that threw would be a visitor with no picture and an
 * organizer with a failed upload, which is a worse trade for an optimisation. So this answers
 * with what it managed and never with an exception.
 */
@Component
public class ImageRenderer {

    private static final Logger log = LoggerFactory.getLogger(ImageRenderer.class);

    /**
     * What a rendering came out as, which is not always what went in.
     *
     * <p>The type is decided here rather than inherited: there is a WebP <em>reader</em> on the
     * classpath and no writer, so a WebP cover is decoded and written back as something else.
     */
    public record Rendering(int width, byte[] content, ImageType type) {}

    /**
     * Renderings at each requested width that is genuinely smaller than the original, largest
     * first is not the point - the caller sorts - but smaller-only is.
     *
     * <p>Nothing is enlarged. An upscale invents detail and charges bandwidth for the
     * invention, and a 400px upload asked for at 1280 would be a bigger file that looks worse
     * than the one it replaced.
     *
     * <p>Empty is an ordinary answer: a small upload has nothing smaller, and AVIF has no
     * decoder on any classpath this would want (ADR-0006).
     */
    public List<Rendering> renderingsOf(byte[] original, int... widths) {
        Optional<BufferedImage> decoded = decode(original);
        if (decoded.isEmpty()) {
            return List.of();
        }
        BufferedImage source = decoded.get();

        // Transparency survives or it does not, and that decides the format for every
        // rendering: JPEG has no alpha channel, so a cover with one would come back with the
        // transparent parts filled black.
        ImageType type = source.getColorModel().hasAlpha() ? ImageType.PNG : ImageType.JPEG;

        var renderings = new ArrayList<Rendering>();
        for (int width : widths) {
            if (width >= source.getWidth()) {
                continue;
            }
            encode(scale(source, width), type).ifPresent(
                    content -> renderings.add(new Rendering(width, content, type)));
        }
        return List.copyOf(renderings);
    }

    /**
     * Empty for a format nothing on the classpath reads, and for bytes that claimed to be an
     * image and are not.
     *
     * <p>{@code ImageIO.read} answers null rather than throwing when no reader recognises the
     * stream, which is the case that matters here and the one easiest to write past.
     */
    private Optional<BufferedImage> decode(byte[] original) {
        try {
            return Optional.ofNullable(ImageIO.read(new ByteArrayInputStream(original)));
        } catch (IOException | RuntimeException unreadable) {
            log.info("Cover could not be decoded for rendering, serving it as uploaded: {}",
                    unreadable.toString());
            return Optional.empty();
        }
    }

    private static BufferedImage scale(BufferedImage source, int width) {
        int height = Math.max(1, Math.round(width * (float) source.getHeight() / source.getWidth()));
        // TYPE_INT_RGB for the opaque case, or the JPEG writer receives an alpha channel it
        // cannot encode and writes a file browsers render with inverted colours.
        BufferedImage target = new BufferedImage(width, height,
                source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB
                        : BufferedImage.TYPE_INT_RGB);

        Graphics2D canvas = target.createGraphics();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            canvas.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            canvas.drawImage(source, 0, 0, width, height, null);
        } finally {
            canvas.dispose();
        }
        return target;
    }

    private Optional<byte[]> encode(BufferedImage image, ImageType type) {
        var out = new ByteArrayOutputStream();
        try {
            return ImageIO.write(image, type.extension(), out)
                    ? Optional.of(out.toByteArray())
                    : Optional.empty();
        } catch (IOException | RuntimeException failed) {
            log.warn("Could not encode a cover rendering as {}", type, failed);
            return Optional.empty();
        }
    }
}
