package com.recsys.features;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class VectorMathTest {

    @Test
    void innerProduct_alignedLength_matchesNaive() {
        // length divisible by 4 — exercises the fully-unrolled path
        float[] a = {1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f};
        float[] b = {8f, 7f, 6f, 5f, 4f, 3f, 2f, 1f};
        double expected = 1*8 + 2*7 + 3*6 + 4*5 + 5*4 + 6*3 + 7*2 + 8*1; // 120
        assertThat(VectorMath.innerProduct(a, b)).isCloseTo(expected, within(1e-9));
    }

    @Test
    void innerProduct_remainder_matchesNaive() {
        // length not divisible by 4 — exercises the scalar tail
        float[] a = {1f, 2f, 3f, 4f, 5f};
        float[] b = {5f, 4f, 3f, 2f, 1f};
        double expected = 1*5 + 2*4 + 3*3 + 4*2 + 5*1; // 35
        assertThat(VectorMath.innerProduct(a, b)).isCloseTo(expected, within(1e-9));
    }

    @Test
    void innerProduct_singleElement() {
        assertThat(VectorMath.innerProduct(new float[]{3f}, new float[]{4f})).isCloseTo(12.0, within(1e-9));
    }

    @Test
    void innerProduct_nullA_returnsNegativeInfinity() {
        assertThat(VectorMath.innerProduct(null, new float[]{1f})).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void innerProduct_nullB_returnsNegativeInfinity() {
        assertThat(VectorMath.innerProduct(new float[]{1f}, null)).isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void innerProduct_lengthMismatch_returnsNegativeInfinity() {
        assertThat(VectorMath.innerProduct(new float[]{1f, 2f}, new float[]{1f}))
                .isEqualTo(Double.NEGATIVE_INFINITY);
    }

    @Test
    void innerProduct_zeroVector_returnsZero() {
        float[] zeros = {0f, 0f, 0f, 0f};
        float[] ones  = {1f, 1f, 1f, 1f};
        assertThat(VectorMath.innerProduct(zeros, ones)).isCloseTo(0.0, within(1e-9));
    }

    @Test
    void innerProduct_largeVector_matchesNaive() {
        int dim = 128;
        float[] a = new float[dim];
        float[] b = new float[dim];
        double expected = 0;
        for (int i = 0; i < dim; i++) {
            a[i] = i + 1;
            b[i] = dim - i;
            expected += (double) a[i] * b[i];
        }
        assertThat(VectorMath.innerProduct(a, b)).isCloseTo(expected, within(1e-6));
    }
}
