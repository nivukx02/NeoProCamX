# AI Semantic Segmentation Matting

MediaPipe-powered segmentation and fallback matting path for subject-aware processing.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/ai/SegmentationEngine.kt`
- `app/src/main/java/com/neoproc/neoproccamx/ai/SegmentationData.kt`
- `app/src/main/java/com/neoproc/neoproccamx/ai/AICameraPipeline.kt`

## Purpose

```text
The segmentation system produces foreground, skin, hair, face, clothes, and person masks used by subject-aware effects.
```
It combines MediaPipe multiclass segmentation with temporal smoothing and a skin-chroma fallback when model loading is unavailable.

## Runtime Flow

1. AICameraPipeline supplies image frames and luma guide information.

2. SegmentationEngine runs the model or fallback path.

3. Masks are refined and temporally stabilized.

4. Downstream effects use separate semantic confidence channels for bokeh, skin, hair, clothing, and person isolation.

## Core Math

Guided filter local linear model:

```text
q_i = a_k I_i + b_k for i in window omega_k
```
```text
a_k = (mean(Ip) - mean(I) mean(p)) / (var(I) + epsilon)
```
```text
b_k = mean(p) - a_k mean(I)
```
```text
q_i = mean(a)_i I_i + mean(b)_i
```
Temporal motion-adaptive blend:

```text
alpha_motion = alpha_base * exp(-beta * ||v||^2)
```
```text
M_final = (1 - alpha_motion) M_current + alpha_motion M_prev_warped
```
Skin fallback Mahalanobis distance in CbCr:

```text
D = sqrt((x - mu)^T Sigma^-1 (x - mu))
```
```text
S_skin = 1 / (1 + exp(gamma * (D - tau)))
```
## Truth Boundary

Semantic quality depends on actual model asset availability, input resolution, and runtime acceleration.

Fallback skin chroma is a continuity path, not a replacement for true semantic segmentation.
