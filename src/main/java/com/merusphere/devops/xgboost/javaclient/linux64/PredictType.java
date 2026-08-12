package com.merusphere.devops.xgboost.javaclient.linux64;

/**
 * What {@link Booster#predict} should return.
 *
 * <p>The codes are the {@code "type"} values XGBoost's prediction config
 * expects. They come from the {@code XGBoosterPredictFromDMatrix} documentation
 * in {@code c_api.h} and are <em>not</em> the same as the {@code option_mask}
 * bits of the older {@code XGBoosterPredict} entry point &mdash; notably leaf
 * prediction is 6 here and 2 there. Do not infer them from the declaration
 * order.
 */
public enum PredictType {

    /** Transformed output: probabilities for classification, values for regression. */
    NORMAL(0),
    /** Untransformed margin score (log-odds for logistic objectives). */
    MARGIN(1),
    /** Exact SHAP contributions. Shape is {@code [nrow, nfeature + 1]}, bias last. */
    CONTRIBUTION(2),
    /** Approximate SHAP contributions &mdash; cheaper, less exact. */
    APPROX_CONTRIBUTION(3),
    /** Exact SHAP interaction values. Shape is {@code [nrow, nfeature + 1, nfeature + 1]}. */
    INTERACTION(4),
    /** Approximate SHAP interaction values. */
    APPROX_INTERACTION(5),
    /** Leaf index reached in each tree. Shape is {@code [nrow, ntree]}. */
    LEAF(6);

    private final int code;

    PredictType(int code) {
        this.code = code;
    }

    /** The value XGBoost's prediction config expects. */
    public int code() {
        return code;
    }
}
