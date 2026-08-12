package com.merusphere.devops.xgboost.javaclient.linux64;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrainPredictTest {

    @BeforeAll
    static void quiet() {
        Xgb.setVerbosity(0);
    }

    @Test
    @DisplayName("library loads and reports a version matching the vendored header")
    void libraryLoads() {
        String version = Xgb.version();
        assertAll(
                () -> assertNotNull(Xgb.libraryPath(), "library path should be recorded"),
                () -> assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"), "unexpected version: " + version),
                () -> assertTrue(Xgb.buildInfo().contains("USE_OPENMP"),
                        "build info should describe the compiled features"));
    }

    @Test
    @DisplayName("dense ingest reports the shape it was given")
    void denseShape() {
        float[] x = Fixtures.features();
        try (DMatrix m = DMatrix.fromDense(x, Fixtures.ROWS, Fixtures.COLS)) {
            assertEquals(Fixtures.ROWS, m.numRow());
            assertEquals(Fixtures.COLS, m.numCol());
            assertEquals((long) Fixtures.ROWS * Fixtures.COLS, m.numNonMissing());
        }
    }

    @Test
    @DisplayName("labels survive the round trip through native memory")
    void labelRoundTrip() {
        float[] x = Fixtures.features();
        float[] y = Fixtures.labels(x);
        try (DMatrix m = DMatrix.fromDense(x, Fixtures.ROWS, Fixtures.COLS)) {
            m.setLabel(y);
            assertArrayEquals(y, m.getLabel(), 0.0f);
        }
    }

    @Test
    @DisplayName("ragged and mis-sized input is rejected before reaching the C API")
    void inputValidation() {
        assertAll(
                () -> assertThrows(XgbException.class,
                        () -> DMatrix.fromDense(new float[10], 3, 4)),
                () -> assertThrows(XgbException.class,
                        () -> DMatrix.fromDense(new float[][] { { 1, 2 }, { 3 } })));
    }

    @Test
    @DisplayName("training reduces logloss and produces a usable model")
    void trainAndPredict() {
        float[] x = Fixtures.features();
        float[] y = Fixtures.labels(x);

        try (DMatrix train = DMatrix.fromDense(x, Fixtures.ROWS, Fixtures.COLS)) {
            train.setLabel(y);
            try (Booster booster = Booster.create(train)) {
                booster.setParams(Fixtures.params());

                List<Double> logloss = new ArrayList<>();
                for (int i = 0; i < Fixtures.ROUNDS; i++) {
                    booster.update(i, train);
                    logloss.add(parseMetric(booster.evaluate(i,
                            new DMatrix[] { train }, new String[] { "train" })));
                }

                assertEquals(Fixtures.ROUNDS, booster.boostedRounds());
                assertEquals(Fixtures.COLS, booster.numFeature());
                assertTrue(logloss.get(logloss.size() - 1) < logloss.get(0),
                        "logloss should fall: " + logloss.get(0) + " -> " + logloss.get(logloss.size() - 1));

                Prediction p = booster.predict(train);
                assertEquals(Fixtures.ROWS, p.values().length);
                assertEquals(Fixtures.ROWS, p.rows());
                for (float v : p.values()) {
                    assertTrue(v >= 0.0f && v <= 1.0f, "probability out of range: " + v);
                }

                double accuracy = accuracy(p.values(), y);
                assertTrue(accuracy > 0.9, "expected the model to fit its own training data, got " + accuracy);
            }
        }
    }

    @Test
    @DisplayName("CSR ingest gives the same predictions as dense ingest")
    void csrMatchesDense() {
        float[] x = Fixtures.features();
        float[] y = Fixtures.labels(x);

        float[] densePredictions;
        try (DMatrix train = DMatrix.fromDense(x, Fixtures.ROWS, Fixtures.COLS)) {
            train.setLabel(y);
            try (Booster b = Booster.create(train)) {
                b.setParams(Fixtures.params()).train(train, Fixtures.ROUNDS);
                densePredictions = b.predict(train).values();

                // Same data expressed as CSR: every cell is present, so the
                // sparse and dense paths must agree exactly.
                long[] indptr = new long[Fixtures.ROWS + 1];
                int[] indices = new int[Fixtures.ROWS * Fixtures.COLS];
                for (int r = 0; r < Fixtures.ROWS; r++) {
                    indptr[r + 1] = (long) (r + 1) * Fixtures.COLS;
                    for (int c = 0; c < Fixtures.COLS; c++) {
                        indices[r * Fixtures.COLS + c] = c;
                    }
                }
                try (DMatrix csr = DMatrix.fromCsr(indptr, indices, x, Fixtures.COLS)) {
                    assertEquals(Fixtures.ROWS, csr.numRow());
                    assertEquals(Fixtures.COLS, csr.numCol());
                    assertArrayEquals(densePredictions, b.predict(csr).values(), 1e-6f);
                }
            }
        }
    }

    @Test
    @DisplayName("model survives a save/load round trip, in memory and on disk")
    void persistence(@TempDir Path dir) throws IOException {
        float[] x = Fixtures.features();
        float[] y = Fixtures.labels(x);

        byte[] serialized;
        float[] expected;
        Path file = dir.resolve("model.json");

        try (DMatrix train = DMatrix.fromDense(x, Fixtures.ROWS, Fixtures.COLS)) {
            train.setLabel(y);
            try (Booster b = Booster.create(train)) {
                b.setParams(Fixtures.params()).train(train, Fixtures.ROUNDS);
                b.setAttr("trained_by", "xgboost-ffm-client");
                expected = b.predict(train).values();
                serialized = b.toBytes();
                b.saveModel(file.toString());
            }

            assertTrue(Files.size(file) > 0, "model file should not be empty");

            try (Booster fromBytes = Booster.loadModel(serialized);
                 Booster fromFile = Booster.loadModel(file.toString())) {
                assertArrayEquals(expected, fromBytes.predict(train).values(), 0.0f);
                assertArrayEquals(expected, fromFile.predict(train).values(), 0.0f);
                assertEquals("xgboost-ffm-client", fromBytes.getAttr("trained_by"));
                assertEquals(Map.of("trained_by", "xgboost-ffm-client"), fromBytes.attributes());
            }
        }
    }

    @Test
    @DisplayName("margin, leaf and SHAP outputs have the documented shapes")
    void predictionTypes() {
        float[] x = Fixtures.features();
        float[] y = Fixtures.labels(x);

        try (DMatrix train = DMatrix.fromDense(x, Fixtures.ROWS, Fixtures.COLS)) {
            train.setLabel(y);
            try (Booster b = Booster.create(train)) {
                b.setParams(Fixtures.params()).train(train, Fixtures.ROUNDS);

                Prediction normal = b.predict(train, PredictType.NORMAL);
                Prediction margin = b.predict(train, PredictType.MARGIN);
                Prediction leaf = b.predict(train, PredictType.LEAF);
                Prediction shap = b.predict(train, PredictType.CONTRIBUTION);

                assertAll(
                        () -> assertArrayEquals(new long[] { Fixtures.ROWS }, normal.shape()),
                        () -> assertArrayEquals(new long[] { Fixtures.ROWS }, margin.shape()),
                        () -> assertArrayEquals(new long[] { Fixtures.ROWS, Fixtures.ROUNDS }, leaf.shape()),
                        () -> assertArrayEquals(new long[] { Fixtures.ROWS, Fixtures.COLS + 1L }, shap.shape()),
                        // sigmoid(margin) must reproduce the transformed output
                        () -> assertArrayEquals(normal.values(), sigmoid(margin.values()), 1e-5f),
                        // SHAP contributions plus the bias term reconstruct the margin
                        () -> assertArrayEquals(margin.values(), rowSums(shap), 1e-4f));
            }
        }
    }

    @Test
    @DisplayName("using a closed handle fails cleanly instead of crashing the JVM")
    void useAfterClose() {
        DMatrix m = DMatrix.fromDense(Fixtures.features(), Fixtures.ROWS, Fixtures.COLS);
        m.close();
        m.close(); // idempotent
        assertTrue(m.isClosed());
        assertThrows(XgbException.class, m::numRow);
    }

    @Test
    @DisplayName("a failing native call surfaces XGBoost's own error message")
    void nativeErrorsPropagate() {
        try (DMatrix m = DMatrix.fromDense(Fixtures.features(), Fixtures.ROWS, Fixtures.COLS)) {
            XgbException e = assertThrows(XgbException.class, () -> m.getFloatInfo("no_such_field"));
            assertAll(
                    () -> assertEquals("XGDMatrixGetFloatInfo(no_such_field)", e.operation()),
                    () -> assertTrue(e.getMessage().contains("Unknown key"), e.getMessage()));
        }
    }

    @Test
    @DisplayName("parameters are validated lazily, at the first update rather than at set time")
    void parameterValidationIsDeferred() {
        // XGBoosterSetParam accepts anything; the objective is only resolved
        // when training starts. Documented here so the behaviour is not
        // mistaken for a bug in the wrapper.
        float[] x = Fixtures.features();
        try (DMatrix m = DMatrix.fromDense(x, Fixtures.ROWS, Fixtures.COLS)) {
            m.setLabel(Fixtures.labels(x));
            try (Booster b = Booster.create(m)) {
                b.setParam("objective", "not:a:real:objective");
                XgbException e = assertThrows(XgbException.class, () -> b.update(0, m));
                assertTrue(e.getMessage().contains("Unknown objective function"), e.getMessage());
            }
        }
    }

    @Test
    @DisplayName("model dumps one entry per tree")
    void dumpModel() {
        float[] x = Fixtures.features();
        try (DMatrix train = DMatrix.fromDense(x, Fixtures.ROWS, Fixtures.COLS)) {
            train.setLabel(Fixtures.labels(x));
            try (Booster b = Booster.create(train)) {
                b.setParams(Fixtures.params()).train(train, Fixtures.ROUNDS);
                List<String> trees = b.dumpModel("text", true);
                assertEquals(Fixtures.ROUNDS, trees.size());
                assertTrue(trees.get(0).contains("leaf="), trees.get(0));
            }
        }
    }

    // ------------------------------------------------------------------

    private static double parseMetric(String evalLine) {
        int colon = evalLine.lastIndexOf(':');
        return Double.parseDouble(evalLine.substring(colon + 1).trim());
    }

    private static double accuracy(float[] probabilities, float[] labels) {
        int correct = 0;
        for (int i = 0; i < labels.length; i++) {
            if ((probabilities[i] >= 0.5f ? 1.0f : 0.0f) == labels[i]) {
                correct++;
            }
        }
        return (double) correct / labels.length;
    }

    private static float[] sigmoid(float[] margins) {
        float[] out = new float[margins.length];
        for (int i = 0; i < margins.length; i++) {
            out[i] = (float) (1.0 / (1.0 + Math.exp(-margins[i])));
        }
        return out;
    }

    private static float[] rowSums(Prediction shap) {
        int cols = shap.columns();
        float[] out = new float[(int) shap.rows()];
        for (int r = 0; r < out.length; r++) {
            double sum = 0;
            for (int c = 0; c < cols; c++) {
                sum += shap.values()[r * cols + c];
            }
            out[r] = (float) sum;
        }
        return out;
    }
}
