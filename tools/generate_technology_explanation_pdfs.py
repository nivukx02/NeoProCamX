from __future__ import annotations

import re
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import cm
from reportlab.platypus import (
    PageBreak,
    Paragraph,
    Preformatted,
    SimpleDocTemplate,
    Spacer,
)


ROOT = Path(__file__).resolve().parents[1]
OUT_DIR = ROOT / "docs" / "technology_explanations"
PDF_DIR = OUT_DIR / "pdf"
MD_DIR = OUT_DIR / "markdown"


def slugify(name: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


FEATURES = [
    {
        "name": "NDR10+ Dynamic HDR",
        "summary": "NeoProCamX dynamic HDR delivery profile using BT.2020/PQ-compatible 10-bit transport with app-owned tone mapping and metadata.",
        "anchors": [
            "core-math/src/main/kotlin/com/neoproc/neoproccamx/recording/HdrImagingMath.kt",
            "app/src/main/java/com/neoproc/neoproccamx/pipeline/HdrVersionRenderPolicy.kt",
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordingHdrVideoLookPolicy.kt",
            "app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "NDR10+ is the project-owned dynamic range mode for HDR-looking video without claiming a licensed vendor profile.",
                    "The pipeline treats scene light as linear radiance, applies a dynamic shoulder, maps color into BT.2020/PQ transport when supported, and records truth metadata so the output is not mislabeled.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. Camera2 exposes a 10-bit-capable stream when the device supports it.",
                    "2. Vulkan receives the frame through the preview/recording path and applies Neo tone, denoise, sharpness, and color-volume controls.",
                    "3. RecordingHdrVideoLookPolicy maps NDR10+ to BT.2020 with ST.2084/PQ transfer when the encoder can accept it.",
                    "4. EliteVideoQualityContract audits bit depth, transport, color science, and evidence before metadata is emitted.",
                ],
            ),
            (
                "Core Math",
                [
                    "Scene luminance is computed from linear RGB:",
                    "Y = 0.2627002 R + 0.6779981 G + 0.0593017 B  (BT.2020 luma)",
                    "Dynamic percentile anchors are derived from a frame histogram H:",
                    "CDF(k) = sum_{i=0..k} H_i",
                    "P_q = (argmin_k CDF(k) >= q * CDF(255)) / 255 * L_peak",
                    "The dynamic knee uses highlight anchors:",
                    "K = clamp(0.65 P90 + 0.35 P99, P50 + 1, 4000)",
                    "A smooth shoulder maps highlight lightness L:",
                    "T(L) = L, if L <= K",
                    "T(L) = K + (1 - K) * tanh(kappa * (L - K) / (1 - K)), if L > K",
                    "PQ encoding follows ST.2084:",
                    "V = ((c1 + c2 * (L / 10000)^m1) / (1 + c3 * (L / 10000)^m1))^m2",
                    "with m1=2610/16384, m2=2523/32, c1=3424/4096, c2=2413/128, c3=2392/128.",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "NDR10+ is a NeoProCamX label. It is not advertised as vendor-certified HDR10+ unless actual platform/vendor evidence exists.",
                    "On devices without 10-bit/PQ encoder support, the project records fallback evidence instead of pretending true NDR10+ transport happened.",
                ],
            ),
        ],
    },
    {
        "name": "NIVUKX Vision",
        "summary": "Project-owned premium HDR color-volume profile built above the NDR transport path.",
        "anchors": [
            "core-math/src/main/kotlin/com/neoproc/neoproccamx/recording/HdrImagingMath.kt",
            "app/src/main/java/com/neoproc/neoproccamx/pipeline/HdrVersionRenderPolicy.kt",
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordingHdrVideoLookPolicy.kt",
            "app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "NIVUKX Vision is the app-owned HDR rendering identity for high-contrast scenes, neon colors, and wide-gamut highlights.",
                    "It uses BT.2020/PQ-compatible transport where possible while doing the creative color-volume decisions inside Neo math and Vulkan shaders.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. UI or profile policy resolves the requested HDR mode to NIVUKX Vision.",
                    "2. HdrVersionRenderPolicy selects stronger shadow, chroma-denoise, texture, and highlight-compression floors than NDR10+.",
                    "3. Vulkan uses Neo mode parameters to apply the NIVUKX color-volume map and optional NLogs delivery path.",
                    "4. Recording metadata says NIVUKX Vision, not a third-party Dolby/Vendor profile.",
                ],
            ),
            (
                "Core Math",
                [
                    "The signal starts from normalized scene luminance L in [0, 1] with headroom for highlights.",
                    "Perceptual HDR signal:",
                    "V_pq = PQ(L * L_peak)",
                    "NIVUKX color-volume compression keeps hue stable by acting on perceptual chroma C:",
                    "C = sqrt(a^2 + b^2)",
                    "h = atan2(b, a)",
                    "C_nivukx = C * (1 - tanh(omega * L^2) * gamma_compress)",
                    "a' = C_nivukx * cos(h)",
                    "b' = C_nivukx * sin(h)",
                    "The pipeline compresses chroma near white to avoid channel clipping:",
                    "C_final = C_nivukx * (1 - smoothstep(0.85, 0.98, L) * 0.85)",
                    "10-bit code values are:",
                    "D_10 = round(clamp(V_pq, 0, 1) * 1023)",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "NIVUKX Vision is proprietary naming for NeoProCamX's own color-volume and tone mapping logic.",
                    "The project explicitly avoids claiming Dolby certification for this mode.",
                ],
            ),
        ],
    },
    {
        "name": "NIVUKX APEX",
        "summary": "Scene-adaptive NIVUKX profile family with SHARP, ZERO, and CINEMA modes.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/apex/ApexProfile.kt",
            "app/src/main/java/com/neoproc/neoproccamx/apex/ApexToneMapper.kt",
            "app/src/main/java/com/neoproc/neoproccamx/apex/ApexPipelineOrchestrator.kt",
            "app/src/main/cpp/shaders/vulkan/apex/apex_tonemap.frag",
            "app/src/main/cpp/shaders/vulkan/apex/apex_ictcp.frag",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "NIVUKX APEX is the higher-level scene mapper for the NIVUKX family.",
                    "It exposes three project-owned modes: SHARP for maximum detail, ZERO for near-linear optical truth, and CINEMA for softer highlight rolloff.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. SceneAnalyzer produces per-frame scene metrics such as p99 nits, shadow ratio, motion score, and noise risk.",
                    "2. ApexProfileParams.compute(metrics, mode) converts those metrics into shader parameters.",
                    "3. ApexPipelineState stores the selected mode and current profile parameters with preview/recording parity.",
                    "4. The native/Vulkan path consumes those parameters for tone, chroma compression, and detail gain.",
                ],
            ),
            (
                "Core Math",
                [
                    "Dynamic-range score drs and shadow need sn modulate the profile:",
                    "sourcePeak = baseSourcePeak + sourceLift * drs",
                    "targetPeak = baseTargetPeak + targetLift * drs",
                    "shadowLift = baseShadowLift + shadowScale * sn",
                    "For SHARP mode in code:",
                    "sourcePeak = 12000 + 4000 * drs",
                    "targetPeak = 2000 + 400 * drs",
                    "shoulderStrength = 4.80 + 0.62 * drs",
                    "shadowLift = 0.0014 + 0.0018 * sn",
                    "Tone mapping uses a soft shoulder:",
                    "L_out = L_knee + (1 - L_knee) * (1 - exp(-s * (L - L_knee) / (1 - L_knee)))",
                    "Detail gains are mode dependent:",
                    "SHARP: gain1=0.78, gain2=0.28, gain3=0.12",
                    "ZERO: gain1=gain2=gain3=0",
                    "CINEMA: gain1=0.48, gain2=0.16, gain3=0.08",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "APEX is an internal NeoProCamX profile family. It is not a third-party HDR format.",
                    "The code comments state vendorCertifiedHdr10Plus=false and vendorCertifiedDolbyVision=false.",
                ],
            ),
        ],
    },
    {
        "name": "N-Log v4 Scene Linear Family",
        "summary": "Original NeoProCamX log curve family for 10-bit wide-gamut log delivery.",
        "anchors": [
            "core-math/src/main/kotlin/com/neoproc/neoproccamx/core/NeoLogCurve.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ai/NLogMath.kt",
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordingVideoEncoderPlanResolver.kt",
            "app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "N-Log is the project-owned scene-referred log encoding family.",
                    "It is not an ARRI/Sony/Canon/RED clone. The code provides Base, Shadow, Highlight, and Cinema variants using the same reversible contract.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. UI selects a LOG mode or an N-Log color science profile.",
                    "2. NLogMath maps the label to BASE, SHADOW, HIGHLIGHT, or CINEMA.",
                    "3. NeoLogCurve.encode stores scene-linear signal into a 10-bit-friendly log code space.",
                    "4. Encoder planning requests Rec.2020/P010/Main10 where available for LOG recording.",
                ],
            ),
            (
                "Core Math",
                [
                    "Each variant is defined by toeCut, toeSlope, and logDensity.",
                    "The toe section is a C1 Hermite curve so the value and first derivative match the log body at toeCut.",
                    "Toe profile constants:",
                    "toeHeight = toeCut * toeSlope",
                    "grayLog = ln(1 + logDensity * (0.18 - toeCut))",
                    "logScale = (0.50 - toeHeight) / grayLog",
                    "For x >= toeCut:",
                    "y = toeHeight + logScale * ln(1 + logDensity * (x - toeCut))",
                    "For x < toeCut:",
                    "t = clamp(x / toeCut, 0, 1)",
                    "h10 = t^3 - 2t^2 + t",
                    "h01 = -2t^3 + 3t^2",
                    "h11 = t^3 - t^2",
                    "y = h10 * toeCut * toeEntrySlope + h01 * toeHeight + h11 * toeCut * logSlopeAtToe",
                    "10-bit code:",
                    "code10 = round(clamp(y, 0, 1) * 1023)",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "Dynamic range is marked DEVICE_MEASUREMENT_REQUIRED in code; the project does not claim a measured stop count without device evidence.",
                    "The retained ACEScc-style API aliases are documented as Neo-owned N-Log aliases, not certified ACEScc encoders.",
                ],
            ),
        ],
    },
    {
        "name": "Neo Wide Gamut",
        "summary": "Proprietary D55 wide-gamut working space used by Neo color science.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt",
            "app/src/main/java/com/neoproc/neoproccamx/color/AcesColorEngine.kt",
            "app/src/main/java/com/neoproc/neoproccamx/color/NeoGamutCat16Math.kt",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "Neo Wide Gamut is the project-owned wide-gamut color space for highly saturated colors and D55 cinematic white.",
                    "It sits alongside standards such as Rec.709 and Rec.2020 but uses custom primaries and matrices.",
                ],
            ),
            (
                "Primaries",
                [
                    "CIE 1931 xy primaries:",
                    "Red   = (0.7347, 0.2653)",
                    "Green = (0.1152, 0.8260)",
                    "Blue  = (0.0860, 0.0110)",
                    "White = D55 = (0.3324, 0.3474)",
                ],
            ),
            (
                "Core Math",
                [
                    "RGB to XYZ matrix used in source:",
                    "[X]   [0.697475  0.127814  0.125139] [R]",
                    "[Y] = [0.252003  0.918002  0.016001] [G]",
                    "[Z]   [0.000000  0.062402  1.341142] [B]",
                    "XYZ to Neo matrix used by NeoGamutCat16Math:",
                    "[R]   [ 1.506115607 -0.200307072 -0.138142484] [X]",
                    "[G] = [-0.413783104  1.145237837  0.024945497] [Y]",
                    "[B]   [ 0.019252915 -0.053286775  0.744472510] [Z]",
                    "A color is converted to a target space by:",
                    "RGB_target = M_XYZ_to_target * M_adapt(sourceWhite,targetWhite) * M_neo_to_XYZ * RGB_neo",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "Neo Wide Gamut is a project-owned color space, not a certification program.",
                    "The source maps P3/ACES style user selections into Neo Wide Gamut as an internal working route where appropriate.",
                ],
            ),
        ],
    },
    {
        "name": "NeoGamut CAT16 Adaptation",
        "summary": "CIE CAT16 white-point adaptation from dynamic scene white to Neo D55.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/color/NeoGamutCat16Math.kt",
            "app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanColorScienceUniformPolicy.kt",
            "app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "CAT16 adaptation keeps colors stable when the sensor white balance changes.",
                    "The project computes a source-white to D55 matrix on the CPU and sends matrix uniforms to the Vulkan rendering path.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. White balance or kelvin/lux data provides a source white point.",
                    "2. NeoGamutCat16Math calculates a 3x3 adaptation matrix.",
                    "3. VulkanColorScienceUniformPolicy packages color-science uniforms.",
                    "4. The shader applies the matrix per pixel before OkLCh-style grading and tone mapping.",
                ],
            ),
            (
                "Core Math",
                [
                    "CAT16 cone response:",
                    "rho_s = M16 * W_s",
                    "rho_t = M16 * W_t",
                    "scale_i = rho_t_i / rho_s_i",
                    "M_adapt = M16_inv * diag(scale) * M16",
                    "The matrix in source is:",
                    "M16 = [[0.401288, 0.650173, -0.051461],",
                    "       [-0.250268, 1.204414, 0.045854],",
                    "       [-0.002079, 0.048952, 0.953127]]",
                    "For a CIE xy white point:",
                    "X = x / y, Y = 1, Z = (1 - x - y) / y",
                    "Adaptive kelvin/lux strength:",
                    "M_final = I + (M_adapt - I) * adaptationStrength",
                    "adaptationStrength = clamp(0.58 + smoothstep(8, 800, lux) * 0.40, 0.58, 0.98)",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "CAT16 improves white adaptation but does not replace physical sensor calibration.",
                    "Bad or missing camera metadata causes identity fallback to avoid unstable color shifts.",
                ],
            ),
        ],
    },
    {
        "name": "NeoRadiance Math",
        "summary": "Original HDR and cinema color computation library for tone, shadow, shoulder, and chroma preservation.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/ai/NeoRadianceMath.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ai/AIProcessor.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ui/VideoFormatDeliveryPolicy.kt",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "NeoRadiance is the project-owned math library for tone response, HDR consistency, and color-preserving highlight control.",
                    "It provides first-principles functions used by AI processing and video delivery decisions.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. Pixels are linearized from sRGB or sensor-derived RGB.",
                    "2. NeoRadiance computes luma, tone response, shadow lift, highlight compression, and chroma consistency.",
                    "3. AI and HDR processing use these weights to merge frames and preserve natural color.",
                ],
            ),
            (
                "Core Math",
                [
                    "Linear luminance:",
                    "Y = 0.2126 R + 0.7152 G + 0.0722 B",
                    "Cascade Fold tone curve:",
                    "p = ln(1 + 3.5 x)",
                    "spine = tanh((p - 0.65) * (2.2 + 1.8 k))",
                    "normalized = (spine + 1) / 2",
                    "over = max(x - 1, 0)",
                    "shoulder = over * (1 - exp(-over / (1.2 + 2s)))",
                    "out = clamp(normalized + shoulder, 0, 1.35)",
                    "Depth Curve shadow lift:",
                    "if x < 0.08: out = (x / 0.08)^(0.65 + 0.25 strength) * 0.08 + 0.02 x strength",
                    "else: out = x",
                    "Quantum channel compression:",
                    "maxC = max(R, G, B)",
                    "scale = 1 / (1 + (1 - exp(-(maxC - 1) * (1.5 + 2 strength))) * strength)",
                    "RGB' = RGB * scale",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "NeoRadiance is original app math, not a substitute for a calibrated camera IDT.",
                    "Its physical accuracy depends on camera metadata, white balance, and actual sensor response.",
                ],
            ),
        ],
    },
    {
        "name": "NPV10 Master Container",
        "summary": "Private NeoProCamX master format for 10-bit P010/YUV and packed RAW payloads.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10ContainerPolicy.kt",
            "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt",
            "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10FileReader.kt",
            "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "NPV10 is the project-owned private master container for high-bit-depth local acquisition and later MOV conversion.",
                    "It preserves explicit payload format metadata so an 8-bit fallback cannot be mislabeled as native 10-bit.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. Npv10RecordingManager creates a private master session.",
                    "2. Npv10FileWriter writes header metadata and frame chunks.",
                    "3. Payload format identifies P010, planar YUV420P10, expanded 8-bit, RAW12 packed, or RAW14 packed.",
                    "4. Npv10IntegrityValidator audits the master before publishing or conversion.",
                ],
            ),
            (
                "Core Math",
                [
                    "Container identity:",
                    "MAGIC = 'NPV10'",
                    "FRAME_MAGIC = 'FRM0'",
                    "For YUV420 10-bit style payloads, estimated frame payload bytes:",
                    "Y_samples = W * H",
                    "UV_samples = 2 * ceil(W / 2) * ceil(H / 2)",
                    "bytes = (Y_samples + UV_samples) * 2",
                    "For packed RAW payloads:",
                    "bytes = W * H * 2",
                    "10-bit P010 sample convention:",
                    "word16[15:6] = code10[9:0]",
                    "word16[5:0] = padding or project-specific residual field depending on payload format",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "NPV10 is private to NeoProCamX. It is not a public standard container.",
                    "The project keeps payloadFormat and bit-depth evidence separate to prevent false native-10-bit claims.",
                ],
            ),
        ],
    },
    {
        "name": "NeoRadiance RAW and NPRAW Modes",
        "summary": "Project-owned RAW mode family with explicit bit-depth and compression metadata.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/recording/CinemaVideoConfig.kt",
            "app/src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt",
            "app/src/main/java/com/neoproc/neoproccamx/recording/RawVideoContractPolicy.kt",
            "app/src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoBitDepthPolicy.kt",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "NeoRadiance RAW/NPRAW modes describe how RAW sensor data is stored or transformed inside the app.",
                    "Modes include NEO_SPECTRAL_RAW, NEO_PURE_RAW, NEO_FINE_RAW, and NEO_DENSE_RAW, plus compatibility lossless modes.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. The selected RAW compression mode is normalized by RawCompressionMode.fromName.",
                    "2. RawVideoContractPolicy resolves video capture to the project RAW contract.",
                    "3. RawVideoManager writes DNG or project stream payloads depending on mode.",
                    "4. Bit-depth policy reports 12/14/16-bit intent separately from actual camera capability.",
                ],
            ),
            (
                "Core Math",
                [
                    "Raw signal normalization:",
                    "rawNorm = clamp((rawCode - blackLevel) / (whiteLevel - blackLevel), 0, 1)",
                    "Exposure-normalized radiance:",
                    "radiance = rawNorm / max(exposureSeconds * analogGain, epsilon)",
                    "RAW bit-depth mapping by mode:",
                    "OFF, LOSSLESS, LOSSLESS_ENHANCED, VISUALLY_LOSSLESS: 16-bit",
                    "HIGH_QUALITY, NEO_SPECTRAL_RAW, NEO_PURE_RAW, NEO_FINE_RAW: 14-bit",
                    "HIGH_COMPRESSION: 12-bit uncompressed RAW video contract",
                    "NEO_DENSE_RAW: 16-bit deep predictive pipeline",
                    "Approximate compression/storage UI hint:",
                    "storageBytes ~= rawBytes / compressionRatio(mode)",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "RAW mode can only be true sensor RAW when Camera2 exposes RAW_SENSOR/RAW_PRIVATE and the device supports the requested bit depth.",
                    "If 12-bit RAW is not exposed by the HAL, the project should report unavailable instead of faking RAW.",
                ],
            ),
        ],
    },
    {
        "name": "Vulkan Cinema Debayer",
        "summary": "GPU-oriented RAW Bayer demosaic and highlight recovery path.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanCinemaDebayerPipeline.kt",
            "app/src/main/cpp/shaders/vulkan/cinema_debayer.comp",
            "app/src/main/cpp/shaders/vulkan/demosaic_vulkan.comp",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "Vulkan Cinema Debayer converts RAW Bayer sensor grids into scene-linear RGB before display or encoded delivery.",
                    "It exists so RAW workflows can bypass default display tone mapping and keep highlight latitude under app control.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. A RAW HardwareBuffer/Image is described by width, height, stride, black/white levels, and Bayer pattern.",
                    "2. The pipeline normalizes RAW samples to linear light.",
                    "3. The compute shader reconstructs missing color channels using edge-aware interpolation.",
                    "4. Highlight recovery and shoulder compression preserve super-white detail before output.",
                ],
            ),
            (
                "Core Math",
                [
                    "RAW normalization:",
                    "r(x,y) = clamp((raw(x,y) - blackLevel) / (whiteLevel - blackLevel), 0, 1)",
                    "Bayer pattern selects the known channel at each pixel:",
                    "RGGB, GRBG, GBRG, or BGGR",
                    "Edge-directed green reconstruction compares horizontal and vertical gradients:",
                    "H = abs((C[x-2,y] + 2C[x,y] + C[x+2,y]) - 2(G[x-1,y] + G[x+1,y]))",
                    "V = abs((C[x,y-2] + 2C[x,y] + C[x,y+2]) - 2(G[x,y-1] + G[x,y+1]))",
                    "If H < V:",
                    "G = (G[x-1,y] + G[x+1,y]) / 2 + (2C[x,y] - C[x-2,y] - C[x+2,y]) / 4",
                    "If V < H:",
                    "G = (G[x,y-1] + G[x,y+1]) / 2 + (2C[x,y] - C[x,y-2] - C[x,y+2]) / 4",
                    "Highlight shoulder:",
                    "L' = threshold + (1 - threshold) * (1 - exp(-strength * (L - threshold) / (1 - threshold)))",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "The debayer path is active only when rawGpuDebayerActive and Vulkan support are true.",
                    "RAW recording itself may remain RAW_SENSOR direct data, meaning debayer is not applied to the encoded stream unless explicitly routed.",
                ],
            ),
        ],
    },
    {
        "name": "Pixel Reality",
        "summary": "Runtime truth-reporting layer for preview, recording, AI, and RAW processing claims.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/ui/PixelProcessingRealityPolicy.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt",
            "app/src/test/java/com/neoproc/neoproccamx/ui/PixelProcessingRealityPolicyTest.kt",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "Pixel Reality prevents the app from claiming that a feature is active when the actual frame path bypasses it.",
                    "It classifies each stage as real GPU shader, real GPU compute, CPU analysis, Camera2 ISP, planned-only, or unsupported.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. RecordingPipelineParityPolicy resolves the recording feed path.",
                    "2. Runtime evidence reports whether native preview, Vulkan, AI, multi-frame, GPU import, and RAW debayer paths are active.",
                    "3. PixelProcessingRealityPolicy builds per-stage evidence and a signature string.",
                    "4. UI and metadata can use the signature to avoid fake quality claims.",
                ],
            ),
            (
                "Core Logic",
                [
                    "Preview custom GPU flag:",
                    "previewGpu = nativePreviewActive && vulkanRuntimeSupported",
                    "Recording custom GPU flag:",
                    "recordingGpu = vulkanRuntimeSupported && feedPath == INTERNAL_HARDWARE_IMPORT_ENCODER && recordingGpuFrameImportValidated",
                    "Custom GPU active:",
                    "customGpuActive = recordingGpu",
                    "Camera2 custom-processing bypass:",
                    "bypass = !recordingGpu && feedPath != INTERNAL_HARDWARE_IMPORT_ENCODER",
                    "The signature is:",
                    "PIXEL_REALITY|feed=<path>|previewGpu=<bool>|recordingGpu=<bool>|customGpuActive=<bool>|customGpuBypass=<bool>|real=<stages>|planned=<stages>|unsupported=<stages>",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "A stage appears as real for recording only after recordingGpuFrameImportValidated is true.",
                    "This is a verification layer, not an image-processing algorithm.",
                ],
            ),
        ],
    },
    {
        "name": "Preview Recording Parity",
        "summary": "Policy system that keeps the viewfinder and encoded video on the same processing route where possible.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPreviewContinuityPolicy.kt",
            "app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanPreviewRecordingParityPolicy.kt",
            "app/src/main/cpp/vulkan_bridge.cpp",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "Preview/Recording Parity is the WYSIWYG contract: the recorded file should match the live preview as closely as the hardware path allows.",
                    "The project prefers internal hardware import into the encoder when Vulkan support and stability evidence are present.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. The policy builds a parity plan from mode, Vulkan runtime support, RAW state, and hardware-import preference.",
                    "2. RAW video uses direct Camera2 RAW surfaces because RAW should remain sensor data.",
                    "3. Non-RAW PRO/LOG/NDR modes can use INTERNAL_HARDWARE_IMPORT_ENCODER when stable.",
                    "4. Parity certificates compare color space, denoise, sharpness, LUT, and stage signatures.",
                ],
            ),
            (
                "Core Logic",
                [
                    "Feed path selection:",
                    "if rawVideoEnabled:",
                    "    feed = CAMERA2_DIRECT_RECORD_SURFACE",
                    "else if vulkanRuntimeSupported && (preferInternalHardwareImport || internalHardwareImportStable):",
                    "    feed = INTERNAL_HARDWARE_IMPORT_ENCODER",
                    "else:",
                    "    feed = CAMERA2_PREVIEW_PARITY_RECORD_SURFACE",
                    "Parity condition:",
                    "parityPass = previewColorSpace == recordingColorSpace",
                    "          && previewStages == recordingStages",
                    "          && previewDenoiseProfile == recordingDenoiseProfile",
                    "          && previewSharpnessProfile == recordingSharpnessProfile",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "Parity cannot be absolute if the device refuses hardware import, 10-bit transport, or encoder-surface binding.",
                    "The project records evidence and warnings instead of forcing a false parity claim.",
                ],
            ),
        ],
    },
    {
        "name": "HardwareBuffer Zero Copy",
        "summary": "Camera2-to-Vulkan-to-MediaCodec buffer path designed to avoid app-visible CPU copies.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/pipeline/HardwareBufferZeroCopyVerifier.kt",
            "app/src/main/cpp/native_buffer_manager.cpp",
            "app/src/main/cpp/native_buffer_manager.h",
            "app/src/main/cpp/vulkan_bridge.cpp",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "The zero-copy path keeps frames in hardware buffers so GPU and encoder surfaces can exchange them without JVM byte-array copies.",
                    "This reduces latency, garbage collection, and memory bandwidth pressure.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. Camera2 writes into an ImageReader or Surface backed by HardwareBuffer.",
                    "2. Image.hardwareBuffer is acquired and validated.",
                    "3. Native Vulkan imports the external memory or samples from the buffer-backed image.",
                    "4. Vulkan renders into the encoder surface, and MediaCodec consumes the frame.",
                ],
            ),
            (
                "Core Logic",
                [
                    "Required usage flags:",
                    "usage = GPU_SAMPLED_IMAGE | GPU_COLOR_OUTPUT",
                    "Frame validation checks:",
                    "zeroCopyAvailable = hardwareBuffer != null",
                    "isTrulyZeroCopy = validationTimeNs < 100000",
                    "The performance model is:",
                    "T_frame = T_camera_queue + T_import + T_shader + T_encoder_queue",
                    "Zero-copy targets T_import approximately 0 and avoids:",
                    "T_copy = bytesPerFrame / memoryBandwidth",
                    "For 4K P010, bytesPerFrame ~= (3840*2160 + 2*1920*1080) * 2 ~= 24.9 MB",
                    "Avoiding even one full copy per frame saves about 1.5 GB/s at 60 fps.",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "HardwareBuffer presence does not prove the entire path is zero-copy; it is a zero-copy candidate.",
                    "The project uses runtime validation and import counters to distinguish real import from fallback.",
                ],
            ),
        ],
    },
    {
        "name": "IMAX Strict Quality Profile",
        "summary": "Current high-end quality profile and canonical uniform checksum policy.",
        "anchors": [
            "core-math/src/main/kotlin/com/neoproc/neoproccamx/core/HighEndQualityPolicy.kt",
            "app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag",
            "app/src/test/java/com/neoproc/neoproccamx/core/HighEndQualityPolicyTest.kt",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "IMAX_STRICT_10_10 is the current high-end profile name behind SingularityHorizonProfile/ImaxStrictProfile.",
                    "It centralizes detail, denoise, bitrate, LUT, AI, export, and attestation constants.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. HighEndQualityPolicy exposes strict mode, full sensor, scaling, and runtime tier switches.",
                    "2. ImaxStrictProfile aliases SingularityHorizonProfile constants for legacy call sites.",
                    "3. canonicalUniformPayload serializes important constants.",
                    "4. CANONICAL_UNIFORM_SHA512 hashes the payload to detect CPU/GPU coefficient drift.",
                ],
            ),
            (
                "Core Math",
                [
                    "Canonical hash:",
                    "payload = 'profile=...|ultraDetail=<bits>|adaptiveGain=<bits>|...'",
                    "checksum = SHA512(UTF8(payload))",
                    "Quality boost selection:",
                    "boost(fps) = 14400 if fps >= 480",
                    "           = 7200 if fps >= 240",
                    "           = 3600 if fps >= 120",
                    "           = 1500 if fps >= 60",
                    "           = 750 otherwise",
                    "Bitrate floor by resolution:",
                    "floorBps = table(width * height) * 1,000,000",
                    "Examples currently in source:",
                    "4K floor = 820 Mbps, 8K floor = 1600 Mbps, 1080p floor = 340 Mbps",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "These constants are policy targets. Actual encoder bitrate and resolution still depend on device capability.",
                    "A checksum verifies coefficient identity, not visual quality by itself.",
                ],
            ),
        ],
    },
    {
        "name": "Elite Video Quality Contract",
        "summary": "Audit and certificate layer for mode, bit depth, color science, bitrate, and runtime evidence.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContract.kt",
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicy.kt",
            "app/src/test/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContractTest.kt",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "The Elite Video Quality Contract is the project's quality gate.",
                    "It maps requests into tiers such as ELITE_PRO_SDR_10BIT, ELITE_HDR_NDR10_PLUS_10BIT, ELITE_LOG_N_LOG_* and ELITE_NPV10_*.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. A request describes mode, width, height, fps, codec, HDR/log profile, bit depth, payload, and color selections.",
                    "2. Policy normalizes HDR and N-Log versions.",
                    "3. The certificate records pass/fail, quality tier, ten-bit requirement, bitrate floor, quality value, and failure reasons.",
                    "4. Metadata merges manual color selection, enhancement plan, SDR/N-Log/HDR mastering plan, and truth metadata.",
                ],
            ),
            (
                "Core Logic",
                [
                    "Certificate status:",
                    "PASS iff failureReasons is empty and evidence requirements for the selected mode are met.",
                    "Ten-bit requirement:",
                    "requiresTenBit = mode in {PRO, HDR, LOG, NPV10} or hdrMode != OFF or logProfile active",
                    "NPV10 native truth condition:",
                    "native10 = sourceBitDepth >= 10 && storageBitDepth >= 10 && payloadFormat in {P010_LE, YUV420P10_LE}",
                    "Enhancement metadata uses non-destructive truth flags:",
                    "nativeOutputResolutionFaked = false",
                    "truthfulMasterPreserved = mode in {NPV10, RAW}",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "The contract is designed to fail unsafe or unproven claims.",
                    "It does not create hardware support; it only audits whether the requested claim is supported by evidence.",
                ],
            ),
        ],
    },
    {
        "name": "Neo 10-bit Dither Export",
        "summary": "10-bit export protection using quantization-aware dithering for NPV10/NDR/NIVUKX/Log paths.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContract.kt",
            "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt",
            "app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp",
            "app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "The Neo 10-bit dither path protects gradients during export and preview when signals are quantized into 10-bit or display-limited output.",
                    "It is especially important for skies, shadows, log curves, and HDR shoulder regions.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. Export policy identifies modes that need dithering, especially NPV10, NIVUKX Vision, NDR10+, and Neo Log.",
                    "2. Shader or converter applies a low-amplitude noise field before quantization.",
                    "3. Quantization maps continuous values into integer code values.",
                    "4. The dither turns coherent banding error into visually smoother high-frequency error.",
                ],
            ),
            (
                "Core Math",
                [
                    "Base 10-bit quantization without dither:",
                    "code = round(clamp(x, 0, 1) * 1023)",
                    "TPDF dither uses the difference of two uniform random variables:",
                    "d = (u1 - u2) / 1023",
                    "x_dithered = clamp(x + d * amount, 0, 1)",
                    "code = round(x_dithered * 1023)",
                    "Blue-noise/JND shaping can scale amount by luminance:",
                    "A(Y) = (1 / 1023) * (0.18 + 0.42 * sqrt(Y)) * (1 - smoothstep(0.78, 1.0, Y))",
                    "x_dithered = x + A(Y) * B(x mod 64, y mod 64, frame mod N)",
                    "Quantization error:",
                    "e = x - code / 1023",
                    "Dither target: decorrelate e from the image structure.",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "Dither does not add real bit depth; it masks quantization structure perceptually.",
                    "The master still needs true 10-bit source/storage evidence for native 10-bit claims.",
                ],
            ),
        ],
    },
    {
        "name": "Vulkan Processed Recording Path",
        "summary": "GPU-rendered frame path that sends processed frames to MediaCodec when hardware import validates.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt",
            "app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt",
            "app/src/main/cpp/vulkan_bridge.cpp",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "The Vulkan processed recording path makes recorded frames use the same shader stack as the preview instead of receiving low-complexity Camera2 direct frames.",
                    "It is the practical route for WYSIWYG denoise, LUT, tone mapping, sharpness, and stabilization parity.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. Camera frames are imported into Vulkan.",
                    "2. ycbcr_preview.frag applies color science, denoise, sharpness, and delivery encoding.",
                    "3. The Vulkan bridge binds the encoder surface and submits the processed frame.",
                    "4. MediaCodec encodes the GPU-produced frame.",
                    "5. Runtime logs and Pixel Reality indicate recordingGpu=true only after import and encoder binding succeed.",
                ],
            ),
            (
                "Core Logic",
                [
                    "The path is considered valid when:",
                    "vulkanRuntimeSupported = true",
                    "feedPath = INTERNAL_HARDWARE_IMPORT_ENCODER",
                    "recordingGpuFrameImportValidated = true",
                    "encoderSurfaceBound = true",
                    "Then:",
                    "recordingGpu = true",
                    "activeStages = {TONE_MAP, COLOR_PROFILE, LUT, DENOISE, SHARPNESS, STABILIZATION}",
                    "Frame cost model:",
                    "T_total = T_import + T_shader + T_encoder_submit",
                    "The goal is to keep T_total <= frameInterval, where frameInterval = 1000 / fps ms.",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "If the encoder surface cannot be bound or no frame imports, the app must report fallback.",
                    "GPU path quality depends on shader correctness, encoder bit depth, and device surface support.",
                ],
            ),
        ],
    },
    {
        "name": "AI Semantic Segmentation Matting",
        "summary": "MediaPipe-powered segmentation and fallback matting path for subject-aware processing.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/ai/SegmentationEngine.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ai/SegmentationData.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ai/AICameraPipeline.kt",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "The segmentation system produces foreground, skin, hair, face, clothes, and person masks used by subject-aware effects.",
                    "It combines MediaPipe multiclass segmentation with temporal smoothing and a skin-chroma fallback when model loading is unavailable.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. AICameraPipeline supplies image frames and luma guide information.",
                    "2. SegmentationEngine runs the model or fallback path.",
                    "3. Masks are refined and temporally stabilized.",
                    "4. Downstream effects use separate semantic confidence channels for bokeh, skin, hair, clothing, and person isolation.",
                ],
            ),
            (
                "Core Math",
                [
                    "Guided filter local linear model:",
                    "q_i = a_k I_i + b_k for i in window omega_k",
                    "a_k = (mean(Ip) - mean(I) mean(p)) / (var(I) + epsilon)",
                    "b_k = mean(p) - a_k mean(I)",
                    "q_i = mean(a)_i I_i + mean(b)_i",
                    "Temporal motion-adaptive blend:",
                    "alpha_motion = alpha_base * exp(-beta * ||v||^2)",
                    "M_final = (1 - alpha_motion) M_current + alpha_motion M_prev_warped",
                    "Skin fallback Mahalanobis distance in CbCr:",
                    "D = sqrt((x - mu)^T Sigma^-1 (x - mu))",
                    "S_skin = 1 / (1 + exp(gamma * (D - tau)))",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "Semantic quality depends on actual model asset availability, input resolution, and runtime acceleration.",
                    "Fallback skin chroma is a continuity path, not a replacement for true semantic segmentation.",
                ],
            ),
        ],
    },
    {
        "name": "Manual Color Science Registry",
        "summary": "User-facing color-space and look registry centered on Neo-owned profiles.",
        "anchors": [
            "app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt",
            "app/src/main/java/com/neoproc/neoproccamx/ui/VideoProfileIntentResolver.kt",
        ],
        "sections": [
            (
                "Purpose",
                [
                    "The manual color registry exposes standards and Neo-owned color technologies without depending on third-party vendor looks.",
                    "Its main proprietary entries are Neo Wide Gamut, NDR10+, NIVUKX Vision, and the N-Log family.",
                ],
            ),
            (
                "Runtime Flow",
                [
                    "1. The user selects color space, color science, LUT, or look from settings.",
                    "2. SettingsOptionCatalog normalizes labels such as Rec.2020-NDR10+, Rec.2020-NIVUKX, Neo Wide Gamut, and N-Log variants.",
                    "3. ColorScience resolves the target profile and matrix.",
                    "4. Vulkan receives uniforms and LUT decisions for preview/recording parity.",
                ],
            ),
            (
                "Core Math",
                [
                    "Saturation and hue should be manipulated in perceptual coordinates:",
                    "C = sqrt(a^2 + b^2)",
                    "h = atan2(b, a)",
                    "C' = C * saturation",
                    "a' = C' * cos(h)",
                    "b' = C' * sin(h)",
                    "Lightness contrast uses a sigmoid:",
                    "L' = 1 / (1 + exp(-k * (L - pivot)))",
                    "Color-space conversion is matrix based:",
                    "RGB_out = M_XYZ_to_out * M_adapt * M_in_to_XYZ * RGB_in",
                ],
            ),
            (
                "Truth Boundary",
                [
                    "Creative looks such as Warm Cinema or Bleach Bypass are app looks, not camera-manufacturer profiles.",
                    "Vendor profile names should fall back to Neo profiles or be normalized away unless explicitly licensed and implemented.",
                ],
            ),
        ],
    },
]


def styles():
    base = getSampleStyleSheet()
    return {
        "title": ParagraphStyle(
            "NeoTitle",
            parent=base["Title"],
            fontName="Helvetica-Bold",
            fontSize=22,
            leading=27,
            textColor=colors.HexColor("#101820"),
            spaceAfter=12,
        ),
        "subtitle": ParagraphStyle(
            "NeoSubtitle",
            parent=base["BodyText"],
            fontName="Helvetica",
            fontSize=10,
            leading=14,
            textColor=colors.HexColor("#3A4650"),
            spaceAfter=16,
        ),
        "h2": ParagraphStyle(
            "NeoH2",
            parent=base["Heading2"],
            fontName="Helvetica-Bold",
            fontSize=14,
            leading=18,
            textColor=colors.HexColor("#0B3D3A"),
            spaceBefore=10,
            spaceAfter=6,
        ),
        "body": ParagraphStyle(
            "NeoBody",
            parent=base["BodyText"],
            fontName="Helvetica",
            fontSize=10,
            leading=14,
            spaceAfter=6,
        ),
        "mono": ParagraphStyle(
            "NeoMono",
            parent=base["Code"],
            fontName="Courier",
            fontSize=8.6,
            leading=11,
            leftIndent=8,
            rightIndent=8,
            backColor=colors.HexColor("#F3F6F7"),
            borderColor=colors.HexColor("#D8E1E4"),
            borderWidth=0.25,
            borderPadding=5,
            spaceBefore=3,
            spaceAfter=8,
        ),
        "footer": ParagraphStyle(
            "NeoFooter",
            parent=base["BodyText"],
            fontName="Helvetica",
            fontSize=8,
            leading=10,
            textColor=colors.HexColor("#66727A"),
        ),
    }


def is_formula(line: str) -> bool:
    markers = ["=", "^", "sqrt", "clamp", "tanh", "exp", "ln", "round", "sum_", "if ", "->", "[", "]"]
    return any(marker in line for marker in markers) and len(line) < 140


def add_lines(story, lines, style):
    buffer = []
    for line in lines:
        if is_formula(line):
            if buffer:
                story.append(Paragraph("<br/>".join(buffer), style["body"]))
                buffer = []
            story.append(Preformatted(line, style["mono"]))
        else:
            buffer.append(line)
    if buffer:
        story.append(Paragraph("<br/>".join(buffer), style["body"]))


def build_markdown(feature: dict) -> str:
    lines = [
        f"# {feature['name']}",
        "",
        feature["summary"],
        "",
        "## Implementation Anchors",
        "",
    ]
    for anchor in feature["anchors"]:
        lines.append(f"- `{anchor}`")
    lines.append("")
    for heading, body_lines in feature["sections"]:
        lines.append(f"## {heading}")
        lines.append("")
        for item in body_lines:
            if is_formula(item):
                lines.append("```text")
                lines.append(item)
                lines.append("```")
            else:
                lines.append(item)
                lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def build_pdf(feature: dict, pdf_path: Path):
    style = styles()
    doc = SimpleDocTemplate(
        str(pdf_path),
        pagesize=A4,
        rightMargin=1.45 * cm,
        leftMargin=1.45 * cm,
        topMargin=1.35 * cm,
        bottomMargin=1.35 * cm,
        title=feature["name"],
        author="NeoProCamX",
        subject="NeoProCamX proprietary technology explanation",
    )
    story = [
        Paragraph(feature["name"], style["title"]),
        Paragraph(feature["summary"], style["subtitle"]),
        Paragraph("Implementation Anchors", style["h2"]),
    ]
    for anchor in feature["anchors"]:
        story.append(Preformatted(anchor, style["mono"]))
    for heading, lines in feature["sections"]:
        story.append(Paragraph(heading, style["h2"]))
        add_lines(story, lines, style)
    story.append(Spacer(1, 12))
    story.append(
        Paragraph(
            "Generated from the local NeoProCamX source tree. Hardware-dependent claims require runtime evidence from the target device.",
            style["footer"],
        )
    )
    doc.build(story)


def build_index():
    style = styles()
    pdf_path = PDF_DIR / "00-technology-index.pdf"
    doc = SimpleDocTemplate(
        str(pdf_path),
        pagesize=A4,
        rightMargin=1.45 * cm,
        leftMargin=1.45 * cm,
        topMargin=1.35 * cm,
        bottomMargin=1.35 * cm,
        title="NeoProCamX Technology Index",
        author="NeoProCamX",
    )
    story = [
        Paragraph("NeoProCamX Technology Index", style["title"]),
        Paragraph(
            "Separate PDFs were generated for each project-owned technology found in the source tree.",
            style["subtitle"],
        ),
    ]
    for idx, feature in enumerate(FEATURES, start=1):
        slug = slugify(feature["name"])
        story.append(Paragraph(f"{idx}. {feature['name']}", style["h2"]))
        story.append(Paragraph(feature["summary"], style["body"]))
        story.append(Preformatted(f"PDF: {idx:02d}-{slug}.pdf", style["mono"]))
    doc.build(story)


def main():
    PDF_DIR.mkdir(parents=True, exist_ok=True)
    MD_DIR.mkdir(parents=True, exist_ok=True)
    for idx, feature in enumerate(FEATURES, start=1):
        slug = slugify(feature["name"])
        base = f"{idx:02d}-{slug}"
        (MD_DIR / f"{base}.md").write_text(build_markdown(feature), encoding="utf-8")
        build_pdf(feature, PDF_DIR / f"{base}.pdf")
    build_index()
    print(f"Generated {len(FEATURES)} feature PDFs plus index in {PDF_DIR}")


if __name__ == "__main__":
    main()
