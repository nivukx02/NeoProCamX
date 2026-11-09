# Natural NDR Rendering Design

## Goal

Improve NDR10+ and NIVUKX Vision rendering so high-luminance skies and sunlight roll off naturally instead of clipping, while shadows remain detailed and color stays neutral. The result should feel like scene-linear RAW dynamic range compressed into display HDR, not like a flat global exposure correction or artificial mobile HDR.

## Scope

This pass changes the project-owned NDR/NIVUKX math and shader contracts only:

- `HdrImagingMath.kt` gains a natural dynamic range mapper used by NDR10+ and NIVUKX Vision.
- `ycbcr_preview.frag` routes NDR/NIVUKX monitor and delivery paths through a local, hue-preserving natural NDR tone mapper before display/encode transforms.
- `hdr_merge.comp` improves exposure weighting, ghost rejection, and highlight reconstruction in the multi-exposure merge shader.
- Unit and shader contract tests pin the behavior.

## Rendering Model

The NDR mapper separates luma from chroma, maps luma with a toe/midtone/shoulder curve, then reconstructs RGB around the mapped luma. Highlights use a tanh shoulder:

`Y_out = K + (W - K) * tanh(k * (Y - K) / (W - K))`

Shadows use a bounded toe lift only below the shadow range. Chroma is compressed radially near high luma and high RGB channel ratios, preserving hue direction and preventing cyan/green highlight drift. NIVUKX Vision uses a slightly lower knee and stronger gamut compression than NDR10+.

## HDR Merge Model

The merge shader still blends short, medium, and long exposure frames per pixel. It improves the weights:

- Deep highlights prefer underexposed frames.
- Shadows prefer longer exposure frames.
- Midtones keep balanced tent/Gaussian weights.
- Samples far from the reference exposure are downweighted to reduce ghosting.
- Clipped highlights get local neighbor reconstruction before tone mapping.

## Non-Goals

This pass does not claim impossible recovery from fully clipped RAW data. It estimates clipped texture from surrounding pixels and shorter exposures when available. It also does not add external vendor profiles or certification claims.
