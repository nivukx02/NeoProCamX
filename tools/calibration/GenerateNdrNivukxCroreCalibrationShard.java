import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import javax.imageio.ImageIO;

public final class GenerateNdrNivukxCroreCalibrationShard {
    private static final String VERSION = "NDR_NIVUKX_CRORE_IMAGE_CORPUS_V1";
    private static final long IMAGE_COUNT = 100_000_000L;
    private static final int GRID_W = 8;
    private static final int GRID_H = 8;
    private static final int PIXELS_PER_IMAGE = GRID_W * GRID_H;
    private static final long TOTAL_PIXEL_SAMPLES = IMAGE_COUNT * (long) PIXELS_PER_IMAGE;
    private static final int PATCH_SIZE = 96;
    private static final int MARGIN = 56;
    private static final int GAP = 8;
    private static final int WIDTH = MARGIN * 2 + GRID_W * PATCH_SIZE + (GRID_W - 1) * GAP;
    private static final int HEIGHT = MARGIN * 2 + GRID_H * PATCH_SIZE + (GRID_H - 1) * GAP;
    private static final double GOLDEN_RATIO_CONJUGATE = 0.6180339887498949;
    private static final String[] ROLES = {
        "NEUTRAL_RAMP",
        "SKIN_MEMORY",
        "SKY_GRADIENT",
        "HIGHLIGHT_ROLLOFF",
        "SHADOW_CHROMA",
        "FOLIAGE_MEMORY",
        "SATURATION_BOUNDARY",
        "WB_ILLUMINANT",
        "RED_FACE_GUARD",
        "CYAN_GREEN_GUARD",
        "TEXTURE_DETAIL",
        "LOW_NOISE_DARK"
    };

    private GenerateNdrNivukxCroreCalibrationShard() {
    }

    public static void main(String[] args) throws IOException {
        long startImage = args.length > 0 ? Long.parseLong(args[0]) : 0L;
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 16;
        Path outDir = args.length > 2
            ? Paths.get(args[2])
            : Paths.get("deliverables", "ndr_nivukx_crore_sample");
        Files.createDirectories(outDir);

        long safeStart = clamp(startImage, 0L, IMAGE_COUNT - 1L);
        int safeCount = Math.max(1, count);
        StringBuilder manifest = new StringBuilder(8192);
        manifest.append("{\n")
            .append("  \"version\": \"").append(VERSION).append("\",\n")
            .append("  \"image_count\": ").append(IMAGE_COUNT).append(",\n")
            .append("  \"total_pixel_samples\": ").append(TOTAL_PIXEL_SAMPLES).append(",\n")
            .append("  \"materialized_start_image\": ").append(safeStart).append(",\n")
            .append("  \"materialized_count\": ").append(safeCount).append(",\n")
            .append("  \"images\": [\n");

        for (int i = 0; i < safeCount; i++) {
            long imageIndex = (safeStart + i) % IMAGE_COUNT;
            String fileName = String.format(Locale.US, "ndr_nivukx_crore_%09d.png", imageIndex);
            ImageIO.write(renderImage(imageIndex), "png", outDir.resolve(fileName).toFile());
            manifest.append("    { \"image_index\": ").append(imageIndex)
                .append(", \"file\": \"").append(fileName).append("\" }")
                .append(i + 1 == safeCount ? "\n" : ",\n");
        }

        manifest.append("  ]\n}\n");
        Files.write(outDir.resolve("ndr_nivukx_crore_shard_manifest.json"), manifest.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static BufferedImage renderImage(long imageIndex) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(new Color(18, 18, 18));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        for (int y = 0; y < GRID_H; y++) {
            for (int x = 0; x < GRID_W; x++) {
                long globalPixel = imageIndex * (long) PIXELS_PER_IMAGE + y * (long) GRID_W + x;
                Rgb rgb = colorAtGlobalIndex(globalPixel);
                int left = MARGIN + x * (PATCH_SIZE + GAP);
                int top = MARGIN + y * (PATCH_SIZE + GAP);
                g.setColor(new Color(to8bit(rgb.r), to8bit(rgb.g), to8bit(rgb.b)));
                g.fillRect(left, top, PATCH_SIZE, PATCH_SIZE);
            }
        }

        g.dispose();
        return image;
    }

    private static Rgb colorAtGlobalIndex(long globalIndex) {
        long safeIndex = clamp(globalIndex, 0L, TOTAL_PIXEL_SAMPLES - 1L);
        String role = ROLES[(int) (safeIndex % ROLES.length)];
        float t = radicalInverseBase2(safeIndex / ROLES.length);
        float u = fract((safeIndex + 1L) * GOLDEN_RATIO_CONJUGATE);
        float v = fract((safeIndex + 17L) * 0.4142135623730950);
        return colorFor(role, t, u, v).sanitized();
    }

    private static Rgb colorFor(String role, float t, float u, float v) {
        switch (role) {
            case "NEUTRAL_RAMP": {
                float gray = pow(t, 1.72f);
                return new Rgb(gray, gray, gray);
            }
            case "SKIN_MEMORY":
                return interpolateSkin(t, u);
            case "SKY_GRADIENT": {
                float haze = u * 0.055f;
                return new Rgb(
                    lerp(0.135f, 0.755f, t) + haze * 0.70f,
                    lerp(0.300f, 0.875f, t) + haze * 0.38f,
                    Math.max(lerp(0.780f, 1.000f, t) - haze * 0.18f, 0.62f)
                );
            }
            case "HIGHLIGHT_ROLLOFF": {
                Rgb base;
                switch (Math.max(0, Math.min(7, (int) (u * 8f)))) {
                    case 0: base = new Rgb(1.000f, 1.000f, 1.000f); break;
                    case 1: base = new Rgb(0.960f, 0.970f, 0.975f); break;
                    case 2: base = new Rgb(0.900f, 0.915f, 0.920f); break;
                    case 3: base = new Rgb(0.980f, 0.920f, 0.820f); break;
                    case 4: base = new Rgb(0.850f, 0.920f, 1.000f); break;
                    case 5: base = new Rgb(0.980f, 0.560f, 0.500f); break;
                    case 6: base = new Rgb(0.720f, 0.950f, 0.940f); break;
                    default: base = new Rgb(0.690f, 0.820f, 0.990f); break;
                }
                return scale(base, lerp(0.72f, 1.00f, t));
            }
            case "SHADOW_CHROMA":
                return hsvToRgb(u, lerp(0.14f, 0.70f, v), 0.004f + 0.165f * pow(t, 1.35f));
            case "FOLIAGE_MEMORY":
                return new Rgb(
                    lerp(0.035f, 0.360f, t),
                    lerp(0.125f, 0.640f, pow(t, 0.82f)),
                    lerp(0.030f, 0.220f, t) * (1f - 0.22f * u)
                );
            case "SATURATION_BOUNDARY":
                return hsvToRgb(u, lerp(0.42f, 1.00f, t), lerp(0.32f, 1.00f, v));
            case "WB_ILLUMINANT": {
                float gray = lerp(0.035f, 0.960f, t);
                Rgb cast;
                switch (Math.max(0, Math.min(4, (int) (u * 5f)))) {
                    case 0: cast = new Rgb(1.00f, 0.86f, 0.66f); break;
                    case 1: cast = new Rgb(1.00f, 0.94f, 0.82f); break;
                    case 2: cast = new Rgb(1.00f, 1.00f, 1.00f); break;
                    case 3: cast = new Rgb(0.86f, 0.94f, 1.00f); break;
                    default: cast = new Rgb(0.74f, 0.88f, 1.00f); break;
                }
                return scale(cast, gray);
            }
            case "RED_FACE_GUARD":
                return new Rgb(
                    lerp(0.420f, 0.985f, t),
                    lerp(0.028f, 0.560f, pow(t, 0.90f)),
                    lerp(0.028f, 0.500f, pow(t, 1.15f))
                );
            case "CYAN_GREEN_GUARD":
                return new Rgb(
                    lerp(0.030f, 0.720f, t),
                    lerp(0.360f, 0.980f, t),
                    lerp(0.300f, 1.000f, pow(t, 0.82f))
                );
            case "TEXTURE_DETAIL": {
                float base = lerp(0.070f, 0.900f, t);
                float warm = (u - 0.5f) * 0.050f;
                return new Rgb(base + warm, base, base - warm);
            }
            case "LOW_NOISE_DARK": {
                float base = 0.0015f + 0.105f * pow(t, 1.55f);
                return new Rgb(base * (1.0f + u * 0.26f), base, base * (1.0f + (1.0f - u) * 0.22f));
            }
            default:
                throw new IllegalArgumentException("Unsupported role " + role);
        }
    }

    private static Rgb interpolateSkin(float t, float u) {
        Rgb[] anchors = {
            new Rgb(0.220f, 0.115f, 0.075f),
            new Rgb(0.360f, 0.210f, 0.145f),
            new Rgb(0.560f, 0.350f, 0.260f),
            new Rgb(0.760f, 0.560f, 0.450f),
            new Rgb(0.930f, 0.720f, 0.610f),
            new Rgb(0.620f, 0.225f, 0.245f)
        };
        float scaled = t * anchors.length;
        int index = Math.max(0, Math.min(anchors.length - 1, (int) Math.floor(scaled)));
        int next = Math.min(index + 1, anchors.length - 1);
        float local = clamp01(scaled - index);
        float warmth = (u - 0.5f) * 0.045f;
        return new Rgb(
            lerp(anchors[index].r, anchors[next].r, local) + warmth,
            lerp(anchors[index].g, anchors[next].g, local),
            lerp(anchors[index].b, anchors[next].b, local) - warmth * 0.45f
        );
    }

    private static Rgb hsvToRgb(float hue, float saturation, float value) {
        float h = ((hue % 1f) + 1f) % 1f;
        float s = clamp01(saturation);
        float v = clamp01(value);
        float c = v * s;
        float hp = h * 6f;
        float x = c * (1f - Math.abs((hp % 2f) - 1f));
        float rp;
        float gp;
        float bp;
        if (hp < 1f) {
            rp = c; gp = x; bp = 0f;
        } else if (hp < 2f) {
            rp = x; gp = c; bp = 0f;
        } else if (hp < 3f) {
            rp = 0f; gp = c; bp = x;
        } else if (hp < 4f) {
            rp = 0f; gp = x; bp = c;
        } else if (hp < 5f) {
            rp = x; gp = 0f; bp = c;
        } else {
            rp = c; gp = 0f; bp = x;
        }
        float m = v - c;
        return new Rgb(rp + m, gp + m, bp + m);
    }

    private static float radicalInverseBase2(long value) {
        long bits = value;
        float result = 0f;
        float fraction = 0.5f;
        while (bits > 0L) {
            result += fraction * (float) (bits & 1L);
            bits >>>= 1;
            fraction *= 0.5f;
        }
        return result;
    }

    private static float fract(double value) {
        return (float) (value - Math.floor(value));
    }

    private static Rgb scale(Rgb rgb, float amount) {
        return new Rgb(rgb.r * amount, rgb.g * amount, rgb.b * amount);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * clamp01(t);
    }

    private static float pow(float value, float exponent) {
        return (float) Math.pow(value, exponent);
    }

    private static long clamp(long value, long low, long high) {
        return Math.max(low, Math.min(high, value));
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static int to8bit(float value) {
        return Math.max(0, Math.min(255, Math.round(clamp01(value) * 255f)));
    }

    private static final class Rgb {
        final float r;
        final float g;
        final float b;

        Rgb(float r, float g, float b) {
            this.r = r;
            this.g = g;
            this.b = b;
        }

        Rgb sanitized() {
            return new Rgb(clamp01(r), clamp01(g), clamp01(b));
        }
    }
}
