#!/usr/bin/env python3
"""
Cross-check the FFM client against the reference XGBoost binding.

Reproduces the exact dataset and parameters used by the Java smoke test, trains
the same model through the Python package, and diffs the predictions against the
file SelfCheck wrote.

    ./scripts/selfcheck.sh /tmp/java_predictions.txt
    python3 scripts/reference-run.py /tmp/java_predictions.txt

Needs numpy and the xgboost Python package (pip install xgboost).

Both sides must be driving the same libxgboost.so build for the comparison to be
exact; against a different build, expect agreement to a few ULPs rather than
bit-for-bit.
"""
import sys

import numpy as np
import xgboost as xgb

ROWS, COLS, ROUNDS = 400, 8, 20
MASK = (1 << 64) - 1

PARAMS = {
    "objective": "binary:logistic",
    "max_depth": 3,
    "eta": 0.3,
    "tree_method": "hist",
    "nthread": 1,
    "seed": 0,
}


def features():
    """The same 64-bit LCG as Fixtures.Lcg / SelfCheck.features()."""
    state = 42
    out = np.empty(ROWS * COLS, dtype=np.float32)
    for i in range(out.size):
        state = (state * 6364136223846793005 + 1442695040888963407) & MASK
        out[i] = np.float32((state >> 11) * 2.0**-53)
    return out.reshape(ROWS, COLS)


def labels(x):
    return (x[:, 0] + x[:, 1] - x[:, 2] > np.float32(0.5)).astype(np.float32)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        return 2

    x = features()
    y = labels(x)
    dtrain = xgb.DMatrix(x, label=y)
    booster = xgb.train(PARAMS, dtrain, num_boost_round=ROUNDS)
    reference = booster.predict(dtrain)

    java = np.loadtxt(sys.argv[1], dtype=np.float32)

    print(f"xgboost      : {xgb.__version__}")
    print(f"rows         : {len(java)} java / {len(reference)} python")
    if len(java) != len(reference):
        print("FAIL: length mismatch")
        return 1

    diff = np.abs(java - reference)
    identical = int(np.sum(java == reference))
    print(f"label balance: {int(y.sum())} positive of {len(y)}")
    print(f"max abs diff : {diff.max():.3e}")
    print(f"mean abs diff: {diff.mean():.3e}")
    print(f"bit-identical: {identical}/{len(java)}")

    if diff.max() > 1e-6:
        worst = int(np.argmax(diff))
        print(f"FAIL: worst row {worst}: java={java[worst]!r} python={reference[worst]!r}")
        return 1

    print("\nPASS: FFM client matches the reference binding.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
