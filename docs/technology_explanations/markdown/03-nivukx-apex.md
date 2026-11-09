# NIVUKX APEX

Scene-adaptive NIVUKX profile family with SHARP, ZERO, and CINEMA modes.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/apex/ApexProfile.kt`
- `app/src/main/java/com/neoproc/neoproccamx/apex/ApexToneMapper.kt`
- `app/src/main/java/com/neoproc/neoproccamx/apex/ApexPipelineOrchestrator.kt`
- `app/src/main/cpp/shaders/vulkan/apex/apex_tonemap.frag`
- `app/src/main/cpp/shaders/vulkan/apex/apex_ictcp.frag`

## Purpose

NIVUKX APEX is the higher-level scene mapper for the NIVUKX family.

It exposes three project-owned modes: SHARP for maximum detail, ZERO for near-linear optical truth, and CINEMA for softer highlight rolloff.

## Runtime Flow

1. SceneAnalyzer produces per-frame scene metrics such as p99 nits, shadow ratio, motion score, and noise risk.

2. ApexProfileParams.compute(metrics, mode) converts those metrics into shader parameters.

3. ApexPipelineState stores the selected mode and current profile parameters with preview/recording parity.

4. The native/Vulkan path consumes those parameters for tone, chroma compression, and detail gain.

## Core Math

Dynamic-range score drs and shadow need sn modulate the profile:

```text
sourcePeak = baseSourcePeak + sourceLift * drs
```
```text
targetPeak = baseTargetPeak + targetLift * drs
```
```text
shadowLift = baseShadowLift + shadowScale * sn
```
For SHARP mode in code:

```text
sourcePeak = 12000 + 4000 * drs
```
```text
targetPeak = 2000 + 400 * drs
```
```text
shoulderStrength = 4.80 + 0.62 * drs
```
```text
shadowLift = 0.0014 + 0.0018 * sn
```
Tone mapping uses a soft shoulder:

```text
L_out = L_knee + (1 - L_knee) * (1 - exp(-s * (L - L_knee) / (1 - L_knee)))
```
Detail gains are mode dependent:

```text
SHARP: gain1=0.78, gain2=0.28, gain3=0.12
```
```text
ZERO: gain1=gain2=gain3=0
```
```text
CINEMA: gain1=0.48, gain2=0.16, gain3=0.08
```
## Truth Boundary

APEX is an internal NeoProCamX profile family. It is not a third-party HDR format.

```text
The code comments state vendorCertifiedHdr10Plus=false and vendorCertifiedDolbyVision=false.
```
