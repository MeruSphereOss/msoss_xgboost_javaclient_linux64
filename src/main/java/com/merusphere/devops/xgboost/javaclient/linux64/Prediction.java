package com.merusphere.devops.xgboost.javaclient.linux64;

/**
 * The result of {@link Booster#predict}.
 *
 * <p>{@code values} is the flattened, row-major result; {@code shape} describes
 * how to read it. For plain regression or binary classification the shape is
 * {@code [nrow]}; for multi-class it is {@code [nrow, nclass]}; for SHAP
 * contributions it is {@code [nrow, nfeature + 1]}.
 *
 * @param shape  dimensions of the result
 * @param values row-major values, {@code product(shape)} entries
 */
public record Prediction(long[] shape, float[] values) {

    /** Number of rows predicted. */
    public long rows() {
        return shape.length == 0 ? 0 : shape[0];
    }

    /** Number of values per row (1 for scalar predictions). */
    public int columns() {
        if (shape.length < 2) {
            return 1;
        }
        long cols = 1;
        for (int i = 1; i < shape.length; i++) {
            cols *= shape[i];
        }
        return Math.toIntExact(cols);
    }

    /** The values for a single row. */
    public float[] row(int index) {
        int cols = columns();
        float[] out = new float[cols];
        System.arraycopy(values, index * cols, out, 0, cols);
        return out;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Prediction[shape=(");
        for (int i = 0; i < shape.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(shape[i]);
        }
        return sb.append("), ").append(values.length).append(" values]").toString();
    }
}
