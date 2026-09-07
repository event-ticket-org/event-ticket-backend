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
import javax.imageio.ImageReader;
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

    /** Four bytes a pixel once decoded, so this is the ceiling on one raster: about 200MB. */
    private static final long MAX_PIXELS = 50_000_000L;

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
     * Empty for a format nothing on the classpath reads, for bytes that claimed to be an image
     * and are not, and for a picture with more pixels than we are willing to hold at once.
     *
     * <p>{@code ImageIO.read} answers null rather than throwing when no reader recognises the
     * stream, which is the case that matters here and the one easiest to write past.
     */
    private Optional<BufferedImage> decode(byte[] original) {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(original))) {
            if (stream == null) {
                return Optional.empty();
            }
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream);
                if (tooManyPixels(reader)) {
                    return Optional.empty();
                }
                return Optional.ofNullable(reader.read(0));
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException unreadable) {
            log.info("Cover could not be decoded for rendering, serving it as uploaded: {}",
                    unreadable.toString());
            return Optional.empty();
        }
    }

    /**
     * The header says how big the picture is; reading it costs nothing and reading the picture
     * costs four bytes a pixel.
     *
     * <p>This exists because the upload ceiling bounds the wrong thing. nfr.md caps a cover at
     * five megabytes, and a smooth 12000x8000 JPEG is one and a half - so a file that passes
     * every check this system has can ask the application to allocate roughly 380MB, and a few
     * at once would take down a container sized for a service that never does that. An
     * organizer does not have to mean any harm for that to happen; a camera can produce it.
     *
     * <p>Fifty megapixels is chosen to sit above what a phone or a full-frame camera produces
     * and below what would hurt: it caps one decode at about 200MB. Anything larger is served
     * exactly as uploaded, which is a path that already exists for AVIF and for pictures too
     * small to shrink - so this costs a visitor bandwidth and never a picture.
     */
    private boolean tooManyPixels(ImageReader reader) throws IOException {
        long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
        if (pixels <= MAX_PIXELS) {
            return false;
        }
        log.info("Cover is {} megapixels, past the {} we will decode - serving it as uploaded",
                pixels / 1_000_000, MAX_PIXELS / 1_000_000);
        return true;
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
