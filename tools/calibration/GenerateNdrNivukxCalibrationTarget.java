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
import java.util.Locale;
import javax.imageio.ImageIO;

public final class GenerateNdrNivukxCalibrationTarget {
    private static final int WIDTH = 3840;
    private static final int HEIGHT = 2160;
    private static final int ROWS = 6;
    private static final int COLS = 8;
    private static final int LEFT = 140;
    private static final int TOP = 560;
    private static final int BOTTOM = 245;
    private static final int GAP = 22;
    private static final int PATCH_W = (WIDTH - LEFT * 2 - GAP * (COLS - 1)) / COLS;
    private static final int PATCH_H = (HEIGHT - TOP - BOTTOM - GAP * (ROWS - 1)) / ROWS;
    private static final float SAMPLE_LEFT = 0.22f;
    private static final float SAMPLE_TOP = 0.30f;
    private static final float SAMPLE_RIGHT = 0.82f;
    private static final float SAMPLE_BOTTOM = 0.74f;
    private static final String VERSION = "NDR_NIVUKX_TONE_COLOR_REFERENCE_V2";

    private static final class Patch {
        final String id;
        final String label;
        final String role;
        final int row;
        final int col;
        final float r;
        final float g;
        final float b;

        Patch(String id, String label, String role, int row, int col, float r, float g, float b) {
            this.id = id;
            this.label = label;
            this.role = role;
            this.row = row;
            this.col = col;
            this.r = r;
            this.g = g;
            this.b = b;
        }
    }

    private static final Patch[] PATCHES = new Patch[] {
        p("neutral_02", "N02", "NEUTRAL", 0, 0, 0.02f, 0.02f, 0.02f),
        p("neutral_08", "N08", "NEUTRAL", 0, 1, 0.08f, 0.08f, 0.08f),
        p("neutral_18", "N18", "NEUTRAL", 0, 2, 0.18f, 0.18f, 0.18f),
        p("neutral_32", "N32", "NEUTRAL", 0, 3, 0.32f, 0.32f, 0.32f),
        p("neutral_50", "N50", "NEUTRAL", 0, 4, 0.50f, 0.50f, 0.50f),
        p("neutral_68", "N68", "NEUTRAL", 0, 5, 0.68f, 0.68f, 0.68f),
        p("neutral_84", "N84", "NEUTRAL", 0, 6, 0.84f, 0.84f, 0.84f),
        p("neutral_96", "N96", "NEUTRAL", 0, 7, 0.96f, 0.96f, 0.96f),
        p("shadow_neutral", "SN", "SHADOW", 1, 0, 0.035f, 0.035f, 0.035f),
        p("shadow_skin", "SS", "SHADOW", 1, 1, 0.105f, 0.070f, 0.052f),
        p("shadow_sky", "SB", "SHADOW", 1, 2, 0.040f, 0.058f, 0.102f),
        p("shadow_foliage", "SF", "SHADOW", 1, 3, 0.042f, 0.078f, 0.036f),
        p("shadow_red", "SR", "SHADOW", 1, 4, 0.112f, 0.030f, 0.026f),
        p("shadow_cyan", "SC", "SHADOW", 1, 5, 0.026f, 0.090f, 0.095f),
        p("shadow_warm", "SW", "SHADOW", 1, 6, 0.110f, 0.078f, 0.042f),
        p("shadow_cool", "SK", "SHADOW", 1, 7, 0.045f, 0.055f, 0.095f),
        p("highlight_cloud", "HC", "HIGHLIGHT", 2, 0, 0.900f, 0.915f, 0.920f),
        p("highlight_warm", "HW", "HIGHLIGHT", 2, 1, 0.970f, 0.850f, 0.640f),
        p("highlight_skin", "HS", "HIGHLIGHT", 2, 2, 0.930f, 0.720f, 0.610f),
        p("highlight_sky", "HB", "HIGHLIGHT", 2, 3, 0.690f, 0.820f, 0.990f),
        p("highlight_neutral", "HN", "HIGHLIGHT", 2, 4, 0.970f, 0.970f, 0.970f),
        p("highlight_cyan_guard", "HG", "HIGHLIGHT", 2, 5, 0.720f, 0.950f, 0.940f),
        p("highlight_red_guard", "HR", "HIGHLIGHT", 2, 6, 0.980f, 0.560f, 0.500f),
        p("highlight_yellow", "HY", "HIGHLIGHT", 2, 7, 0.980f, 0.900f, 0.450f),
        p("skin_deep", "SD", "SKIN", 3, 0, 0.360f, 0.210f, 0.145f),
        p("skin_medium", "SM", "SKIN", 3, 1, 0.560f, 0.350f, 0.260f),
        p("skin_light", "SL", "SKIN", 3, 2, 0.760f, 0.560f, 0.450f),
        p("skin_warm", "SWM", "SKIN", 3, 3, 0.690f, 0.410f, 0.270f),
        p("skin_cool", "SCL", "SKIN", 3, 4, 0.600f, 0.390f, 0.340f),
        p("lip_guard", "LG", "SKIN", 3, 5, 0.620f, 0.225f, 0.245f),
        p("texture_gray", "TG", "TEXTURE", 3, 6, 0.430f, 0.430f, 0.430f),
        p("texture_warm", "TW", "TEXTURE", 3, 7, 0.480f, 0.390f, 0.300f),
        p("sky_deep", "BD", "SKY", 4, 0, 0.145f, 0.310f, 0.760f),
        p("sky_day", "BY", "SKY", 4, 1, 0.310f, 0.560f, 0.910f),
        p("sky_haze", "BH", "SKY", 4, 2, 0.560f, 0.720f, 0.910f),
        p("sky_cloud_edge", "BE", "SKY", 4, 3, 0.720f, 0.820f, 0.910f),
        p("foliage_dark", "FD", "FOLIAGE", 4, 4, 0.105f, 0.300f, 0.095f),
        p("foliage_leaf", "FL", "FOLIAGE", 4, 5, 0.190f, 0.480f, 0.155f),
        p("foliage_sun", "FS", "FOLIAGE", 4, 6, 0.360f, 0.600f, 0.210f),
        p("foliage_yellow_green", "FY", "FOLIAGE", 4, 7, 0.530f, 0.650f, 0.190f),
        p("sat_red", "R", "SATURATED_RED", 5, 0, 0.880f, 0.050f, 0.040f),
        p("sat_orange", "O", "SATURATED_ORANGE", 5, 1, 0.940f, 0.360f, 0.050f),
        p("sat_yellow", "Y", "SATURATED_YELLOW", 5, 2, 0.950f, 0.850f, 0.060f),
        p("sat_green", "G", "SATURATED_GREEN", 5, 3, 0.060f, 0.760f, 0.090f),
        p("sat_cyan", "C", "SATURATED_CYAN", 5, 4, 0.040f, 0.760f, 0.820f),
        p("sat_blue", "B", "SATURATED_BLUE", 5, 5, 0.070f, 0.150f, 0.880f),
        p("sat_magenta", "M", "SATURATED_RED", 5, 6, 0.780f, 0.060f, 0.680f),
        p("texture_edges", "TE", "TEXTURE", 5, 7, 0.520f, 0.520f, 0.520f)
    };

    public static void main(String[] args) throws IOException {
        Path assetDir = Paths.get("app", "src", "main", "assets", "calibration");
        Path deliverableDir = Paths.get("deliverables");
        Files.createDirectories(assetDir);
        Files.createDirectories(deliverableDir);

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, new Color(18, 18, 18), WIDTH, HEIGHT, new Color(42, 42, 42)));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        drawRegistration(g);
        drawHeader(g);
        drawToneRamps(g);
        drawPatches(g);
        drawFooter(g);
        g.dispose();

        Path assetTarget = assetDir.resolve("ndr_nivukx_calibration_target.png");
        Path assetReference = assetDir.resolve("ndr_nivukx_calibration_reference.json");
        ImageIO.write(image, "png", assetTarget.toFile());
        Files.write(
            assetReference,
            buildReferenceJson().getBytes(StandardCharsets.UTF_8)
        );
        Files.write(
            deliverableDir.resolve("ndr_nivukx_calibration_capture_instructions.md"),
            buildInstructions().getBytes(StandardCharsets.UTF_8)
        );
        Files.copy(
            assetTarget,
            deliverableDir.resolve("ndr_nivukx_tone_color_reference_v2.png"),
            StandardCopyOption.REPLACE_EXISTING
        );
        Files.copy(
            assetReference,
            deliverableDir.resolve("ndr_nivukx_tone_color_reference_v2.json"),
            StandardCopyOption.REPLACE_EXISTING
        );
    }

    private static void drawHeader(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 64));
        g.drawString("NDR10+ / NIVUKX Vision Tone + Color Reference", LEFT, 105);
        g.setFont(new Font("SansSerif", Font.PLAIN, 31));
        g.setColor(new Color(218, 218, 218));
        g.drawString(VERSION + "  |  fixed display brightness  |  no night-light/vivid/adaptive display modes", LEFT, 158);
        g.drawString("Capture full-frame in Normal SDR, NDR10+, and NIVUKX Vision. Tap focus on the center target.", LEFT, 204);
    }

    private static void drawFooter(Graphics2D g) {
        g.setFont(new Font("Monospaced", Font.PLAIN, 30));
        g.setColor(new Color(218, 218, 218));
        g.drawString("Measure inner sample_bounds only; labels and borders are outside the reference sample zone.", LEFT, HEIGHT - 102);
        g.drawString("Rows: neutral, shadow, highlight, skin/texture, sky/foliage, saturated color guards.", LEFT, HEIGHT - 62);
        g.drawString("Reference JSON: assets/calibration/ndr_nivukx_calibration_reference.json", LEFT, HEIGHT - 24);
    }

    private static void drawRegistration(Graphics2D g) {
        g.setStroke(new BasicStroke(8f));
        g.setColor(new Color(245, 245, 245));
        int size = 82;
        drawCorner(g, LEFT - 92, TOP - 92, size, true, true);
        drawCorner(g, WIDTH - LEFT + 10, TOP - 92, size, false, true);
        drawCorner(g, LEFT - 92, HEIGHT - BOTTOM + 10, size, true, false);
        drawCorner(g, WIDTH - LEFT + 10, HEIGHT - BOTTOM + 10, size, false, false);
    }

    private static void drawCorner(Graphics2D g, int x, int y, int size, boolean left, boolean top) {
        int x1 = left ? x : x + size;
        int y1 = top ? y : y + size;
        int x2 = left ? x + size : x;
        int y2 = top ? y + size : y;
        g.drawLine(x1, y1, x2, y1);
        g.drawLine(x1, y1, x1, y2);
    }

    private static void drawToneRamps(Graphics2D g) {
        int rampX = LEFT;
        int rampY = 252;
        int rampW = WIDTH - LEFT * 2 - 650;
        int rampH = 38;
        int labelX = rampX + rampW + 34;
        drawRamp(g, rampX, rampY, rampW, rampH, new float[]{0f, 0f, 0f}, new float[]{1f, 1f, 1f});
        drawRampLabel(g, labelX, rampY + 28, "Neutral 0-100%: gamma, exposure, WB drift");
        drawRamp(g, rampX, rampY + 58, rampW, rampH, new float[]{0f, 0f, 0f}, new float[]{0.16f, 0.16f, 0.16f});
        drawRampLabel(g, labelX, rampY + 86, "Shadow 0-16%: black crush and chroma noise");
        drawRamp(g, rampX, rampY + 116, rampW, rampH, new float[]{0.76f, 0.76f, 0.76f}, new float[]{1f, 1f, 1f});
        drawRampLabel(g, labelX, rampY + 144, "Highlight 76-100%: rolloff and clipping");
        drawRamp(g, rampX, rampY + 174, rampW / 2 - 10, rampH, new float[]{0.20f, 0.02f, 0.02f}, new float[]{1f, 0.18f, 0.13f});
        drawRamp(g, rampX + rampW / 2 + 10, rampY + 174, rampW / 2 - 10, rampH, new float[]{0.10f, 0.36f, 0.72f}, new float[]{0.76f, 0.96f, 1f});
        drawRampLabel(g, labelX, rampY + 202, "Red guard + sky/cyan guard: color pollution");

        int cx = WIDTH - LEFT - 320;
        int cy = 368;
        g.setStroke(new BasicStroke(5f));
        g.setColor(new Color(245, 245, 245));
        g.drawOval(cx - 70, cy - 70, 140, 140);
        g.drawOval(cx - 32, cy - 32, 64, 64);
        g.drawLine(cx - 105, cy, cx + 105, cy);
        g.drawLine(cx, cy - 105, cx, cy + 105);
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        g.drawString("TAP FOCUS", cx - 75, cy + 142);
    }

    private static void drawRamp(
        Graphics2D g,
        int x,
        int y,
        int w,
        int h,
        float[] start,
        float[] end
    ) {
        for (int i = 0; i < w; i++) {
            float t = i / Math.max(1f, (float) (w - 1));
            float r = start[0] + (end[0] - start[0]) * t;
            float gg = start[1] + (end[1] - start[1]) * t;
            float b = start[2] + (end[2] - start[2]) * t;
            g.setColor(toColor(r, gg, b));
            g.drawLine(x + i, y, x + i, y + h);
        }
        g.setColor(new Color(245, 245, 245, 180));
        g.setStroke(new BasicStroke(3f));
        g.drawRect(x, y, w, h);
    }

    private static void drawRampLabel(Graphics2D g, int x, int baseline, String label) {
        g.setFont(new Font("SansSerif", Font.PLAIN, 26));
        g.setColor(new Color(232, 232, 232));
        g.drawString(label, x, baseline);
    }

    private static void drawPatches(Graphics2D g) {
        for (Patch patch : PATCHES) {
            int x = LEFT + patch.col * (PATCH_W + GAP);
            int y = TOP + patch.row * (PATCH_H + GAP);
            g.setColor(toColor(patch.r, patch.g, patch.b));
            g.fillRoundRect(x, y, PATCH_W, PATCH_H, 14, 14);
            drawTextureIfNeeded(g, patch, x, y);

            int sampleX = x + Math.round(PATCH_W * SAMPLE_LEFT);
            int sampleY = y + Math.round(PATCH_H * SAMPLE_TOP);
            int sampleW = Math.round(PATCH_W * (SAMPLE_RIGHT - SAMPLE_LEFT));
            int sampleH = Math.round(PATCH_H * (SAMPLE_BOTTOM - SAMPLE_TOP));
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(255, 255, 255, 88));
            g.drawRect(sampleX, sampleY, sampleW, sampleH);

            float luma = patch.r * 0.2126f + patch.g * 0.7152f + patch.b * 0.0722f;
            g.setColor(luma > 0.42f ? new Color(20, 20, 20) : new Color(245, 245, 245));
            g.setFont(new Font("SansSerif", Font.BOLD, 34));
            g.drawString(patch.label, x + 18, y + 42);
            g.setFont(new Font("Monospaced", Font.PLAIN, 18));
            g.drawString(patch.id, x + 18, y + PATCH_H - 18);

            g.setStroke(new BasicStroke(4f));
            g.setColor(new Color(250, 250, 250, 175));
            g.drawRoundRect(x, y, PATCH_W, PATCH_H, 14, 14);
        }
    }

    private static void drawTextureIfNeeded(Graphics2D g, Patch patch, int x, int y) {
        if (!patch.role.equals("TEXTURE")) return;
        g.setStroke(new BasicStroke(3f));
        Color dark = new Color(20, 20, 20, 120);
        Color light = new Color(245, 245, 245, 120);
        for (int i = 0; i < PATCH_W; i += 24) {
            g.setColor((i / 24) % 2 == 0 ? dark : light);
            g.drawLine(x + i, y + 8, x + i, y + PATCH_H - 8);
        }
        for (int i = 0; i < PATCH_H; i += 28) {
            g.setColor((i / 28) % 2 == 0 ? light : dark);
            g.drawLine(x + 8, y + i, x + PATCH_W - 8, y + i);
        }
    }

    private static String buildReferenceJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"version\": \"").append(VERSION).append("\",\n");
        json.append("  \"encoding\": \"sRGB display reference, 8-bit PNG\",\n");
        json.append("  \"measurement_rule\": \"Use sample_bounds_normalized, not full patch bounds; labels and borders are excluded.\",\n");
        json.append("  \"capture_modes\": [\"Normal SDR\", \"NDR10+\", \"NIVUKX Vision\"],\n");
        json.append("  \"canvas\": { \"width\": ").append(WIDTH)
            .append(", \"height\": ").append(HEIGHT)
            .append(", \"rows\": ").append(ROWS)
            .append(", \"columns\": ").append(COLS).append(" },\n");
        json.append("  \"patches\": [\n");
        for (int i = 0; i < PATCHES.length; i++) {
            Patch p = PATCHES[i];
            int x = LEFT + p.col * (PATCH_W + GAP);
            int y = TOP + p.row * (PATCH_H + GAP);
            int sx0 = x + Math.round(PATCH_W * SAMPLE_LEFT);
            int sy0 = y + Math.round(PATCH_H * SAMPLE_TOP);
            int sx1 = x + Math.round(PATCH_W * SAMPLE_RIGHT);
            int sy1 = y + Math.round(PATCH_H * SAMPLE_BOTTOM);
            json.append("    { \"id\": \"").append(p.id).append("\", ")
                .append("\"label\": \"").append(p.label).append("\", ")
                .append("\"role\": \"").append(p.role).append("\", ")
                .append("\"row\": ").append(p.row).append(", ")
                .append("\"column\": ").append(p.col).append(", ")
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
            if (i < PATCHES.length - 1) json.append(",");
            json.append("\n");
        }
        json.append("  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private static String buildInstructions() {
        return String.join("\n",
            "# NDR10+ / NIVUKX Vision Calibration Capture",
            "",
            "Display `deliverables/ndr_nivukx_tone_color_reference_v2.png` full-screen on a second display.",
            "The same PNG is also packaged at `app/src/main/assets/calibration/ndr_nivukx_calibration_target.png`.",
            "",
            "Required display setup:",
            "- Disable adaptive brightness.",
            "- Disable eye comfort/night light.",
            "- Disable vivid/display enhancement modes.",
            "- Use fixed brightness. Start at 70 percent; do not change it during the captures.",
            "- Clean the display and keep it perpendicular to the camera.",
            "",
            "Capture order:",
            "1. Record 5 seconds in normal SDR/reference mode.",
            "2. Record 5 seconds in NDR10+.",
            "3. Record 5 seconds in NIVUKX Vision.",
            "",
            "Framing:",
            "- Fill the camera preview with the full chart.",
            "- Keep all chart edges visible.",
            "- Tap focus on the `TAP FOCUS` target before each recording.",
            "- Lock exposure if the app allows it after the chart is stable.",
            "- Do not crop, zoom, or rotate between modes.",
            "",
            "Requested filenames:",
            "- `NORMAL_REFERENCE_CHART`",
            "- `NDR10_REFERENCE_CHART`",
            "- `NIVUKX_REFERENCE_CHART`",
            "",
            "Send back the three recorded clips or still frames. The reference files are",
            "`deliverables/ndr_nivukx_tone_color_reference_v2.json` and",
            "`app/src/main/assets/calibration/ndr_nivukx_calibration_reference.json`.",
            ""
        );
    }

    private static Patch p(String id, String label, String role, int row, int col, float r, float g, float b) {
        return new Patch(id, label, role, row, col, r, g, b);
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
