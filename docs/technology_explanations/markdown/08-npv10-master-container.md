# NPV10 Master Container

Private NeoProCamX master format for 10-bit P010/YUV and packed RAW payloads.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10ContainerPolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10FileWriter.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10FileReader.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt`

## Purpose

NPV10 is the project-owned private master container for high-bit-depth local acquisition and later MOV conversion.

```text
It preserves explicit payload format metadata so an 8-bit fallback cannot be mislabeled as native 10-bit.
```
## Runtime Flow

1. Npv10RecordingManager creates a private master session.

2. Npv10FileWriter writes header metadata and frame chunks.

```text
3. Payload format identifies P010, planar YUV420P10, expanded 8-bit, RAW12 packed, or RAW14 packed.
```
4. Npv10IntegrityValidator audits the master before publishing or conversion.

## Core Math

Container identity:

```text
MAGIC = 'NPV10'
```
```text
FRAME_MAGIC = 'FRM0'
```
For YUV420 10-bit style payloads, estimated frame payload bytes:

```text
Y_samples = W * H
```
```text
UV_samples = 2 * ceil(W / 2) * ceil(H / 2)
```
```text
bytes = (Y_samples + UV_samples) * 2
```
For packed RAW payloads:

```text
bytes = W * H * 2
```
10-bit P010 sample convention:

```text
word16[15:6] = code10[9:0]
```
```text
word16[5:0] = padding or project-specific residual field depending on payload format
```
## Truth Boundary

NPV10 is private to NeoProCamX. It is not a public standard container.

The project keeps payloadFormat and bit-depth evidence separate to prevent false native-10-bit claims.
