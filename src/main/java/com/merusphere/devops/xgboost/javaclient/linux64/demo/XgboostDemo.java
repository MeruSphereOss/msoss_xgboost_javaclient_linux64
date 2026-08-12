package com.merusphere.devops.xgboost.javaclient.linux64.demo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.merusphere.devops.xgboost.javaclient.linux64.Booster;
import com.merusphere.devops.xgboost.javaclient.linux64.DMatrix;
import com.merusphere.devops.xgboost.javaclient.linux64.PredictType;
import com.merusphere.devops.xgboost.javaclient.linux64.Prediction;
import com.merusphere.devops.xgboost.javaclient.linux64.Xgb;

/**
 * The jar's main class: a self-contained train/predict run that needs nothing
 * but {@code libxgboost.so}.
 *
 * <pre>
 *   java --enable-native-access=ALL-UNNAMED -jar xgboost-javaclient-linux64-1.0.0.jar
 *   java --enable-native-access=ALL-UNNAMED -jar ...jar /tmp/model.json
 * </pre>
 *
 * <p>With an argument, the trained model is written to that path and reloaded to
 * confirm the round trip. Use it as the first thing you run on a new host: if
 * this prints a falling logloss, the library, the loader path and the FFM
 * plumbing are all working.
 */
public final class XgboostDemo {

    private static final int ROWS = 400;
    private static final int COLS = 8;
    private static final int ROUNDS = 20;

    private XgboostDemo() {
    }

    public static void main(String[] args) throws Exception {
        Xgb.setVerbosity(0);

        System.out.println("xgboost-javaclient-linux64");
        System.out.println("  library : " + Xgb.libraryPath());
        System.out.println("  version : " + Xgb.version());
        System.out.println();

        float[] x = features();
        float[] y = labels(x);

        try (DMatrix train = DMatrix.fromDense(x, ROWS, COLS)) {
            train.setLabel(y);

            try (Booster booster = Booster.create(train)) {
                booster.setParams(params());

                System.out.println("training " + ROUNDS + " rounds on " + train);
                for (int i = 0; i < ROUNDS; i++) {
                    booster.update(i, train);
                    if (i == 0 || (i + 1) % 5 == 0) {
                        System.out.println("  " + booster.evaluate(i,
                                new DMatrix[] { train }, new String[] { "train" }));
                    }
                }

                Prediction p = booster.predict(train);
                Prediction margin = booster.predict(train, PredictType.MARGIN);
                System.out.println();
                System.out.println("predictions : " + p);
                System.out.println("accuracy    : " + accuracy(p.values(), y));
                System.out.printf("row 0       : p=%.6f  margin=%.6f  label=%.0f%n",
                        p.values()[0], margin.values()[0], y[0]);

                if (args.length > 0) {
                    Path out = Path.of(args[0]);
                    booster.saveModel(out.toString());
                    System.out.println();
                    System.out.println("saved       : " + out.toAbsolutePath()
                            + " (" + Files.size(out) + " bytes)");
                    try (Booster reloaded = Booster.loadModel(out.toString())) {
                        boolean same = java.util.Arrays.equals(
                                p.values(), reloaded.predict(train).values());
                        System.out.println("reloaded    : predictions "
                                + (same ? "identical" : "DIFFER"));
                    }
                }
            }
        }
    }

    /** Deterministic 64-bit LCG, so every run of the demo produces the same numbers. */
    private static float[] features() {
        long state = 42L;
        float[] x = new float[ROWS * COLS];
        for (int i = 0; i < x.length; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            x[i] = (float) ((state >>> 11) * 0x1.0p-53);
        }
        return x;
    }

    private static float[] labels(float[] x) {
        float[] y = new float[ROWS];
        for (int r = 0; r < ROWS; r++) {
            int b = r * COLS;
            y[r] = (x[b] + x[b + 1] - x[b + 2] > 0.5f) ? 1.0f : 0.0f;
        }
        return y;
    }

    private static Map<String, String> params() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("objective", "binary:logistic");
        p.put("max_depth", "3");
        p.put("eta", "0.3");
        p.put("tree_method", "hist");
        p.put("nthread", "1");
        p.put("seed", "0");
        return p;
    }

    private static String accuracy(float[] probabilities, float[] labels) {
        int correct = 0;
        for (int i = 0; i < labels.length; i++) {
            if ((probabilities[i] >= 0.5f ? 1.0f : 0.0f) == labels[i]) {
                correct++;
            }
        }
        return String.format("%.4f (%d/%d)", (double) correct / labels.length, correct, labels.length);
    }
}
