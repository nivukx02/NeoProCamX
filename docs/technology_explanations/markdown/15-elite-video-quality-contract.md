# Elite Video Quality Contract

Audit and certificate layer for mode, bit depth, color science, bitrate, and runtime evidence.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContract.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicy.kt`
- `app/src/test/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContractTest.kt`

## Purpose

The Elite Video Quality Contract is the project's quality gate.

It maps requests into tiers such as ELITE_PRO_SDR_10BIT, ELITE_HDR_NDR10_PLUS_10BIT, ELITE_LOG_N_LOG_* and ELITE_NPV10_*.

## Runtime Flow

1. A request describes mode, width, height, fps, codec, HDR/log profile, bit depth, payload, and color selections.

2. Policy normalizes HDR and N-Log versions.

3. The certificate records pass/fail, quality tier, ten-bit requirement, bitrate floor, quality value, and failure reasons.

4. Metadata merges manual color selection, enhancement plan, SDR/N-Log/HDR mastering plan, and truth metadata.

## Core Logic

Certificate status:

PASS iff failureReasons is empty and evidence requirements for the selected mode are met.

Ten-bit requirement:

```text
requiresTenBit = mode in {PRO, HDR, LOG, NPV10} or hdrMode != OFF or logProfile active
```
NPV10 native truth condition:

```text
native10 = sourceBitDepth >= 10 && storageBitDepth >= 10 && payloadFormat in {P010_LE, YUV420P10_LE}
```
Enhancement metadata uses non-destructive truth flags:

```text
nativeOutputResolutionFaked = false
```
```text
truthfulMasterPreserved = mode in {NPV10, RAW}
```
## Truth Boundary

The contract is designed to fail unsafe or unproven claims.

It does not create hardware support; it only audits whether the requested claim is supported by evidence.
