# IMAX Strict Quality Profile

Current high-end quality profile and canonical uniform checksum policy.

## Implementation Anchors

- `core-math/src/main/kotlin/com/neoproc/neoproccamx/core/HighEndQualityPolicy.kt`
- `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`
- `app/src/test/java/com/neoproc/neoproccamx/core/HighEndQualityPolicyTest.kt`

## Purpose

IMAX_STRICT_10_10 is the current high-end profile name behind SingularityHorizonProfile/ImaxStrictProfile.

```text
It centralizes detail, denoise, bitrate, LUT, AI, export, and attestation constants.
```
## Runtime Flow

```text
1. HighEndQualityPolicy exposes strict mode, full sensor, scaling, and runtime tier switches.
```
2. ImaxStrictProfile aliases SingularityHorizonProfile constants for legacy call sites.

3. canonicalUniformPayload serializes important constants.

4. CANONICAL_UNIFORM_SHA512 hashes the payload to detect CPU/GPU coefficient drift.

## Core Math

Canonical hash:

```text
payload = 'profile=...|ultraDetail=<bits>|adaptiveGain=<bits>|...'
```
```text
checksum = SHA512(UTF8(payload))
```
Quality boost selection:

```text
boost(fps) = 14400 if fps >= 480
```
```text
           = 7200 if fps >= 240
```
```text
           = 3600 if fps >= 120
```
```text
           = 1500 if fps >= 60
```
```text
           = 750 otherwise
```
Bitrate floor by resolution:

```text
floorBps = table(width * height) * 1,000,000
```
Examples currently in source:

```text
4K floor = 820 Mbps, 8K floor = 1600 Mbps, 1080p floor = 340 Mbps
```
## Truth Boundary

These constants are policy targets. Actual encoder bitrate and resolution still depend on device capability.

A checksum verifies coefficient identity, not visual quality by itself.
