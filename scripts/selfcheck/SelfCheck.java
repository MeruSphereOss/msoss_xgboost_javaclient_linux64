import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.merusphere.devops.xgboost.javaclient.linux64.Booster;
import com.merusphere.devops.xgboost.javaclient.linux64.DMatrix;
import com.merusphere.devops.xgboost.javaclient.linux64.PredictType;
import com.merusphere.devops.xgboost.javaclient.linux64.Prediction;
import com.merusphere.devops.xgboost.javaclient.linux64.Xgb;
import com.merusphere.devops.xgboost.javaclient.linux64.XgbException;

/**
 * Dependency-free smoke test: same coverage as the JUnit suite, but runnable
 * with nothing but a JDK and the compiled client. Useful for checking a
 * libxgboost.so on a machine where you would rather not set up Maven.
 *
 * Run it through the wrapper, which compiles and invokes it for you:
 *
 *   ./scripts/selfcheck.sh
 *   ./scripts/selfcheck.sh /tmp/java_predictions.txt
 *
 * With an argument it writes the predictions to that path, which is what
 * scripts/reference-run.py diffs against the Python binding.
 *
 * Exits non-zero on the first failure.
 */
public final class SelfCheck {

    static final int ROWS = 400;
    static final int COLS = 8;
    static final int ROUNDS = 20;

    static int checks = 0;

    public static void main(String[] args) throws Exception {
        Xgb.setVerbosity(0);

        section("library");
        System.out.println("  loaded from : " + Xgb.libraryPath());
        System.out.println("  version     : " + Xgb.version());
        check("version is well formed", Xgb.version().matches("\\d+\\.\\d+\\.\\d+"));
        check("build info present", Xgb.buildInfo().contains("USE_OPENMP"));

        float[] x = features();
        float[] y = labels(x);

        section("dense ingest");
        try (DMatrix m = DMatrix.fromDense(x, ROWS, COLS)) {
            check("numRow", m.numRow() == ROWS);
            check("numCol", m.numCol() == COLS);
            check("numNonMissing", m.numNonMissing() == (long) ROWS * COLS);
            m.setLabel(y);
            check("label round trip", java.util.Arrays.equals(y, m.getLabel()));
        }

        section("input validation");
        check("mis-sized dense input rejected", throwsXgb(() -> DMatrix.fromDense(new float[10], 3, 4)));
        check("ragged input rejected", throwsXgb(() -> DMatrix.fromDense(new float[][] { { 1, 2 }, { 3 } })));

        section("train + predict");
        float[] expected;
        byte[] serialized;
        Path modelFile = Files.createTempDirectory("xgb").resolve("model.json");

        try (DMatrix train = DMatrix.fromDense(x, ROWS, COLS)) {
            train.setLabel(y);
            try (Booster b = Booster.create(train)) {
                b.setParams(params());
                List<Double> logloss = new ArrayList<>();
                for (int i = 0; i < ROUNDS; i++) {
                    b.update(i, train);
                    logloss.add(metric(b.evaluate(i, new DMatrix[] { train }, new String[] { "train" })));
                }
                System.out.printf("  logloss     : %.5f -> %.5f%n", logloss.get(0), logloss.get(ROUNDS - 1));
                check("boostedRounds", b.boostedRounds() == ROUNDS);
                check("numFeature", b.numFeature() == COLS);
                check("logloss decreased", logloss.get(ROUNDS - 1) < logloss.get(0));

                Prediction p = b.predict(train);
                expected = p.values();
                check("prediction length", p.values().length == ROWS);
                check("shape is [rows]", p.shape().length == 1 && p.shape()[0] == ROWS);
                boolean inRange = true;
                for (float v : p.values()) {
                    inRange &= v >= 0.0f && v <= 1.0f;
                }
                check("probabilities in [0,1]", inRange);
                double acc = accuracy(p.values(), y);
                System.out.printf("  accuracy    : %.4f%n", acc);
                check("fits training data", acc > 0.9);

                section("prediction types");
                Prediction margin = b.predict(train, PredictType.MARGIN);
                Prediction leaf = b.predict(train, PredictType.LEAF);
                Prediction shap = b.predict(train, PredictType.CONTRIBUTION);
                check("margin shape", java.util.Arrays.equals(margin.shape(), new long[] { ROWS }));
                check("leaf shape", java.util.Arrays.equals(leaf.shape(), new long[] { ROWS, ROUNDS }));
                check("shap shape", java.util.Arrays.equals(shap.shape(), new long[] { ROWS, COLS + 1 }));
                check("sigmoid(margin) == normal", close(expected, sigmoid(margin.values()), 1e-5f));
                check("shap rows sum to margin", close(margin.values(), rowSums(shap), 1e-4f));

                section("csr ingest");
                long[] indptr = new long[ROWS + 1];
                int[] indices = new int[ROWS * COLS];
                for (int r = 0; r < ROWS; r++) {
                    indptr[r + 1] = (long) (r + 1) * COLS;
                    for (int c = 0; c < COLS; c++) {
                        indices[r * COLS + c] = c;
                    }
                }
                try (DMatrix csr = DMatrix.fromCsr(indptr, indices, x, COLS)) {
                    check("csr numRow", csr.numRow() == ROWS);
                    check("csr predictions match dense", close(expected, b.predict(csr).values(), 1e-6f));
                }

                section("persistence");
                b.setAttr("trained_by", "xgboost-ffm-client");
                serialized = b.toBytes();
                b.saveModel(modelFile.toString());
                check("model file written", Files.size(modelFile) > 0);
                check("json export non-empty", b.toBytes("json").length > 0);

                section("dump");
                List<String> trees = b.dumpModel("text", true);
                check("one dump entry per tree", trees.size() == ROUNDS);
                check("dump mentions leaves", trees.get(0).contains("leaf="));
            }

            try (Booster fromBytes = Booster.loadModel(serialized);
                 Booster fromFile = Booster.loadModel(modelFile.toString())) {
                check("reload from bytes is bit-identical",
                        java.util.Arrays.equals(expected, fromBytes.predict(train).values()));
                check("reload from file is bit-identical",
                        java.util.Arrays.equals(expected, fromFile.predict(train).values()));
                check("attribute survives round trip",
                        "xgboost-ffm-client".equals(fromBytes.getAttr("trained_by")));
                check("attribute listing", fromBytes.attributes().size() == 1);
            }
        }

        section("error handling");
        DMatrix closed = DMatrix.fromDense(x, ROWS, COLS);
        closed.close();
        closed.close();
        check("close is idempotent", closed.isClosed());
        check("use after close throws", throwsXgb(closed::numRow));

        try (DMatrix m = DMatrix.fromDense(x, ROWS, COLS)) {
            String message = "";
            try {
                m.getFloatInfo("no_such_field");
            } catch (XgbException e) {
                message = e.getMessage();
            }
            System.out.println("  native error: " + firstLine(message));
            check("native error surfaced", message.contains("XGDMatrixGetFloatInfo(no_such_field)"));
            check("native error carries XGBoost's own text", message.contains("Unknown key"));
        }

        // Parameters are validated lazily: XGBoosterSetParam accepts anything
        // and the failure only shows up at the first update. Worth knowing
        // before you go looking for a validation call that does not exist.
        try (DMatrix m = DMatrix.fromDense(x, ROWS, COLS)) {
            m.setLabel(y);
            try (Booster b = Booster.create(m)) {
                b.setParam("objective", "not:a:real:objective");
                check("bad parameter is accepted at set time", true);
                check("bad parameter fails at update time", throwsXgb(() -> b.update(0, m)));
            }
        }

        // Write the predictions so tools/reference-run.py can diff them.
        if (args.length > 0) {
            StringBuilder sb = new StringBuilder();
            for (float v : expected) {
                sb.append(Float.toString(v)).append('\n');
            }
            Files.writeString(Path.of(args[0]), sb.toString());
            System.out.println("\nwrote predictions to " + args[0]);
        }

        System.out.println("\nAll " + checks + " checks passed.");
    }

    // ------------------------------------------------------------------

    static void section(String name) {
        System.out.println("\n[" + name + "]");
    }

    static void check(String what, boolean ok) {
        checks++;
        System.out.println("  " + (ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) {
            System.out.println("\nFAILED: " + what);
            System.exit(1);
        }
    }

    static boolean throwsXgb(Runnable r) {
        try {
            r.run();
            return false;
        } catch (XgbException e) {
            return true;
        }
    }

    static float[] features() {
        long state = 42L;
        float[] x = new float[ROWS * COLS];
        for (int i = 0; i < x.length; i++) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            x[i] = (float) ((state >>> 11) * 0x1.0p-53);
        }
        return x;
    }

    static float[] labels(float[] x) {
        float[] y = new float[ROWS];
        for (int r = 0; r < ROWS; r++) {
            int b = r * COLS;
            y[r] = (x[b] + x[b + 1] - x[b + 2] > 0.5f) ? 1.0f : 0.0f;
        }
        return y;
    }

    static Map<String, String> params() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("objective", "binary:logistic");
        p.put("max_depth", "3");
        p.put("eta", "0.3");
        p.put("tree_method", "hist");
        p.put("nthread", "1");
        p.put("seed", "0");
        return p;
    }

    static double metric(String line) {
        return Double.parseDouble(line.substring(line.lastIndexOf(':') + 1).trim());
    }

    static double accuracy(float[] p, float[] y) {
        int ok = 0;
        for (int i = 0; i < y.length; i++) {
            if ((p[i] >= 0.5f ? 1.0f : 0.0f) == y[i]) {
                ok++;
            }
        }
        return (double) ok / y.length;
    }

    static float[] sigmoid(float[] m) {
        float[] out = new float[m.length];
        for (int i = 0; i < m.length; i++) {
            out[i] = (float) (1.0 / (1.0 + Math.exp(-m[i])));
        }
        return out;
    }

    static float[] rowSums(Prediction shap) {
        int cols = shap.columns();
        float[] out = new float[(int) shap.rows()];
        for (int r = 0; r < out.length; r++) {
            double s = 0;
            for (int c = 0; c < cols; c++) {
                s += shap.values()[r * cols + c];
            }
            out[r] = (float) s;
        }
        return out;
    }

    static boolean close(float[] a, float[] b, float tol) {
        if (a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > tol) {
                System.out.println("    mismatch at " + i + ": " + a[i] + " vs " + b[i]);
                return false;
            }
        }
        return true;
    }

    static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl) + " ...";
    }
}
