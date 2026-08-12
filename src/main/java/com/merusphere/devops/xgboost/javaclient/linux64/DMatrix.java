package com.merusphere.devops.xgboost.javaclient.linux64;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import com.merusphere.devops.xgboost.javaclient.linux64.internal.ArrayInterface;
import com.merusphere.devops.xgboost.javaclient.linux64.internal.Ffm;
import com.merusphere.devops.xgboost.javaclient.linux64.internal.NativeLibrary;
import com.merusphere.devops.xgboost.javaclient.linux64.capi.XgboostH;

/**
 * A training or inference matrix &mdash; the Java side of {@code DMatrixHandle}.
 *
 * <p>Instances hold a native allocation and must be closed:
 * <pre>{@code
 * try (DMatrix train = DMatrix.fromDense(features, nrow, ncol)) {
 *     train.setLabel(labels);
 *     ...
 * }
 * }</pre>
 *
 * <p>Not thread-safe. XGBoost permits concurrent <em>reads</em> of a DMatrix
 * from multiple threads, but the mutators here (label, weight, base margin) are
 * not synchronised; set metadata before sharing the instance.
 */
public final class DMatrix implements AutoCloseable {

    /** The value treated as "no data" when none is given explicitly. */
    public static final float DEFAULT_MISSING = Float.NaN;

    private final MemorySegment handle;
    private boolean closed;

    private DMatrix(MemorySegment handle) {
        this.handle = handle;
    }

    // ------------------------------------------------------------------
    // Construction
    // ------------------------------------------------------------------

    /**
     * Builds a DMatrix from a row-major dense array, using NaN as the missing value.
     *
     * @param data row-major values, {@code nrow * ncol} entries
     */
    public static DMatrix fromDense(float[] data, int nrow, int ncol) {
        return fromDense(data, nrow, ncol, DEFAULT_MISSING, 0);
    }

    /**
     * Builds a DMatrix from a row-major dense array.
     *
     * @param data    row-major values, {@code nrow * ncol} entries
     * @param missing value to treat as missing; use {@link Float#NaN} for the default
     * @param nthread threads to use for ingest; 0 means "all available"
     */
    public static DMatrix fromDense(float[] data, int nrow, int ncol, float missing, int nthread) {
        NativeLibrary.ensureLoaded();
        long expected = (long) nrow * ncol;
        if (data.length != expected) {
            throw new XgbException("Expected " + expected + " values for a "
                    + nrow + "x" + ncol + " matrix but got " + data.length);
        }
        try (Arena arena = Arena.ofConfined()) {
            // Off-heap copy so XGBoost can read it by address. XGDMatrixCreateFromDense
            // copies into the DMatrix's own storage, so this buffer dies with the arena.
            MemorySegment buffer = arena.allocateFrom(ValueLayout.JAVA_FLOAT, data);
            String descriptor = ArrayInterface.of(buffer, ArrayInterface.F32, nrow, ncol);
            String config = "{\"missing\":" + jsonFloat(missing) + ",\"nthread\":" + nthread + "}";

            MemorySegment out = Ffm.outPtr(arena);
            Ffm.check(XgboostH.XGDMatrixCreateFromDense(
                    Ffm.cString(arena, descriptor),
                    Ffm.cString(arena, config),
                    out), "XGDMatrixCreateFromDense");
            return new DMatrix(Ffm.readPtr(out));
        }
    }

    /** Convenience overload for a rectangular {@code float[nrow][ncol]}. */
    public static DMatrix fromDense(float[][] rows) {
        if (rows.length == 0) {
            throw new XgbException("Cannot build a DMatrix from zero rows");
        }
        int nrow = rows.length;
        int ncol = rows[0].length;
        float[] flat = new float[nrow * ncol];
        for (int r = 0; r < nrow; r++) {
            if (rows[r].length != ncol) {
                throw new XgbException("Row " + r + " has " + rows[r].length
                        + " columns, expected " + ncol + " (ragged input)");
            }
            System.arraycopy(rows[r], 0, flat, r * ncol, ncol);
        }
        return fromDense(flat, nrow, ncol);
    }

    /**
     * Builds a DMatrix from a CSR (compressed sparse row) matrix.
     *
     * @param indptr  row offsets, {@code nrow + 1} entries
     * @param indices column index per non-zero
     * @param values  value per non-zero
     * @param ncol    number of columns in the full matrix
     */
    public static DMatrix fromCsr(long[] indptr, int[] indices, float[] values, int ncol) {
        return fromCsr(indptr, indices, values, ncol, DEFAULT_MISSING, 0);
    }

    /** Builds a DMatrix from a CSR matrix with an explicit missing value and thread count. */
    public static DMatrix fromCsr(long[] indptr, int[] indices, float[] values,
                                  int ncol, float missing, int nthread) {
        NativeLibrary.ensureLoaded();
        if (indices.length != values.length) {
            throw new XgbException("indices (" + indices.length + ") and values ("
                    + values.length + ") must have the same length");
        }
        if (indptr.length == 0) {
            throw new XgbException("indptr must have at least one entry");
        }
        long nnz = indptr[indptr.length - 1];
        if (nnz != values.length) {
            throw new XgbException("indptr ends at " + nnz + " but " + values.length
                    + " values were given");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment indptrBuf = arena.allocateFrom(ValueLayout.JAVA_LONG, indptr);
            MemorySegment indicesBuf = arena.allocateFrom(ValueLayout.JAVA_INT, indices);
            MemorySegment valuesBuf = arena.allocateFrom(ValueLayout.JAVA_FLOAT, values);

            String config = "{\"missing\":" + jsonFloat(missing) + ",\"nthread\":" + nthread + "}";
            MemorySegment out = Ffm.outPtr(arena);
            Ffm.check(XgboostH.XGDMatrixCreateFromCSR(
                    Ffm.cString(arena, ArrayInterface.of(indptrBuf, ArrayInterface.U64, indptr.length)),
                    Ffm.cString(arena, ArrayInterface.of(indicesBuf, ArrayInterface.U32, indices.length)),
                    Ffm.cString(arena, ArrayInterface.of(valuesBuf, ArrayInterface.F32, values.length)),
                    ncol,
                    Ffm.cString(arena, config),
                    out), "XGDMatrixCreateFromCSR");
            return new DMatrix(Ffm.readPtr(out));
        }
    }

    /**
     * Loads a DMatrix from a file XGBoost understands (LIBSVM, CSV, binary cache).
     *
     * @param uri path, optionally with XGBoost's {@code ?format=csv&label_column=0} suffix
     */
    public static DMatrix fromUri(String uri) {
        return fromUri(uri, true);
    }

    /** Loads a DMatrix from a file, optionally silencing XGBoost's load messages. */
    public static DMatrix fromUri(String uri, boolean silent) {
        NativeLibrary.ensureLoaded();
        try (Arena arena = Arena.ofConfined()) {
            String config = "{\"uri\":\"" + escape(uri) + "\",\"silent\":" + (silent ? 1 : 0)
                    + ",\"data_split_mode\":0}";
            MemorySegment out = Ffm.outPtr(arena);
            Ffm.check(XgboostH.XGDMatrixCreateFromURI(
                    Ffm.cString(arena, config), out), "XGDMatrixCreateFromURI");
            return new DMatrix(Ffm.readPtr(out));
        }
    }

    // ------------------------------------------------------------------
    // Metadata
    // ------------------------------------------------------------------

    /** Sets the training labels. Length must equal {@link #numRow()}. */
    public void setLabel(float[] label) {
        setFloatInfo("label", label);
    }

    /** Sets per-row weights. */
    public void setWeight(float[] weight) {
        setFloatInfo("weight", weight);
    }

    /** Sets per-row base margin (the raw score prediction starts from). */
    public void setBaseMargin(float[] margin) {
        setFloatInfo("base_margin", margin);
    }

    /** Returns the labels, or an empty array if none were set. */
    public float[] getLabel() {
        return getFloatInfo("label");
    }

    /** Sets a float-valued info field by name. */
    public void setFloatInfo(String field, float[] values) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buffer = arena.allocateFrom(ValueLayout.JAVA_FLOAT, values);
            Ffm.check(XgboostH.XGDMatrixSetFloatInfo(
                    handle, Ffm.cString(arena, field), buffer, values.length),
                    "XGDMatrixSetFloatInfo(" + field + ")");
        }
    }

    /** Reads a float-valued info field by name. */
    public float[] getFloatInfo(String field) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment outLen = Ffm.outULong(arena);
            MemorySegment outPtr = Ffm.outPtr(arena);
            Ffm.check(XgboostH.XGDMatrixGetFloatInfo(
                    handle, Ffm.cString(arena, field), outLen, outPtr),
                    "XGDMatrixGetFloatInfo(" + field + ")");
            long len = Ffm.readULong(outLen);
            // The returned pointer is owned by XGBoost and is invalidated by the
            // next call into the library, so copy before returning.
            return len == 0 ? new float[0] : Ffm.copyFloats(Ffm.readPtr(outPtr), len);
        }
    }

    /** Sets feature names, used when dumping the model. */
    public void setFeatureNames(String[] names) {
        setStrInfo("feature_name", names);
    }

    /** Sets feature types ({@code "q"} numeric, {@code "c"} categorical, {@code "i"} indicator). */
    public void setFeatureTypes(String[] types) {
        setStrInfo("feature_type", types);
    }

    private void setStrInfo(String field, String[] values) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            Ffm.check(XgboostH.XGDMatrixSetStrFeatureInfo(
                    handle, Ffm.cString(arena, field),
                    Ffm.cStringArray(arena, values), values.length),
                    "XGDMatrixSetStrFeatureInfo(" + field + ")");
        }
    }

    /** Number of rows. */
    public long numRow() {
        return scalar(true);
    }

    /** Number of columns (features). */
    public long numCol() {
        return scalar(false);
    }

    /** Number of non-missing entries. */
    public long numNonMissing() {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = Ffm.outULong(arena);
            Ffm.check(XgboostH.XGDMatrixNumNonMissing(handle, out), "XGDMatrixNumNonMissing");
            return Ffm.readULong(out);
        }
    }

    private long scalar(boolean rows) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = Ffm.outULong(arena);
            if (rows) {
                Ffm.check(XgboostH.XGDMatrixNumRow(handle, out), "XGDMatrixNumRow");
            } else {
                Ffm.check(XgboostH.XGDMatrixNumCol(handle, out), "XGDMatrixNumCol");
            }
            return Ffm.readULong(out);
        }
    }

    /** Writes the matrix to XGBoost's binary cache format. */
    public void saveBinary(String path, boolean silent) {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            Ffm.check(XgboostH.XGDMatrixSaveBinary(
                    handle, Ffm.cString(arena, path), silent ? 1 : 0), "XGDMatrixSaveBinary");
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** The underlying {@code DMatrixHandle}. For use by {@link Booster}. */
    MemorySegment handle() {
        checkOpen();
        return handle;
    }

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
        Ffm.check(XgboostH.XGDMatrixFree(handle), "XGDMatrixFree");
    }

    private void checkOpen() {
        if (closed) {
            throw new XgbException("DMatrix has been closed");
        }
    }

    // ------------------------------------------------------------------

    /**
     * XGBoost's JSON reader accepts the bare {@code NaN} and {@code Infinity}
     * tokens that strict JSON forbids, and its config parsing relies on it &mdash;
     * a missing value of NaN is the default everywhere.
     */
    static String jsonFloat(float f) {
        if (Float.isNaN(f)) {
            return "NaN";
        }
        if (Float.isInfinite(f)) {
            return f > 0 ? "Infinity" : "-Infinity";
        }
        return Float.toString(f);
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public String toString() {
        if (closed) {
            return "DMatrix[closed]";
        }
        return "DMatrix[" + numRow() + "x" + numCol() + "]";
    }
}
