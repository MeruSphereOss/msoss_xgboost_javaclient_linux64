package com.merusphere.devops.xgboost.javaclient.linux64;

/**
 * A deterministic dataset shared by the tests.
 *
 * <p>The generator is a plain 64-bit LCG rather than {@link java.util.Random} so
 * that {@code tools/reference-run.py} can reproduce byte-identical features in
 * Python. That is what makes the cross-check against the reference XGBoost
 * binding meaningful: same inputs, same parameters, so the predictions have to
 * match.
 */
final class Fixtures {

    static final int ROWS = 400;
    static final int COLS = 8;
    static final int ROUNDS = 20;

    private Fixtures() {
    }

    /** Row-major {@code ROWS x COLS} feature matrix. */
    static float[] features() {
        Lcg rng = new Lcg(42L);
        float[] x = new float[ROWS * COLS];
        for (int i = 0; i < x.length; i++) {
            x[i] = (float) rng.nextDouble();
        }
        return x;
    }

    /** Labels derived from the features by a fixed rule, so the model can actually learn. */
    static float[] labels(float[] x) {
        float[] y = new float[ROWS];
        for (int r = 0; r < ROWS; r++) {
            int base = r * COLS;
            y[r] = (x[base] + x[base + 1] - x[base + 2] > 0.5f) ? 1.0f : 0.0f;
        }
        return y;
    }

    /** Training parameters, fixed for reproducibility (single-threaded, seeded). */
    static java.util.Map<String, String> params() {
        return new java.util.LinkedHashMap<>(java.util.Map.of(
                "objective", "binary:logistic",
                "max_depth", "3",
                "eta", "0.3",
                "tree_method", "hist",
                "nthread", "1",
                "seed", "0"));
    }

    /** The same LCG as {@code tools/reference-run.py}. */
    static final class Lcg {
        private long state;

        Lcg(long seed) {
            this.state = seed;
        }

        /** A double in {@code [0, 1)}. */
        double nextDouble() {
            state = state * 6364136223846793005L + 1442695040888963407L;
            return (state >>> 11) * 0x1.0p-53;
        }
    }
}
