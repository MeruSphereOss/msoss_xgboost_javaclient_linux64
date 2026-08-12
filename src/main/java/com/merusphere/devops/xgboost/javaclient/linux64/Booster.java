package com.merusphere.devops.xgboost.javaclient.linux64;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.merusphere.devops.xgboost.javaclient.linux64.internal.Ffm;
import com.merusphere.devops.xgboost.javaclient.linux64.internal.NativeLibrary;
import com.merusphere.devops.xgboost.javaclient.linux64.capi.XgboostH;

/**
 * A gradient boosting model &mdash; the Java side of {@code BoosterHandle}.
 *
 * <p>Typical use:
 * <pre>{@code
 * try (DMatrix train = DMatrix.fromDense(x, nrow, ncol);
 *      Booster booster = Booster.create(train)) {
 *     train.setLabel(y);
 *     booster.setParam("objective", "binary:logistic");
 *     booster.setParam("max_depth", "4");
 *     for (int i = 0; i < 50; i++) {
 *         booster.update(i, train);
 *     }
 *     Prediction p = booster.predict(train);
 * }
 * }</pre>
 *
 * <p>Not thread-safe. XGBoost parallelises internally; a single booster should
 * be driven from one thread at a time. For concurrent inference, either
 * synchronise or give each thread its own booster loaded from the same bytes.
 */
public final class Booster implements AutoCloseable {

    private final MemorySegment handle;
    private boolean closed;

    private Booster(MemorySegment handle) {
        this.handle = handle;
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /**
     * Creates a booster whose cached matrices are the ones given.
     *
     * <p>XGBoost keeps a prediction cache per matrix passed here, which is what
     * makes repeated {@code update} calls on the training matrix fast. Pass the
     * training matrix and any evaluation matrices you intend to score each round.
     */
    public static Booster create(DMatrix... cache) {
        NativeLibrary.ensureLoaded();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment handles = arena.allocate(ValueLayout.ADDRESS, Math.max(cache.length, 1));
            for (int i = 0; i < cache.length; i++) {
                handles.setAtIndex(ValueLayout.ADDRESS, i, cache[i].handle());
            }
            MemorySegment out = Ffm.outPtr(arena);
            Ffm.check(XgboostH.XGBoosterCreate(handles, cache.length, out), "XGBoosterCreate");
            return new Booster(Ffm.readPtr(out));
        }
    }

    /** Loads a model previously written by {@link #saveModel(String)}. */
    public static Booster loadModel(String path) {
        Booster booster = create();
        try (Arena arena = Arena.ofConfined()) {
            Ffm.check(XgboostH.XGBoosterLoadModel(
                    booster.handle, Ffm.cString(arena, path)), "XGBoosterLoadModel");
        } catch (RuntimeException e) {
            booster.close();
            throw e;
        }
        return booster;
    }

    /** Loads a model from the bytes produced by {@link #toBytes()}. */
    public static Booster loadModel(byte[] model) {
        Booster booster = create();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocateFrom(ValueLayout.JAVA_BYTE, model);
            Ffm.check(XgboostH.XGBoosterLoadModelFromBuffer(
                    booster.handle, buffer, model.length), "XGBoosterLoadModelFromBuffer");
        } catch (RuntimeException e) {
            booster.close();
            throw e;
        }
        return booster;
    }

    // ------------------------------------------------------------------
    // Parameters
    // ------------------------------------------------------------------

    /**
     * Sets one training parameter, e.g. {@code setParam("max_depth", "6")}.
     *
     * <p>XGBoost does not validate parameters here &mdash; an unknown objective
     * or an unparseable value is accepted silently and only fails at the first
     * {@link #update}. That is the library's behaviour, not a gap in this
     * wrapper.
     */
    public Booster setParam(String name, String value) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            Ffm.check(XgboostH.XGBoosterSetParam(
                    handle, Ffm.cString(arena, name), Ffm.cString(arena, value)),
                    "XGBoosterSetParam(" + name + ")");
        }
        return this;
    }

    /** Sets several parameters at once. */
    public Booster setParams(Map<String, String> params) {
        params.forEach(this::setParam);
        return this;
    }

    // ------------------------------------------------------------------
    // Training
    // ------------------------------------------------------------------

    /**
     * Runs one boosting round against {@code dtrain}.
     *
     * @param iteration zero-based round number; XGBoost uses it for callbacks
     *                  and for learning-rate schedules
     */
    public void update(int iteration, DMatrix dtrain) {
        checkOpen();
        Ffm.check(XgboostH.XGBoosterUpdateOneIter(handle, iteration, dtrain.handle()),
                "XGBoosterUpdateOneIter");
    }

    /**
     * Trains for {@code rounds} boosting rounds, starting from the current state.
     *
     * @return this booster
     */
    public Booster train(DMatrix dtrain, int rounds) {
        int start = boostedRounds();
        for (int i = 0; i < rounds; i++) {
            update(start + i, dtrain);
        }
        return this;
    }

    /**
     * Evaluates the model on the given matrices.
     *
     * @param names one label per matrix, used in the returned string
     * @return XGBoost's evaluation line, e.g. {@code "[10]\ttrain-logloss:0.31\ttest-logloss:0.35"}
     */
    public String evaluate(int iteration, DMatrix[] matrices, String[] names) {
        checkOpen();
        if (matrices.length != names.length) {
            throw new XgbException("Got " + matrices.length + " matrices but "
                    + names.length + " names");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment handles = arena.allocate(ValueLayout.ADDRESS, Math.max(matrices.length, 1));
            for (int i = 0; i < matrices.length; i++) {
                handles.setAtIndex(ValueLayout.ADDRESS, i, matrices[i].handle());
            }
            MemorySegment out = Ffm.outPtr(arena);
            Ffm.check(XgboostH.XGBoosterEvalOneIter(
                    handle, iteration, handles, Ffm.cStringArray(arena, names),
                    matrices.length, out), "XGBoosterEvalOneIter");
            return Ffm.readString(Ffm.readPtr(out));
        }
    }

    /** Number of boosting rounds completed so far. */
    public int boostedRounds() {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = arena.allocate(ValueLayout.JAVA_INT);
            Ffm.check(XgboostH.XGBoosterBoostedRounds(handle, out), "XGBoosterBoostedRounds");
            return out.get(ValueLayout.JAVA_INT, 0);
        }
    }

    /** Number of features the model expects. */
    public long numFeature() {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = Ffm.outULong(arena);
            Ffm.check(XgboostH.XGBoosterGetNumFeature(handle, out), "XGBoosterGetNumFeature");
            return Ffm.readULong(out);
        }
    }

    /** Discards the learned model, keeping the parameters. */
    public void reset() {
        checkOpen();
        Ffm.check(XgboostH.XGBoosterReset(handle), "XGBoosterReset");
    }

    // ------------------------------------------------------------------
    // Prediction
    // ------------------------------------------------------------------

    /** Predicts with the full model and default (transformed) output. */
    public Prediction predict(DMatrix data) {
        return predict(data, PredictType.NORMAL, 0, 0, false);
    }

    /** Predicts with the full model and the given output type. */
    public Prediction predict(DMatrix data, PredictType type) {
        return predict(data, type, 0, 0, false);
    }

    /**
     * Predicts over a range of boosting rounds.
     *
     * @param iterationBegin first round to include
     * @param iterationEnd   one past the last round; 0 means "to the end"
     * @param strictShape    when true, the returned shape always carries the
     *                       trailing dimensions explicitly rather than
     *                       collapsing them for the single-output case
     */
    public Prediction predict(DMatrix data, PredictType type,
                              int iterationBegin, int iterationEnd, boolean strictShape) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            String config = "{\"type\":" + type.code()
                    + ",\"training\":false"
                    + ",\"iteration_begin\":" + iterationBegin
                    + ",\"iteration_end\":" + iterationEnd
                    + ",\"strict_shape\":" + strictShape + "}";

            MemorySegment outShape = Ffm.outPtr(arena);
            MemorySegment outDim = Ffm.outULong(arena);
            MemorySegment outResult = Ffm.outPtr(arena);

            Ffm.check(XgboostH.XGBoosterPredictFromDMatrix(
                    handle, data.handle(), Ffm.cString(arena, config),
                    outShape, outDim, outResult), "XGBoosterPredictFromDMatrix");

            long dims = Ffm.readULong(outDim);
            long[] shape = Ffm.copyULongs(Ffm.readPtr(outShape), dims);
            long total = 1;
            for (long d : shape) {
                total *= d;
            }
            // Both buffers belong to the booster's internal prediction cache and
            // are invalidated by the next call into XGBoost -- copy them now.
            float[] values = Ffm.copyFloats(Ffm.readPtr(outResult), total);
            return new Prediction(shape, values);
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Writes the model to a file. The extension selects the format:
     * {@code .json}, {@code .ubj} (UBJSON, the compact default) or the legacy
     * binary format for anything else.
     */
    public void saveModel(String path) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            Ffm.check(XgboostH.XGBoosterSaveModel(handle, Ffm.cString(arena, path)),
                    "XGBoosterSaveModel");
        }
    }

    /** Serialises the model to UBJSON bytes. */
    public byte[] toBytes() {
        return toBytes("ubj");
    }

    /**
     * Serialises the model.
     *
     * @param format {@code "ubj"} or {@code "json"}
     */
    public byte[] toBytes(String format) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            String config = "{\"format\":\"" + format + "\"}";
            MemorySegment outLen = Ffm.outULong(arena);
            MemorySegment outPtr = Ffm.outPtr(arena);
            Ffm.check(XgboostH.XGBoosterSaveModelToBuffer(
                    handle, Ffm.cString(arena, config), outLen, outPtr),
                    "XGBoosterSaveModelToBuffer");
            return Ffm.copyBytes(Ffm.readPtr(outPtr), Ffm.readULong(outLen));
        }
    }

    /** The model's full configuration as a JSON string. */
    public String configJson() {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outLen = Ffm.outULong(arena);
            MemorySegment outStr = Ffm.outPtr(arena);
            Ffm.check(XgboostH.XGBoosterSaveJsonConfig(handle, outLen, outStr),
                    "XGBoosterSaveJsonConfig");
            return Ffm.readString(Ffm.readPtr(outStr));
        }
    }

    /**
     * Dumps the trees in a human-readable form.
     *
     * @param format    {@code "text"}, {@code "json"} or {@code "dot"}
     * @param withStats include split gain and cover statistics
     * @return one entry per tree
     */
    public List<String> dumpModel(String format, boolean withStats) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outLen = Ffm.outULong(arena);
            MemorySegment outArray = Ffm.outPtr(arena);
            Ffm.check(XgboostH.XGBoosterDumpModelEx(
                    handle, Ffm.cString(arena, ""), withStats ? 1 : 0,
                    Ffm.cString(arena, format), outLen, outArray), "XGBoosterDumpModelEx");

            long len = Ffm.readULong(outLen);
            MemorySegment array = Ffm.readPtr(outArray)
                    .reinterpret(len * ValueLayout.ADDRESS.byteSize());
            List<String> trees = new ArrayList<>((int) len);
            for (long i = 0; i < len; i++) {
                trees.add(Ffm.readString(array.getAtIndex(ValueLayout.ADDRESS, i)));
            }
            return trees;
        }
    }

    // ------------------------------------------------------------------
    // Attributes
    // ------------------------------------------------------------------

    /** Sets a user attribute stored alongside the model. Pass {@code null} to remove it. */
    public void setAttr(String key, String value) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment v = value == null ? MemorySegment.NULL : Ffm.cString(arena, value);
            Ffm.check(XgboostH.XGBoosterSetAttr(handle, Ffm.cString(arena, key), v),
                    "XGBoosterSetAttr(" + key + ")");
        }
    }

    /** Reads a user attribute, or {@code null} if it is not set. */
    public String getAttr(String key) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = Ffm.outPtr(arena);
            MemorySegment success = arena.allocate(ValueLayout.JAVA_INT);
            Ffm.check(XgboostH.XGBoosterGetAttr(
                    handle, Ffm.cString(arena, key), out, success),
                    "XGBoosterGetAttr(" + key + ")");
            return success.get(ValueLayout.JAVA_INT, 0) == 0 ? null : Ffm.readString(Ffm.readPtr(out));
        }
    }

    /** All user attributes currently set on the model. */
    public Map<String, String> attributes() {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outLen = Ffm.outULong(arena);
            MemorySegment outArray = Ffm.outPtr(arena);
            Ffm.check(XgboostH.XGBoosterGetAttrNames(handle, outLen, outArray),
                    "XGBoosterGetAttrNames");
            long len = Ffm.readULong(outLen);
            Map<String, String> result = new LinkedHashMap<>();
            if (len == 0) {
                return result;
            }
            MemorySegment array = Ffm.readPtr(outArray)
                    .reinterpret(len * ValueLayout.ADDRESS.byteSize());
            List<String> keys = new ArrayList<>((int) len);
            for (long i = 0; i < len; i++) {
                keys.add(Ffm.readString(array.getAtIndex(ValueLayout.ADDRESS, i)));
            }
            // Read the names out first: getAttr calls back into XGBoost and
            // invalidates the name array we are iterating.
            for (String key : keys) {
                result.put(key, getAttr(key));
            }
            return result;
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Whether {@link #close()} has been called. */
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Ffm.check(XgboostH.XGBoosterFree(handle), "XGBoosterFree");
    }

    private void checkOpen() {
        if (closed) {
            throw new XgbException("Booster has been closed");
        }
    }

    @Override
    public String toString() {
        return closed ? "Booster[closed]"
                : "Booster[rounds=" + boostedRounds() + ", features=" + numFeature() + "]";
    }
}
