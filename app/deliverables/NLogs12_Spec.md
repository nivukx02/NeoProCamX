# NLogs-12 Reference Specification

NLogs-12 is an editorial and VFX round-trip curve for NIVUKX Vision. Consumer
delivery returns to the project transport path after grading.

Working space: ACES AP1 scene-linear.

Peak luminance:

```c
static const double NLOGS12_L_PEAK = 10000.0;
```

Constants:

```c
static const double NLOGS12_A = 250.0;
static const double NLOGS12_B = 0.0078125;
static const double NLOGS12_C = log10(NLOGS12_B);
static const double NLOGS12_D = log10(NLOGS12_A + NLOGS12_B) - NLOGS12_C;
```

Forward reference:

```c
#include <math.h>

double nlogs12_encode(double luminance_nits) {
    double l = fmin(fmax(luminance_nits / NLOGS12_L_PEAK, 0.0), 1.0);
    double code = (log10(NLOGS12_A * l + NLOGS12_B) - NLOGS12_C) / NLOGS12_D;
    return fmin(fmax(code, 0.0), 1.0);
}
```

Inverse reference:

```c
#include <math.h>

double nlogs12_decode(double code) {
    double x = fmin(fmax(code, 0.0), 1.0);
    double l = (pow(10.0, x * NLOGS12_D + NLOGS12_C) - NLOGS12_B) / NLOGS12_A;
    return fmin(fmax(l, 0.0), 1.0) * NLOGS12_L_PEAK;
}
```

Round-trip report:

The unit test `NivukxVisionMathTest.nlogs12ForwardInverseRoundTripIsStable`
validates black, mid, highlight, and peak samples with tolerance `1e-7` nits.
The QA harness stores color-difference measurements in acceptance test A14.
