import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

public final class GenerateNdrNivukxCalibrationTarget2048 {
    private static final String VERSION = "NDR_NIVUKX_ELITE_TONE_COLOR_REFERENCE_2048_V1";
    private static final int WIDTH = 3840;
    private static final int HEIGHT = 2160;
    private static final int PATCH_COUNT = 2048;
    private static final int PATCHES_PER_PAGE = 8;
    private static final int PAGES = PATCH_COUNT / PATCHES_PER_PAGE;
    private static final int ROWS = 2;
    private static final int COLS = 4;
    private static final int LEFT = 240;
    private static final int TOP = 560;
    private static final int BOTTOM = 260;
    private static final int GAP_X = 120;
    private static final int GAP_Y = 90;
    private static final int PATCH_W = (WIDTH - LEFT * 2 - GAP_X * (COLS - 1)) / COLS;
    private static final int PATCH_H = (HEIGHT - TOP - BOTTOM - GAP_Y) / ROWS;
    private static final float SAMPLE_LEFT = 0.22f;
    private static final float SAMPLE_TOP = 0.30f;
    private static final float SAMPLE_RIGHT = 0.80f;
    private static final float SAMPLE_BOTTOM = 0.74f;

    private static final class Patch {
        final int index;
        final int page;
        final int slot;
        final String id;
        final String label;
        final String role;
        final float r;
        final float g;
        final float b;

        Patch(int index, String role, String label, float r, float g, float b) {
            this.index = index;
            this.page = index / PATCHES_PER_PAGE + 1;
            this.slot = index % PATCHES_PER_PAGE;
            this.id = String.format(Locale.US, "%s_%04d", role.toLowerCase(Locale.US), index + 1);
            this.label = label;
            this.role = role;
            this.r = clamp01(r);
            this.g = clamp01(g);
            this.b = clamp01(b);
        }

        int row() {
            return slot / COLS;
        }

        int col() {
            return slot % COLS;
        }
    }

    private static final class PatchBuilder {
        final List<Patch> patches = new ArrayList<>(PATCH_COUNT);

        void add(String role, String label, float r, float g, float b) {
            patches.add(new Patch(patches.size(), role, label, r, g, b));
        }
    }

    public static void main(String[] args) throws IOException {
        Path assetDir = Paths.get("app", "src", "main", "assets", "calibration", "ndr_nivukx_2048");
        Path deliverableDir = Paths.get("deliverables", "ndr_nivukx_2048");
        Files.createDirectories(assetDir);
        Files.createDirectories(deliverableDir);

        List<Patch> patches = buildPatches();
        if (patches.size() != PATCH_COUNT) {
            throw new IllegalStateException("Expected 2048 patches, got " + patches.size());
        }

        for (int page = 1; page <= PAGES; page++) {
            BufferedImage image = renderPage(page, patches);
            String file = pageFile(page);
            ImageIO.write(image, "png", assetDir.resolve(file).toFile());
            Files.copy(assetDir.resolve(file), deliverableDir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }

        String reference = buildReferenceJson(patches);
        Files.write(assetDir.resolve("ndr_nivukx_2048_reference.json"), reference.getBytes(StandardCharsets.UTF_8));
        Files.write(deliverableDir.resolve("ndr_nivukx_2048_reference.json"), reference.getBytes(StandardCharsets.UTF_8));
        Files.write(deliverableDir.resolve("capture_instructions.md"), buildInstructions().getBytes(StandardCharsets.UTF_8));
    }

    private static List<Patch> buildPatches() {
        PatchBuilder out = new PatchBuilder();
        addNeutralRamp(out, 192);
        addSkinMemory(out, 192);
        addSkyGradient(out, 192);
        addHighlightRolloff(out, 192);
        addShadowChroma(out, 192);
        addFoliageMemory(out, 160);
        addSaturationBoundary(out, 320);
        addWhiteBalanceIlluminants(out, 160);
        addRedFaceGuard(out, 128);
        addCyanGreenGuard(out, 128);
        addTextureDetail(out, 96);
        addLowNoiseDark(out, 96);
        return out.patches;
    }

    private static void addNeutralRamp(PatchBuilder out, int count) {
        for (int i = 0; i < count; i++) {
            float t = i / (float) (count - 1);
            float v = (float) Math.pow(t, 1.65);
            out.add("NEUTRAL_RAMP", "N" + i, v, v, v);
        }
    }

    private static void addSkinMemory(PatchBuilder out, int count) {
        float[][] anchors = {
            {0.22f, 0.115f, 0.075f},
            {0.36f, 0.210f, 0.145f},
            {0.56f, 0.350f, 0.260f},
            {0.76f, 0.560f, 0.450f},
            {0.93f, 0.720f, 0.610f},
            {0.62f, 0.225f, 0.245f}
        };
        for (int i = 0; i < count; i++) {
            float t = i / (float) count;
            int base = (int) Math.floor(t * anchors.length) % anchors.length;
            int next = (base + 1) % anchors.length;
            float local = t * anchors.length - (float) Math.floor(t * anchors.length);
            float warmth = (float) Math.sin(i * 0.6180339887) * 0.022f;
            float r = lerp(anchors[base][0], anchors[next][0], local) + warmth;
            float g = lerp(anchors[base][1], anchors[next][1], local);
            float b = lerp(anchors[base][2], anchors[next][2], local) - warmth * 0.45f;
            out.add("SKIN_MEMORY", "SK" + i, r, g, b);
        }
    }

    private static void addSkyGradient(PatchBuilder out, int count) {
        for (int i = 0; i < count; i++) {
            float t = i / (float) (count - 1);
            float haze = (i % 16) / 15.0f;
            float r = lerp(0.145f, 0.760f, t) + haze * 0.035f;
            float g = lerp(0.310f, 0.880f, t) + haze * 0.018f;
            float b = lerp(0.760f, 1.000f, t) - haze * 0.020f;
            out.add("SKY_GRADIENT", "SKY" + i, r, g, b);
        }
    }

    private static void addHighlightRolloff(PatchBuilder out, int count) {
        float[][] colors = {
            {1.00f, 1.00f, 1.00f},
            {0.96f, 0.97f, 0.975f},
            {0.90f, 0.915f, 0.920f},
            {0.98f, 0.92f, 0.82f},
            {0.85f, 0.92f, 1.00f},
            {0.98f, 0.56f, 0.50f},
            {0.72f, 0.95f, 0.94f},
            {0.69f, 0.82f, 0.99f}
        };
        for (int i = 0; i < count; i++) {
            float exposure = (i % 24) / 23.0f;
            float[] c = colors[(i / 24) % colors.length];
            float lift = lerp(0.74f, 1.0f, exposure);
            out.add("HIGHLIGHT_ROLLOFF", "HI" + i, c[0] * lift, c[1] * lift, c[2] * lift);
        }
    }

    private static void addShadowChroma(PatchBuilder out, int count) {
        for (int i = 0; i < count; i++) {
            float hue = (i % 48) / 48.0f;
            float value = 0.012f + 0.155f * ((i / 48) / 3.0f);
            float sat = 0.12f + 0.52f * ((i % 12) / 11.0f);
            float[] rgb = hsbToRgb(hue, sat, value);
            out.add("SHADOW_CHROMA", "SH" + i, rgb[0], rgb[1], rgb[2]);
        }
    }

    private static void addFoliageMemory(PatchBuilder out, int count) {
        for (int i = 0; i < count; i++) {
            float t = i / (float) (count - 1);
            float r = lerp(0.035f, 0.360f, t);
            float g = lerp(0.120f, 0.620f, (float) Math.pow(t, 0.85));
            float b = lerp(0.030f, 0.220f, t) * (1.0f - 0.25f * (i % 5) / 4.0f);
            out.add("FOLIAGE_MEMORY", "FOL" + i, r, g, b);
        }
    }

    private static void addSaturationBoundary(PatchBuilder out, int count) {
        for (int i = 0; i < count; i++) {
            float hue = (i % 64) / 64.0f;
            float sat = 0.40f + 0.60f * ((i / 64) % 5) / 4.0f;
            float value = 0.34f + 0.66f * ((i / 320) + ((i / 16) % 4) / 4.0f);
            value = clamp01(value);
            float[] rgb = hsbToRgb(hue, sat, value);
            out.add("SATURATION_BOUNDARY", "SAT" + i, rgb[0], rgb[1], rgb[2]);
        }
    }

    private static void addWhiteBalanceIlluminants(PatchBuilder out, int count) {
        float[][] casts = {
            {1.00f, 0.86f, 0.66f},
            {1.00f, 0.94f, 0.82f},
            {1.00f, 1.00f, 1.00f},
            {0.86f, 0.94f, 1.00f},
            {0.74f, 0.88f, 1.00f}
        };
        for (int i = 0; i < count; i++) {
            float gray = 0.06f + 0.90f * ((i % 32) / 31.0f);
            float[] cast = casts[(i / 32) % casts.length];
            out.add("WB_ILLUMINANT", "WB" + i, gray * cast[0], gray * cast[1], gray * cast[2]);
        }
    }

    private static void addRedFaceGuard(PatchBuilder out, int count) {
        for (int i = 0; i < count; i++) {
            float t = i / (float) (count - 1);
            float r = lerp(0.42f, 0.98f, t);
            float g = lerp(0.028f, 0.56f, (float) Math.pow(t, 0.9));
            float b = lerp(0.028f, 0.50f, (float) Math.pow(t, 1.15));
            out.add("RED_FACE_GUARD", "RFG" + i, r, g, b);
        }
    }

    private static void addCyanGreenGuard(PatchBuilder out, int count) {
        for (int i = 0; i < count; i++) {
            float t = i / (float) (count - 1);
            float r = lerp(0.035f, 0.72f, t);
            float g = lerp(0.36f, 0.98f, t);
            float b = lerp(0.30f, 1.00f, (float) Math.pow(t, 0.82));
            out.add("CYAN_GREEN_GUARD", "CG" + i, r, g, b);
        }
    }

    private static void addTextureDetail(PatchBuilder out, int count) {
        for (int i = 0; i < count; i++) {
            float t = i / (float) (count - 1);
            float base = 0.08f + 0.82f * t;
            float warm = (i % 6 - 2.5f) * 0.018f;
            out.add("TEXTURE_DETAIL", "TX" + i, base + warm, base, base - warm);
        }
    }

    private static void addLowNoiseDark(PatchBuilder out, int count) {
        for (int i = 0; i < count; i++) {
            float t = i / (float) (count - 1);
            float base = 0.002f + 0.095f * (float) Math.pow(t, 1.45);
            float chroma = (i % 8) / 7.0f;
            out.add("LOW_NOISE_DARK", "LND" + i, base * (1.0f + chroma * 0.28f), base, base * (1.0f + (1.0f - chroma) * 0.22f));
        }
    }

    private static BufferedImage renderPage(int page, List<Patch> patches) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(12, 12, 12), WIDTH, HEIGHT, new Color(34, 34, 34)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        drawRegistration(g);
        drawHeader(g, page);
        drawFocusTarget(g);
        patches.stream().filter(patch -> patch.page == page).forEach(patch -> drawPatch(g, patch));
        drawFooter(g, page);
        g.dispose();
        return image;
    }

    private static void drawHeader(Graphics2D g, int page) {
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 58));
        g.drawString("NDR10+ / NIVUKX Vision 2048-Patch Elite Calibration", LEFT, 108);
        g.setFont(new Font("SansSerif", Font.BOLD, 38));
        g.drawString("Page " + page + " / " + PAGES + "  -  " + pageTitle(page), LEFT, 174);
        g.setFont(new Font("SansSerif", Font.PLAIN, 28));
        g.setColor(new Color(220, 220, 220));
        g.drawString("Eight measured patches per page. Use sample boxes only; labels and borders are excluded.", LEFT, 226);
        g.drawString("Designed for offline tone simulation and live camera validation across sky, skin, highlights, WB, and saturation boundaries.", LEFT, 268);
    }

    private static void drawFooter(Graphics2D g, int page) {
        g.setColor(new Color(230, 230, 230));
        g.setFont(new Font("Monospaced", Font.PLAIN, 26));
        g.drawString("File: " + pageFile(page) + "  |  Measure inner sample boxes only.", LEFT, HEIGHT - 92);
        g.drawString("Reference: app/src/main/assets/calibration/ndr_nivukx_2048/ndr_nivukx_2048_reference.json", LEFT, HEIGHT - 48);
    }

    private static void drawRegistration(Graphics2D g) {
        g.setStroke(new BasicStroke(8f));
        g.setColor(new Color(245, 245, 245));
        int size = 95;
        drawCorner(g, LEFT - 130, TOP - 130, size, true, true);
        drawCorner(g, WIDTH - LEFT + 35, TOP - 130, size, false, true);
        drawCorner(g, LEFT - 130, HEIGHT - BOTTOM + 35, size, true, false);
        drawCorner(g, WIDTH - LEFT + 35, HEIGHT - BOTTOM + 35, size, false, false);
    }

    private static void drawCorner(Graphics2D g, int x, int y, int size, boolean left, boolean top) {
        int x1 = left ? x : x + size;
        int y1 = top ? y : y + size;
        int x2 = left ? x + size : x;
        int y2 = top ? y + size : y;
        g.drawLine(x1, y1, x2, y1);
        g.drawLine(x1, y1, x1, y2);
    }

    private static void drawFocusTarget(Graphics2D g) {
        int cx = WIDTH - LEFT - 245;
        int cy = 198;
        g.setStroke(new BasicStroke(5f));
        g.setColor(new Color(245, 245, 245));
        g.drawOval(cx - 58, cy - 58, 116, 116);
        g.drawOval(cx - 24, cy - 24, 48, 48);
        g.drawLine(cx - 90, cy, cx + 90, cy);
        g.drawLine(cx, cy - 90, cx, cy + 90);
        g.setFont(new Font("SansSerif", Font.BOLD, 25));
        g.drawString("TAP FOCUS", cx - 66, cy + 118);
    }

    private static void drawPatch(Graphics2D g, Patch patch) {
        int x = LEFT + patch.col() * (PATCH_W + GAP_X);
        int y = TOP + patch.row() * (PATCH_H + GAP_Y);
        g.setColor(toColor(patch.r, patch.g, patch.b));
        g.fillRoundRect(x, y, PATCH_W, PATCH_H, 18, 18);
        drawTextureIfNeeded(g, patch, x, y);

        int sampleX = x + Math.round(PATCH_W * SAMPLE_LEFT);
        int sampleY = y + Math.round(PATCH_H * SAMPLE_TOP);
        int sampleW = Math.round(PATCH_W * (SAMPLE_RIGHT - SAMPLE_LEFT));
        int sampleH = Math.round(PATCH_H * (SAMPLE_BOTTOM - SAMPLE_TOP));
        g.setStroke(new BasicStroke(3f));
        g.setColor(new Color(255, 255, 255, 92));
        g.drawRect(sampleX, sampleY, sampleW, sampleH);

        float luma = patch.r * 0.2126f + patch.g * 0.7152f + patch.b * 0.0722f;
        Color text = luma > 0.45f ? new Color(18, 18, 18) : new Color(248, 248, 248);
        g.setColor(text);
        g.setFont(new Font("SansSerif", Font.BOLD, 42));
        g.drawString(patch.label, x + 26, y + 58);
        g.setFont(new Font("Monospaced", Font.BOLD, 20));
        g.drawString(patch.id, x + 26, y + PATCH_H - 48);
        g.setFont(new Font("Monospaced", Font.PLAIN, 18));
        g.drawString(patch.role, x + 26, y + PATCH_H - 22);

        g.setStroke(new BasicStroke(5f));
        g.setColor(new Color(250, 250, 250, 185));
        g.drawRoundRect(x, y, PATCH_W, PATCH_H, 18, 18);
    }

    private static void drawTextureIfNeeded(Graphics2D g, Patch patch, int x, int y) {
        if (!patch.role.equals("TEXTURE_DETAIL")) return;
        g.setStroke(new BasicStroke(4f));
        Color dark = new Color(12, 12, 12, 110);
        Color light = new Color(245, 245, 245, 110);
        for (int i = 0; i < PATCH_W; i += 34) {
            g.setColor((i / 34) % 2 == 0 ? dark : light);
            g.drawLine(x + i, y + 10, x + i, y + PATCH_H - 10);
        }
        for (int i = 0; i < PATCH_H; i += 38) {
            g.setColor((i / 38) % 2 == 0 ? light : dark);
            g.drawLine(x + 10, y + i, x + PATCH_W - 10, y + i);
        }
    }

    private static String buildReferenceJson(List<Patch> patches) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"version\": \"").append(VERSION).append("\",\n");
        json.append("  \"encoding\": \"sRGB display reference, 8-bit PNG pages\",\n");
        json.append("  \"measurement_rule\": \"Use sample_bounds_normalized only; labels, borders, and texture overlays are excluded.\",\n");
        json.append("  \"capture_modes\": [\"Normal SDR\", \"NDR10+\", \"NIVUKX Vision\"],\n");
        json.append("  \"canvas\": { \"width\": ").append(WIDTH)
            .append(", \"height\": ").append(HEIGHT)
            .append(", \"patch_count\": ").append(PATCH_COUNT)
            .append(", \"page_count\": ").append(PAGES)
            .append(", \"patches_per_page\": ").append(PATCHES_PER_PAGE)
            .append(", \"rows\": ").append(ROWS)
            .append(", \"columns\": ").append(COLS).append(" },\n");
        json.append("  \"pages\": [\n");
        for (int page = 1; page <= PAGES; page++) {
            json.append("    { \"page_index\": ").append(page)
                .append(", \"file\": \"").append(pageFile(page)).append("\", ")
                .append("\"title\": \"").append(pageTitle(page)).append("\" }");
            json.append(page == PAGES ? "\n" : ",\n");
        }
        json.append("  ],\n");
        json.append("  \"patches\": [\n");
        for (int i = 0; i < patches.size(); i++) {
            Patch patch = patches.get(i);
            appendPatchJson(json, patch);
            json.append(i == patches.size() - 1 ? "\n" : ",\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private static void appendPatchJson(StringBuilder json, Patch patch) {
        int x = LEFT + patch.col() * (PATCH_W + GAP_X);
        int y = TOP + patch.row() * (PATCH_H + GAP_Y);
        int sampleX = x + Math.round(PATCH_W * SAMPLE_LEFT);
        int sampleY = y + Math.round(PATCH_H * SAMPLE_TOP);
        int sampleW = Math.round(PATCH_W * (SAMPLE_RIGHT - SAMPLE_LEFT));
        int sampleH = Math.round(PATCH_H * (SAMPLE_BOTTOM - SAMPLE_TOP));
        json.append("    { \"id\": \"").append(patch.id)
            .append("\", \"label\": \"").append(patch.label)
            .append("\", \"role\": \"").append(patch.role)
            .append("\", \"page\": ").append(patch.page)
            .append(", \"slot\": ").append(patch.slot)
            .append(", \"row\": ").append(patch.row())
            .append(", \"column\": ").append(patch.col())
            .append(", \"file\": \"").append(pageFile(patch.page))
            .append("\", \"bounds_normalized\": ").append(bounds(x, y, PATCH_W, PATCH_H))
            .append(", \"sample_bounds_normalized\": ").append(bounds(sampleX, sampleY, sampleW, sampleH))
            .append(", \"reference_srgb\": [")
            .append(format(patch.r)).append(", ")
            .append(format(patch.g)).append(", ")
            .append(format(patch.b)).append("]")
            .append(", \"reference_srgb_8bit\": [")
            .append(toByte(patch.r)).append(", ")
            .append(toByte(patch.g)).append(", ")
            .append(toByte(patch.b)).append("] }");
    }

    private static String buildInstructions() {
        return "# NDR10+ / NIVUKX Vision 2048-Patch Elite Calibration\n\n"
            + "- 2048 measured colors split into 256 pages with 8 patches per image.\n"
            + "- Use this after the 144-patch quick target passes.\n"
            + "- For offline simulation, run `tools/calibration/offline_ndr_nivukx_tone_simulator.py --reference app/src/main/assets/calibration/ndr_nivukx_2048/ndr_nivukx_2048_reference.json --max-edge 720`.\n"
            + "- For live camera capture, show each page full-screen with fixed display brightness and disabled display enhancement.\n"
            + "- Measure only the inner sample boxes described by `sample_bounds_normalized`.\n";
    }

    private static String bounds(int x, int y, int w, int h) {
        return "[" + format(x / (float) WIDTH) + ", " + format(y / (float) HEIGHT) + ", "
            + format((x + w) / (float) WIDTH) + ", " + format((y + h) / (float) HEIGHT) + "]";
    }

    private static String pageFile(int page) {
        return String.format(Locale.US, "ndr_nivukx_2048_page_%03d.png", page);
    }

    private static String pageTitle(int page) {
        int firstPatch = (page - 1) * PATCHES_PER_PAGE;
        if (firstPatch < 192) return "neutral ramp and gamma foundation";
        if (firstPatch < 384) return "skin memory and red-face protection";
        if (firstPatch < 576) return "sky gradients and blue-channel preservation";
        if (firstPatch < 768) return "highlight rolloff and channel balance";
        if (firstPatch < 960) return "shadow chroma and low-light color";
        if (firstPatch < 1120) return "foliage memory and green stability";
        if (firstPatch < 1440) return "saturation boundary and hue ring";
        if (firstPatch < 1600) return "white-balance illuminant stress";
        if (firstPatch < 1728) return "red face guard stress";
        if (firstPatch < 1856) return "cyan and green highlight guard";
        if (firstPatch < 1952) return "texture and detail stability";
        return "low-noise dark floor";
    }

    private static Color toColor(float r, float g, float b) {
        return new Color(toByte(r), toByte(g), toByte(b));
    }

    private static int toByte(float value) {
        return Math.max(0, Math.min(255, Math.round(clamp01(value) * 255f)));
    }

    private static float[] hsbToRgb(float h, float s, float v) {
        int rgb = Color.HSBtoRGB(clamp01(h), clamp01(s), clamp01(v));
        return new float[] {
            ((rgb >> 16) & 0xFF) / 255.0f,
            ((rgb >> 8) & 0xFF) / 255.0f,
            (rgb & 0xFF) / 255.0f
        };
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * clamp01(t);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static String format(float value) {
        return String.format(Locale.US, "%.6f", value);
    }
}
