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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;

public final class GenerateNdrNivukxCalibrationTarget144 {
    private static final String VERSION = "NDR_NIVUKX_TONE_COLOR_REFERENCE_144_V1";
    private static final int WIDTH = 3840;
    private static final int HEIGHT = 2160;
    private static final int PAGES = 18;
    private static final int PATCHES_PER_PAGE = 8;
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
        final int page;
        final int slot;
        final String id;
        final String label;
        final String role;
        final float r;
        final float g;
        final float b;

        Patch(int page, int slot, String id, String label, String role, float r, float g, float b) {
            this.page = page;
            this.slot = slot;
            this.id = id;
            this.label = label;
            this.role = role;
            this.r = r;
            this.g = g;
            this.b = b;
        }

        int row() {
            return slot / COLS;
        }

        int col() {
            return slot % COLS;
        }
    }

    public static void main(String[] args) throws IOException {
        Path assetDir = Paths.get("app", "src", "main", "assets", "calibration", "ndr_nivukx_144");
        Path deliverableDir = Paths.get("deliverables", "ndr_nivukx_144");
        Files.createDirectories(assetDir);
        Files.createDirectories(deliverableDir);

        List<Patch> patches = patches();
        if (patches.size() != PAGES * PATCHES_PER_PAGE) {
            throw new IllegalStateException("Expected 144 patches, got " + patches.size());
        }

        for (int page = 1; page <= PAGES; page++) {
            BufferedImage image = renderPage(page, patches);
            String file = pageFile(page);
            ImageIO.write(image, "png", assetDir.resolve(file).toFile());
            Files.copy(assetDir.resolve(file), deliverableDir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
        }

        String reference = buildReferenceJson(patches);
        Files.write(assetDir.resolve("ndr_nivukx_144_reference.json"), reference.getBytes(StandardCharsets.UTF_8));
        Files.write(deliverableDir.resolve("ndr_nivukx_144_reference.json"), reference.getBytes(StandardCharsets.UTF_8));
        Files.write(deliverableDir.resolve("capture_instructions.md"), buildInstructions().getBytes(StandardCharsets.UTF_8));
    }

    private static BufferedImage renderPage(int page, List<Patch> patches) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(15, 15, 15), WIDTH, HEIGHT, new Color(38, 38, 38)));
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
        g.setFont(new Font("SansSerif", Font.BOLD, 62));
        g.drawString("NDR10+ / NIVUKX Vision 144-Patch Calibration", LEFT, 110);
        g.setFont(new Font("SansSerif", Font.BOLD, 42));
        g.drawString("Page " + page + " / " + PAGES + "  -  " + pageTitle(page), LEFT, 178);
        g.setFont(new Font("SansSerif", Font.PLAIN, 30));
        g.setColor(new Color(220, 220, 220));
        g.drawString("Show one page full-screen. Record Normal, NDR10+, and NIVUKX Vision before moving to the next page.", LEFT, 228);
        g.drawString("Use fixed display brightness. Disable eye comfort, adaptive brightness, and display enhancement modes.", LEFT, 270);
    }

    private static void drawFooter(Graphics2D g, int page) {
        g.setColor(new Color(230, 230, 230));
        g.setFont(new Font("Monospaced", Font.PLAIN, 28));
        g.drawString("File: " + pageFile(page) + "  |  Measure inner sample boxes only.", LEFT, HEIGHT - 92);
        g.drawString("Reference: app/src/main/assets/calibration/ndr_nivukx_144/ndr_nivukx_144_reference.json", LEFT, HEIGHT - 48);
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
        int cy = 200;
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
        g.setFont(new Font("SansSerif", Font.BOLD, 46));
        g.drawString(patch.label, x + 26, y + 62);
        g.setFont(new Font("Monospaced", Font.BOLD, 22));
        g.drawString(patch.id, x + 26, y + PATCH_H - 46);
        g.setFont(new Font("Monospaced", Font.PLAIN, 20));
        g.drawString(patch.role, x + 26, y + PATCH_H - 20);

        g.setStroke(new BasicStroke(5f));
        g.setColor(new Color(250, 250, 250, 185));
        g.drawRoundRect(x, y, PATCH_W, PATCH_H, 18, 18);
    }

    private static void drawTextureIfNeeded(Graphics2D g, Patch patch, int x, int y) {
        if (!patch.role.equals("TEXTURE")) return;
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
            .append(", \"page_count\": ").append(PAGES)
            .append(", \"patches_per_page\": ").append(PATCHES_PER_PAGE)
            .append(", \"rows\": ").append(ROWS)
            .append(", \"columns\": ").append(COLS).append(" },\n");
        json.append("  \"pages\": [\n");
        for (int page = 1; page <= PAGES; page++) {
            json.append("    { \"page_index\": ").append(page)
                .append(", \"file\": \"").append(pageFile(page)).append("\", ")
                .append("\"title\": \"").append(pageTitle(page)).append("\" }");
            if (page < PAGES) json.append(",");
            json.append("\n");
        }
        json.append("  ],\n");
        json.append("  \"patches\": [\n");
        for (int i = 0; i < patches.size(); i++) {
            Patch p = patches.get(i);
            int x = LEFT + p.col() * (PATCH_W + GAP_X);
            int y = TOP + p.row() * (PATCH_H + GAP_Y);
            int sx0 = x + Math.round(PATCH_W * SAMPLE_LEFT);
            int sy0 = y + Math.round(PATCH_H * SAMPLE_TOP);
            int sx1 = x + Math.round(PATCH_W * SAMPLE_RIGHT);
            int sy1 = y + Math.round(PATCH_H * SAMPLE_BOTTOM);
            json.append("    { \"id\": \"").append(p.id).append("\", ")
                .append("\"label\": \"").append(p.label).append("\", ")
                .append("\"role\": \"").append(p.role).append("\", ")
                .append("\"page\": ").append(p.page).append(", ")
                .append("\"slot\": ").append(p.slot).append(", ")
                .append("\"row\": ").append(p.row()).append(", ")
                .append("\"column\": ").append(p.col()).append(", ")
                .append("\"file\": \"").append(pageFile(p.page)).append("\", ")
                .append("\"bounds_normalized\": [")
                .append(format((float) x / WIDTH)).append(", ")
                .append(format((float) y / HEIGHT)).append(", ")
                .append(format((float) (x + PATCH_W) / WIDTH)).append(", ")
                .append(format((float) (y + PATCH_H) / HEIGHT)).append("], ")
                .append("\"sample_bounds_normalized\": [")
                .append(format((float) sx0 / WIDTH)).append(", ")
                .append(format((float) sy0 / HEIGHT)).append(", ")
                .append(format((float) sx1 / WIDTH)).append(", ")
                .append(format((float) sy1 / HEIGHT)).append("], ")
                .append("\"reference_srgb\": [")
                .append(format(p.r)).append(", ")
                .append(format(p.g)).append(", ")
                .append(format(p.b)).append("], ")
                .append("\"reference_srgb_8bit\": [")
                .append(clampByte(p.r)).append(", ")
                .append(clampByte(p.g)).append(", ")
                .append(clampByte(p.b)).append("] }");
            if (i < patches.size() - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private static String buildInstructions() {
        return String.join("\n",
            "# NDR10+ / NIVUKX Vision 144-Patch Calibration",
            "",
            "Use the 18 PNG files in `deliverables/ndr_nivukx_144/`.",
            "Each page has 8 large color patches. Capture every page in the same framing and lighting.",
            "",
            "Required sequence per page:",
            "1. Normal SDR reference capture.",
            "2. NDR10+ capture.",
            "3. NIVUKX Vision capture.",
            "",
            "Naming convention:",
            "- `NORMAL_144_PAGE_01`",
            "- `NDR10_144_PAGE_01`",
            "- `NIVUKX_144_PAGE_01`",
            "- Continue through PAGE_18.",
            "",
            "Rules:",
            "- Display page full-screen.",
            "- Keep device, display brightness, and ambient light fixed.",
            "- Disable adaptive brightness, eye comfort/night light, and vivid display modes.",
            "- Tap focus on the page focus target before each recording.",
            "- Keep all four registration corners visible.",
            "",
            "Reference JSON: `deliverables/ndr_nivukx_144/ndr_nivukx_144_reference.json`.",
            ""
        );
    }

    private static String pageFile(int page) {
        return String.format(Locale.US, "ndr_nivukx_144_page_%02d.png", page);
    }

    private static String pageTitle(int page) {
        switch (page) {
            case 1: return "neutral exposure foundation";
            case 2: return "near-white and cloud highlight";
            case 3: return "shadow and low-light color";
            case 4: return "highlight rolloff guards";
            case 5: return "skin base tones";
            case 6: return "skin, lips, and red-face guards";
            case 7: return "sky family";
            case 8: return "blue and cyan family";
            case 9: return "foliage and green family";
            case 10: return "yellow, orange, and warm light";
            case 11: return "red and magenta stress";
            case 12: return "saturated hue ring";
            case 13: return "pastel and desaturated colors";
            case 14: return "white balance illuminant stress";
            case 15: return "texture and detail stress";
            case 16: return "HDR neon and specular color";
            case 17: return "shadow chroma stress";
            case 18: return "mixed validation repeats";
            default: return "unknown";
        }
    }

    private static List<Patch> patches() {
        return Arrays.asList(
            p(1, 0, "neutral_01", "N01", "NEUTRAL", 0.010f, 0.010f, 0.010f),
            p(1, 1, "neutral_03", "N03", "NEUTRAL", 0.030f, 0.030f, 0.030f),
            p(1, 2, "neutral_06", "N06", "NEUTRAL", 0.060f, 0.060f, 0.060f),
            p(1, 3, "neutral_12", "N12", "NEUTRAL", 0.120f, 0.120f, 0.120f),
            p(1, 4, "neutral_18", "N18", "NEUTRAL", 0.180f, 0.180f, 0.180f),
            p(1, 5, "neutral_32", "N32", "NEUTRAL", 0.320f, 0.320f, 0.320f),
            p(1, 6, "neutral_50", "N50", "NEUTRAL", 0.500f, 0.500f, 0.500f),
            p(1, 7, "neutral_75", "N75", "NEUTRAL", 0.750f, 0.750f, 0.750f),

            p(2, 0, "neutral_88", "N88", "NEUTRAL", 0.880f, 0.880f, 0.880f),
            p(2, 1, "neutral_94", "N94", "NEUTRAL", 0.940f, 0.940f, 0.940f),
            p(2, 2, "neutral_98", "N98", "NEUTRAL", 0.980f, 0.980f, 0.980f),
            p(2, 3, "specular_white", "SPW", "HIGHLIGHT", 1.000f, 1.000f, 1.000f),
            p(2, 4, "cloud_soft", "CL1", "HIGHLIGHT", 0.900f, 0.915f, 0.920f),
            p(2, 5, "cloud_bright", "CL2", "HIGHLIGHT", 0.960f, 0.970f, 0.975f),
            p(2, 6, "white_warm", "WW", "HIGHLIGHT", 0.980f, 0.920f, 0.820f),
            p(2, 7, "white_cool", "WC", "HIGHLIGHT", 0.850f, 0.920f, 1.000f),

            p(3, 0, "shadow_black", "SBK", "SHADOW", 0.004f, 0.004f, 0.004f),
            p(3, 1, "shadow_neutral_02", "SN2", "SHADOW", 0.020f, 0.020f, 0.020f),
            p(3, 2, "shadow_neutral_05", "SN5", "SHADOW", 0.050f, 0.050f, 0.050f),
            p(3, 3, "shadow_warm", "SW", "SHADOW", 0.110f, 0.078f, 0.042f),
            p(3, 4, "shadow_cool", "SC", "SHADOW", 0.045f, 0.055f, 0.095f),
            p(3, 5, "shadow_skin", "SS", "SHADOW", 0.105f, 0.070f, 0.052f),
            p(3, 6, "shadow_sky", "SSK", "SHADOW", 0.040f, 0.058f, 0.102f),
            p(3, 7, "shadow_foliage", "SF", "SHADOW", 0.042f, 0.078f, 0.036f),

            p(4, 0, "highlight_cloud", "HC", "HIGHLIGHT", 0.900f, 0.915f, 0.920f),
            p(4, 1, "highlight_sky", "HSK", "HIGHLIGHT", 0.690f, 0.820f, 0.990f),
            p(4, 2, "highlight_skin", "HS", "HIGHLIGHT", 0.930f, 0.720f, 0.610f),
            p(4, 3, "highlight_red_guard", "HR", "HIGHLIGHT", 0.980f, 0.560f, 0.500f),
            p(4, 4, "highlight_cyan_guard", "HCY", "HIGHLIGHT", 0.720f, 0.950f, 0.940f),
            p(4, 5, "highlight_yellow", "HY", "HIGHLIGHT", 0.980f, 0.900f, 0.450f),
            p(4, 6, "highlight_warm", "HW", "HIGHLIGHT", 0.970f, 0.850f, 0.640f),
            p(4, 7, "highlight_neutral", "HN", "HIGHLIGHT", 0.970f, 0.970f, 0.970f),

            p(5, 0, "skin_very_deep", "SVD", "SKIN", 0.220f, 0.115f, 0.075f),
            p(5, 1, "skin_deep", "SD", "SKIN", 0.360f, 0.210f, 0.145f),
            p(5, 2, "skin_medium_dark", "SMD", "SKIN", 0.455f, 0.275f, 0.190f),
            p(5, 3, "skin_medium", "SM", "SKIN", 0.560f, 0.350f, 0.260f),
            p(5, 4, "skin_olive", "SO", "SKIN", 0.500f, 0.385f, 0.255f),
            p(5, 5, "skin_warm", "SWM", "SKIN", 0.690f, 0.410f, 0.270f),
            p(5, 6, "skin_cool", "SCL", "SKIN", 0.600f, 0.390f, 0.340f),
            p(5, 7, "skin_light", "SL", "SKIN", 0.760f, 0.560f, 0.450f),

            p(6, 0, "skin_light_warm", "SLW", "SKIN", 0.830f, 0.610f, 0.470f),
            p(6, 1, "skin_light_cool", "SLC", "SKIN", 0.780f, 0.590f, 0.530f),
            p(6, 2, "skin_highlight", "SH", "SKIN", 0.930f, 0.720f, 0.610f),
            p(6, 3, "lip_red", "LR", "SKIN", 0.620f, 0.225f, 0.245f),
            p(6, 4, "lip_magenta", "LM", "SKIN", 0.580f, 0.170f, 0.285f),
            p(6, 5, "blush_guard", "BG", "SKIN", 0.750f, 0.350f, 0.320f),
            p(6, 6, "face_red_guard", "FR", "SATURATED_RED", 0.720f, 0.260f, 0.205f),
            p(6, 7, "face_orange_guard", "FO", "SATURATED_ORANGE", 0.820f, 0.410f, 0.185f),

            p(7, 0, "sky_deep", "BD", "SKY", 0.145f, 0.310f, 0.760f),
            p(7, 1, "sky_day", "BY", "SKY", 0.310f, 0.560f, 0.910f),
            p(7, 2, "sky_haze", "BH", "SKY", 0.560f, 0.720f, 0.910f),
            p(7, 3, "sky_cloud_edge", "BE", "SKY", 0.720f, 0.820f, 0.910f),
            p(7, 4, "sky_warm_haze", "BWH", "SKY", 0.670f, 0.760f, 0.880f),
            p(7, 5, "sky_cyan_haze", "BCH", "SKY", 0.470f, 0.760f, 0.920f),
            p(7, 6, "sky_after_sun", "BAS", "SKY", 0.620f, 0.700f, 0.850f),
            p(7, 7, "sky_highlight", "BHI", "SKY", 0.760f, 0.880f, 1.000f),

            p(8, 0, "blue_deep", "BDP", "SATURATED_BLUE", 0.040f, 0.080f, 0.540f),
            p(8, 1, "blue_day", "BDY", "SATURATED_BLUE", 0.070f, 0.150f, 0.880f),
            p(8, 2, "blue_cobalt", "BCO", "SATURATED_BLUE", 0.110f, 0.230f, 0.780f),
            p(8, 3, "blue_violet", "BVI", "SATURATED_BLUE", 0.210f, 0.160f, 0.760f),
            p(8, 4, "cyan_deep", "CD", "SATURATED_CYAN", 0.020f, 0.500f, 0.560f),
            p(8, 5, "cyan_bright", "CB", "SATURATED_CYAN", 0.040f, 0.760f, 0.820f),
            p(8, 6, "aqua_guard", "AQ", "SATURATED_CYAN", 0.200f, 0.820f, 0.780f),
            p(8, 7, "cyan_highlight", "CH", "HIGHLIGHT", 0.720f, 0.950f, 0.940f),

            p(9, 0, "foliage_dark", "FD", "FOLIAGE", 0.105f, 0.300f, 0.095f),
            p(9, 1, "foliage_leaf", "FL", "FOLIAGE", 0.190f, 0.480f, 0.155f),
            p(9, 2, "foliage_sun", "FS", "FOLIAGE", 0.360f, 0.600f, 0.210f),
            p(9, 3, "foliage_yellow_green", "FY", "FOLIAGE", 0.530f, 0.650f, 0.190f),
            p(9, 4, "foliage_olive", "FOG", "FOLIAGE", 0.310f, 0.420f, 0.130f),
            p(9, 5, "foliage_dry_grass", "FDG", "FOLIAGE", 0.500f, 0.500f, 0.190f),
            p(9, 6, "foliage_shadow_green", "FSG", "FOLIAGE", 0.045f, 0.180f, 0.050f),
            p(9, 7, "foliage_mint", "FM", "FOLIAGE", 0.380f, 0.720f, 0.460f),

            p(10, 0, "sat_yellow", "Y", "SATURATED_YELLOW", 0.950f, 0.850f, 0.060f),
            p(10, 1, "amber", "AMB", "SATURATED_YELLOW", 0.980f, 0.640f, 0.060f),
            p(10, 2, "sat_orange", "O", "SATURATED_ORANGE", 0.940f, 0.360f, 0.050f),
            p(10, 3, "warm_light", "WL", "HIGHLIGHT", 0.970f, 0.850f, 0.640f),
            p(10, 4, "fire_orange", "FIR", "SATURATED_ORANGE", 1.000f, 0.260f, 0.040f),
            p(10, 5, "gold", "GLD", "SATURATED_YELLOW", 0.940f, 0.700f, 0.180f),
            p(10, 6, "tan", "TAN", "TEXTURE", 0.610f, 0.470f, 0.310f),
            p(10, 7, "sand", "SND", "TEXTURE", 0.740f, 0.670f, 0.490f),

            p(11, 0, "sat_red", "R", "SATURATED_RED", 0.880f, 0.050f, 0.040f),
            p(11, 1, "red_highlight", "RH", "SATURATED_RED", 0.980f, 0.180f, 0.130f),
            p(11, 2, "dark_red", "DR", "SATURATED_RED", 0.420f, 0.020f, 0.020f),
            p(11, 3, "crimson", "CR", "SATURATED_RED", 0.690f, 0.030f, 0.090f),
            p(11, 4, "magenta", "MG", "SATURATED_RED", 0.780f, 0.060f, 0.680f),
            p(11, 5, "pink", "PK", "SKIN", 0.930f, 0.320f, 0.520f),
            p(11, 6, "purple_magenta", "PM", "SATURATED_BLUE", 0.470f, 0.060f, 0.620f),
            p(11, 7, "red_shadow", "RS", "SHADOW", 0.112f, 0.030f, 0.026f),

            p(12, 0, "hue_red", "HRD", "SATURATED_RED", 0.900f, 0.045f, 0.040f),
            p(12, 1, "hue_orange", "HOR", "SATURATED_ORANGE", 0.930f, 0.300f, 0.030f),
            p(12, 2, "hue_yellow", "HYL", "SATURATED_YELLOW", 0.920f, 0.840f, 0.040f),
            p(12, 3, "hue_green", "HGR", "SATURATED_GREEN", 0.060f, 0.760f, 0.090f),
            p(12, 4, "hue_cyan", "HCN", "SATURATED_CYAN", 0.040f, 0.760f, 0.820f),
            p(12, 5, "hue_blue", "HBL", "SATURATED_BLUE", 0.070f, 0.150f, 0.880f),
            p(12, 6, "hue_magenta", "HMG", "SATURATED_RED", 0.780f, 0.060f, 0.680f),
            p(12, 7, "hue_violet", "HVI", "SATURATED_BLUE", 0.260f, 0.050f, 0.720f),

            p(13, 0, "pastel_skin", "PSK", "SKIN", 0.850f, 0.650f, 0.560f),
            p(13, 1, "pastel_sky", "PSY", "SKY", 0.650f, 0.790f, 0.960f),
            p(13, 2, "pastel_cyan", "PCY", "SATURATED_CYAN", 0.560f, 0.880f, 0.860f),
            p(13, 3, "pastel_green", "PGR", "FOLIAGE", 0.570f, 0.780f, 0.520f),
            p(13, 4, "pastel_yellow", "PYL", "SATURATED_YELLOW", 0.920f, 0.850f, 0.460f),
            p(13, 5, "pastel_orange", "POR", "SATURATED_ORANGE", 0.930f, 0.620f, 0.390f),
            p(13, 6, "pastel_pink", "PPK", "SKIN", 0.920f, 0.530f, 0.650f),
            p(13, 7, "pastel_lavender", "PLV", "SATURATED_BLUE", 0.610f, 0.530f, 0.860f),

            p(14, 0, "wb_tungsten_gray", "WTG", "NEUTRAL", 0.520f, 0.440f, 0.340f),
            p(14, 1, "wb_daylight_gray", "WDG", "NEUTRAL", 0.500f, 0.500f, 0.500f),
            p(14, 2, "wb_cloudy_gray", "WCG", "NEUTRAL", 0.520f, 0.535f, 0.560f),
            p(14, 3, "wb_fluorescent_green", "WFG", "NEUTRAL", 0.430f, 0.520f, 0.450f),
            p(14, 4, "wb_magenta_cast", "WMC", "NEUTRAL", 0.540f, 0.450f, 0.540f),
            p(14, 5, "wb_blue_shade", "WBS", "NEUTRAL", 0.390f, 0.450f, 0.590f),
            p(14, 6, "wb_warm_highlight", "WWH", "HIGHLIGHT", 0.980f, 0.900f, 0.760f),
            p(14, 7, "wb_cool_highlight", "WCH", "HIGHLIGHT", 0.760f, 0.880f, 1.000f),

            p(15, 0, "texture_gray_25", "TG25", "TEXTURE", 0.250f, 0.250f, 0.250f),
            p(15, 1, "texture_gray_50", "TG50", "TEXTURE", 0.500f, 0.500f, 0.500f),
            p(15, 2, "texture_gray_75", "TG75", "TEXTURE", 0.750f, 0.750f, 0.750f),
            p(15, 3, "texture_warm", "TWM", "TEXTURE", 0.480f, 0.390f, 0.300f),
            p(15, 4, "texture_cool", "TCL", "TEXTURE", 0.300f, 0.380f, 0.500f),
            p(15, 5, "texture_red", "TRD", "TEXTURE", 0.520f, 0.120f, 0.110f),
            p(15, 6, "texture_green", "TGR", "TEXTURE", 0.120f, 0.460f, 0.160f),
            p(15, 7, "texture_blue", "TBL", "TEXTURE", 0.120f, 0.180f, 0.560f),

            p(16, 0, "neon_red", "NR", "SATURATED_RED", 1.000f, 0.020f, 0.060f),
            p(16, 1, "neon_green", "NG", "SATURATED_GREEN", 0.050f, 1.000f, 0.090f),
            p(16, 2, "neon_blue", "NB", "SATURATED_BLUE", 0.040f, 0.110f, 1.000f),
            p(16, 3, "neon_cyan", "NC", "SATURATED_CYAN", 0.020f, 0.940f, 1.000f),
            p(16, 4, "neon_magenta", "NM", "SATURATED_RED", 0.980f, 0.050f, 0.900f),
            p(16, 5, "neon_yellow", "NY", "SATURATED_YELLOW", 1.000f, 0.960f, 0.040f),
            p(16, 6, "specular_red", "SPR", "HIGHLIGHT", 1.000f, 0.420f, 0.360f),
            p(16, 7, "specular_blue", "SPB", "HIGHLIGHT", 0.520f, 0.680f, 1.000f),

            p(17, 0, "shadow_red_deep", "SRD", "SHADOW", 0.080f, 0.012f, 0.010f),
            p(17, 1, "shadow_orange_deep", "SOD", "SHADOW", 0.090f, 0.036f, 0.010f),
            p(17, 2, "shadow_yellow_deep", "SYD", "SHADOW", 0.085f, 0.072f, 0.015f),
            p(17, 3, "shadow_green_deep", "SGD", "SHADOW", 0.012f, 0.084f, 0.018f),
            p(17, 4, "shadow_cyan_deep", "SCD", "SHADOW", 0.010f, 0.078f, 0.082f),
            p(17, 5, "shadow_blue_deep", "SBD", "SHADOW", 0.012f, 0.024f, 0.090f),
            p(17, 6, "shadow_magenta_deep", "SMDP", "SHADOW", 0.082f, 0.014f, 0.070f),
            p(17, 7, "shadow_skin_dark", "SSD", "SHADOW", 0.085f, 0.045f, 0.030f),

            p(18, 0, "validation_neutral_18", "VN18", "NEUTRAL", 0.180f, 0.180f, 0.180f),
            p(18, 1, "validation_sky_day", "VSK", "SKY", 0.310f, 0.560f, 0.910f),
            p(18, 2, "validation_skin_medium", "VSM", "SKIN", 0.560f, 0.350f, 0.260f),
            p(18, 3, "validation_foliage", "VFL", "FOLIAGE", 0.190f, 0.480f, 0.155f),
            p(18, 4, "validation_highlight", "VHL", "HIGHLIGHT", 0.900f, 0.915f, 0.920f),
            p(18, 5, "validation_red", "VRD", "SATURATED_RED", 0.880f, 0.050f, 0.040f),
            p(18, 6, "validation_cyan", "VCY", "SATURATED_CYAN", 0.040f, 0.760f, 0.820f),
            p(18, 7, "validation_texture", "VTX", "TEXTURE", 0.520f, 0.520f, 0.520f)
        );
    }

    private static Patch p(int page, int slot, String id, String label, String role, float r, float g, float b) {
        return new Patch(page, slot, id, label, role, r, g, b);
    }

    private static Color toColor(float r, float g, float b) {
        return new Color(clampByte(r), clampByte(g), clampByte(b));
    }

    private static int clampByte(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    private static String format(float value) {
        return String.format(Locale.US, "%.6f", value);
    }
}
