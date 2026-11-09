#!/usr/bin/env python3
"""
NDR10+/NIVUKX LIVE-path reproduction harness (host-side, no device).

Goal (user's idea): apply the *real* NDR10+/NIVUKX settings to a controlled test
image on a PC, reproduce the warm/skin "red" problem in measurable numbers, then
tune a fix and see it work — before touching the phone.

This is an EXACT port of the shipping live tone operator
`core-math/.../color/NdrToneOperator.kt` (signalShape + signalToDisplayNits +
mapRgbHuePreserving), plus a faithful model of the convert-path P010 decode
(`RecordDevelopP010ColorIngress.kt`: NCL BT.2020 YCbCr, 4:2:0, nearest up-sample,
pre-EOTF per-channel clamp) and ST.2084 PQ (HdrImagingMath).

It runs three inputs so the numbers localize the red:
  A  clean warm skin           -> develop only          (is the OPERATOR guilty?)
  B  clean warm skin           -> P010 round-trip+develop(is the DECODE guilty?)
  C  camera-baked red skin     -> develop, with/without a candidate warm-red fix
                                                         (can we POST-CORRECT it?)
Neutral grey patches ride along in every case: a correct pipeline must leave them
untouched (Cb=Cr=0), so any warm-selective shift shows up as skin-vs-grey drift.
"""
from __future__ import annotations
import numpy as np
from PIL import Image
from pathlib import Path
import json

OUT = Path(r"D:/NeoProCamX_Final01/NeoProCamX/_diag/repro")
OUT.mkdir(parents=True, exist_ok=True)
EPS = 1e-8

# ---- BT.2020 luma (matches NdrToneOperator.LUMA_*) ----
LR, LG, LB = 0.2627, 0.6780, 0.0593
def luma(rgb): return LR*rgb[...,0] + LG*rgb[...,1] + LB*rgb[...,2]

def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / max(e1 - e0, EPS), 0.0, 1.0)
    return t*t*(3.0 - 2.0*t)

# ---- ST.2084 PQ (SMPTE / H.Sup15) ----
m1 = 2610/16384; m2 = 2523/4096*128
c1 = 3424/4096;  c2 = 2413/4096*32; c3 = 2392/4096*32
def pq_oetf(L):            # linear[0,1] (1.0=10000nits) -> code[0,1]
    L = np.clip(L, 0, 1); Lm = np.power(L, m1)
    return np.power((c1 + c2*Lm)/(1 + c3*Lm), m2)
def pq_eotf(V):            # code[0,1] -> linear[0,1]
    V = np.clip(V, 0, 1); Vm = np.power(V, 1/m2)
    return np.power(np.maximum(Vm - c1, 0.0)/(c2 - c3*Vm), 1/m1)

# ---- exact NdrToneOperator port ----
class Params:
    def __init__(s, gamma, toeW, toeA, peak, desat):
        s.gamma, s.toeW, s.toeA, s.peak, s.desat = gamma, toeW, toeA, peak, desat
    @property
    def refSlope(s): return s.gamma - s.toeA
NDR    = Params(0.92, 0.20, 0.018, 5000.0, 0.20)
NIVUKX = Params(0.88, 0.24, 0.026, 10000.0, 0.26)
DIFFUSE = 203.0

def signal_shape(x, p):
    x = np.asarray(x, float)
    lift = np.power(np.maximum(x, 0), p.gamma) + smoothstep(0.0, p.toeW, x)*p.toeA*(1.0 - x)
    cont = 1.0 + p.refSlope*(x - 1.0)
    out = np.where(x <= 1.0, lift, cont)
    return np.where(x <= 0.0, 0.0, out)

def map_rgb_hue_preserving(rgb, p, desat_onset=0.55, desat_cap=None, warm_fix=0.0):
    """Exact live op. desat_onset/desat_cap expose the two live knobs; warm_fix
    adds the CANDIDATE warm-red taming stage (0 = byte-identical to live)."""
    cap = p.desat if desat_cap is None else desat_cap
    sr = np.maximum(rgb[...,0], 0); sg = np.maximum(rgb[...,1], 0); sb = np.maximum(rgb[...,2], 0)
    rgb = np.stack([sr, sg, sb], -1)
    source_rgb = rgb.copy()
    y = np.maximum(luma(rgb), EPS)
    mappedY = signal_shape(y, p)
    out = rgb * (mappedY/y)[...,None]
    # live radial highlight desat (highlights only)
    desat = np.clip(cap*smoothstep(desat_onset, 1.0, mappedY), 0, cap)
    out = mappedY[...,None] + (out - mappedY[...,None])*(1 - desat)[...,None]
    # Shipping fix: ratio-gated red-overflow reconstruction with exact luma restoration.
    if warm_fix > 0.0:
        oy = np.maximum(luma(out), EPS)
        rg_ratio = source_rgb[...,0] / np.maximum(source_rgb[...,1], EPS)
        bg_ratio = source_rgb[...,2] / np.maximum(source_rgb[...,1], EPS)
        moderate_red = smoothstep(1.18, 1.55, rg_ratio) * (1.0 - smoothstep(2.80, 4.00, rg_ratio))
        blue_present = smoothstep(0.28, 0.55, bg_ratio) * (1.0 - smoothstep(1.08, 1.35, bg_ratio))
        exposure_window = smoothstep(0.04, 0.16, mappedY) * (1.0 - smoothstep(6.5, 8.0, mappedY))
        red_overflow = smoothstep(1.30, 2.10, rg_ratio)
        w = np.clip(
            warm_fix * moderate_red * blue_present * exposure_window * (0.45 + 0.55 * red_overflow),
            0,
            warm_fix,
        )
        corrected = out.copy()
        corrected[...,0] += (np.minimum(corrected[...,0], corrected[...,1] * 1.42) - corrected[...,0]) * w
        corrected[...,2] += (np.maximum(corrected[...,2], corrected[...,1] * 0.64) - corrected[...,2]) * (w * 0.68)
        corrected_y = np.maximum(luma(corrected), EPS)
        out = corrected * (oy / corrected_y)[...,None]
    return np.maximum(out, 0)

# ---- convert-path P010 decode model (RecordDevelopP010ColorIngress) ----
def p010_roundtrip(scene_linear):
    """scene-linear (1.0=diffuse white) -> PQ R'G'B' -> NCL YCbCr -> 4:2:0 box down ->
    nearest up -> R'G'B' -> per-channel clamp[0,1] -> PQ EOTF -> scene-linear."""
    nits = scene_linear * DIFFUSE
    rp = pq_oetf(nits/10000.0)                                   # non-linear R'G'B' codes
    Y = LR*rp[...,0] + LG*rp[...,1] + LB*rp[...,2]
    Cb = (rp[...,2] - Y)/1.8814
    Cr = (rp[...,0] - Y)/1.4746
    # 4:2:0 box down then NEAREST up (the ingress defect)
    def box_nearest(c):
        h, w = c.shape
        d = (c[0:h:2,0:w:2] + c[1:h:2,0:w:2] + c[0:h:2,1:w:2] + c[1:h:2,1:w:2]) / 4.0
        return np.repeat(np.repeat(d, 2, 0), 2, 1)[:h,:w]
    Cb2, Cr2 = box_nearest(Cb), box_nearest(Cr)
    r = Y + 1.4746*Cr2
    g = Y - 0.16455*Cb2 - 0.57135*Cr2
    b = Y + 1.8814*Cb2
    rgb_prime = np.clip(np.stack([r, g, b], -1), 0.0, 1.0)        # the pre-EOTF clamp
    lin = pq_eotf(rgb_prime) * 10000.0 / DIFFUSE                  # back to scene-linear
    return lin

# ---- display (view HDR signal as SDR sRGB just for eyeballing/measuring) ----
def to_srgb(sig):
    x = np.clip(sig, 0, 1)
    return np.where(x <= 0.0031308, x*12.92, 1.055*np.power(x, 1/2.4) - 0.055)

def from_srgb(sig):
    x = np.clip(sig, 0, 1)
    return np.where(x <= 0.04045, x/12.92, np.power((x + 0.055)/1.055, 2.4))

BT709_TO_2020 = np.array([
    [0.627404, 0.329283, 0.043313],
    [0.069097, 0.919540, 0.011362],
    [0.016392, 0.088013, 0.895595],
])
BT2020_TO_709 = np.array([
    [ 1.660491, -0.587641, -0.072850],
    [-0.124550,  1.132900, -0.008349],
    [-0.018151, -0.100579,  1.118730],
])

def ndr_monitor_srgb(tone_linear_2020, p):
    """Exact SDR-monitor branch used by Hdr10PqPreviewCompositor."""
    t = np.maximum(tone_linear_2020, 0.0)
    yl = np.maximum(luma(t), EPS)
    knee = 0.75
    hdr_span = max(p.peak / DIFFUSE - knee, 0.25)
    ysdr = np.where(
        yl <= knee,
        yl,
        knee + (1.0-knee)*(1.0-np.exp(-4.0*(yl-knee)/hdr_span)),
    )
    rgb2020 = t * (ysdr/yl)[...,None]
    rgb709 = rgb2020 @ BT2020_TO_709.T
    y709 = np.maximum(rgb709 @ np.array([0.2126, 0.7152, 0.0722]), 0.0)
    lo = rgb709.min(-1)
    low_scale = np.clip(y709/np.maximum(y709-lo, EPS), 0.0, 1.0)
    rgb709 = np.where((lo < 0)[...,None], y709[...,None] + (rgb709-y709[...,None])*low_scale[...,None], rgb709)
    y709 = np.clip(rgb709 @ np.array([0.2126, 0.7152, 0.0722]), 0.0, 1.0)
    hi = rgb709.max(-1)
    high_scale = np.clip((1.0-y709)/np.maximum(hi-y709, EPS), 0.0, 1.0)
    rgb709 = np.where((hi > 1)[...,None], y709[...,None] + (rgb709-y709[...,None])*high_scale[...,None], rgb709)
    return to_srgb(np.clip(rgb709, 0.0, 1.0))

def sat(rgb):  # display saturation proxy
    mx = rgb.max(-1); mn = rgb.min(-1)
    return (mx - mn)/np.maximum(mx, EPS)

# ---- test image: labelled patches (scene-linear, 1.0 = diffuse white) ----
def patch(color, n=48): return np.tile(np.asarray(color, float), (n, n, 1))
PATCHES = {
    "grey_mid":       [0.20, 0.20, 0.20],
    "grey_bright":    [0.80, 0.80, 0.80],
    "skin_natural":   [0.62, 0.42, 0.32],   # believable warm skin (R>G>B, moderate)
    "skin_bright":    [0.95, 0.66, 0.52],    # bright but in-range warm skin
    "skin_bakedred":  [1.35, 0.60, 0.42],    # simulated CAMERA-baked red (R pushed hot/clipping)
    "sat_red":        [1.20, 0.10, 0.08],
    "sat_orange":     [1.25, 0.55, 0.10],
}

def measure(name, before, after):
    b = before.reshape(-1,3).mean(0); a = after.reshape(-1,3).mean(0)
    return f"{name:14s} in(sRGB)={np.round(to_srgb(b),3)} out={np.round(to_srgb(a),3)} " \
           f"sat {sat(to_srgb(b)):.2f}->{sat(to_srgb(a)):.2f}  R-G in {b[0]-b[1]:+.2f} out {a[0]-a[1]:+.2f}"

def run_mode(p, label):
    print(f"\n===== {label} =====")
    grid = {}
    for name, col in PATCHES.items():
        src = patch(col)
        A = map_rgb_hue_preserving(src, p)                        # develop only
        B = map_rgb_hue_preserving(p010_roundtrip(src), p)        # decode + develop
        Cfix = map_rgb_hue_preserving(src, p, warm_fix=0.50)  # develop + shipping red-overflow fix
        grid[name] = (src, A, B, Cfix)
    print("-- A: develop only (is the tone OPERATOR guilty?) --")
    for n,(s,A,_,_) in grid.items(): print("  "+measure(n, s, A))
    print("-- B: P010 decode + develop (is the DECODE guilty?) --")
    for n,(s,_,B,_) in grid.items(): print("  "+measure(n, s, B))
    print("-- C: develop + SHIPPING red-overflow fix (does reconstruction work?) --")
    for n,(s,_,_,C) in grid.items(): print("  "+measure(n, s, C))
    # write a side-by-side strip: rows=patches, cols=[in, A, B, C-fix]
    rows = []
    for n,(s,A,B,C) in grid.items():
        rows.append(np.concatenate([to_srgb(s), to_srgb(A), to_srgb(B), to_srgb(C)], axis=1))
    img = np.concatenate(rows, axis=0)
    Image.fromarray((np.clip(img,0,1)*255).astype(np.uint8)).save(OUT/f"repro_{label}.png")
    print(f"  wrote {OUT}/repro_{label}.png  (cols: INPUT | A develop | B decode+dev | C dev+FIX)")

def run_packaged_calibration_target():
    """Apply the shipping NDR profiles to the packaged deterministic RAW-like linear reference."""
    target = Path(__file__).resolve().parents[2] / "app/src/main/assets/calibration/ndr_nivukx_calibration_target.png"
    reference_path = target.with_name("ndr_nivukx_calibration_reference.json")
    source_image = Image.open(target).convert("RGB")
    source_image.thumbnail((1280, 720), Image.Resampling.LANCZOS)
    source_srgb = np.asarray(source_image, dtype=np.float64) / 255.0
    source_linear_709 = from_srgb(source_srgb)
    source_linear_2020 = np.maximum(source_linear_709 @ BT709_TO_2020.T, 0.0)

    rendered = {}
    for label, params in (("NDR10", NDR), ("NIVUKX", NIVUKX)):
        no_fix = ndr_monitor_srgb(map_rgb_hue_preserving(source_linear_2020, params, warm_fix=0.0), params)
        fixed = ndr_monitor_srgb(map_rgb_hue_preserving(source_linear_2020, params, warm_fix=0.50), params)
        rendered[label] = fixed
        reference = json.loads(reference_path.read_text(encoding="utf-8"))
        print(f"-- {label} packaged target correction deltas --")
        for role in ("NEUTRAL", "SKIN", "HIGHLIGHT", "SATURATED_RED", "SATURATED_ORANGE"):
            values = []
            for patch_meta in (p for p in reference["patches"] if p["role"] == role):
                x0, y0, x1, y1 = patch_meta["sample_bounds_normalized"]
                h, w = fixed.shape[:2]
                a = no_fix[int(y0*h):int(y1*h), int(x0*w):int(x1*w)].reshape(-1,3).mean(0)
                b = fixed[int(y0*h):int(y1*h), int(x0*w):int(x1*w)].reshape(-1,3).mean(0)
                values.append(b-a)
            delta = np.mean(values, axis=0)
            print(f"  {role:16s} fix dRGB={np.round(delta, 5)}")

    side_by_side = np.concatenate([source_srgb, rendered["NDR10"], rendered["NIVUKX"]], axis=1)
    output = OUT / "packaged_target_source_NDR10_NIVUKX.png"
    Image.fromarray((np.clip(side_by_side, 0, 1)*255).astype(np.uint8)).save(output)
    print(f"  wrote {output}  (cols: SOURCE | NDR10+ | NIVUKX Vision)")

run_mode(NDR, "NDR10")
run_mode(NIVUKX, "NIVUKX")
run_packaged_calibration_target()
print("\nDONE.")
