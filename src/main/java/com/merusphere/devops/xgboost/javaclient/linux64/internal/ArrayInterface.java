package com.merusphere.devops.xgboost.javaclient.linux64.internal;

import java.lang.foreign.MemorySegment;

/**
 * Builds the {@code __array_interface__} descriptors XGBoost uses for zero-copy ingest.
 *
 * <p>This is the part of the C API that makes the FFM approach pay off. Instead
 * of handing XGBoost a Java array to copy through JNI, we describe an off-heap
 * buffer to it: the descriptor is a small JSON document whose {@code data} field
 * is the literal address of the buffer. XGBoost reads straight out of memory we
 * allocated.
 *
 * <p>Shape of the descriptor (NumPy's array interface, version 3):
 * <pre>{@code
 * {"data": [140234881232896, true], "shape": [1000, 20], "typestr": "<f4", "version": 3}
 * }</pre>
 * The boolean is the read-only flag; XGBoost never writes through these pointers.
 *
 * <p>The caller owns the lifetime of the described memory. It must stay alive
 * for the duration of the native call &mdash; for {@code XGDMatrixCreateFrom*}
 * that is enough, because the DMatrix copies the data into its own storage
 * before returning.
 */
public final class ArrayInterface {

    /** Little-endian float32, the dtype XGBoost prefers for feature values. */
    public static final String F32 = "<f4";
    /** Little-endian float64. */
    public static final String F64 = "<f8";
    /** Little-endian uint32, used for CSR column indices. */
    public static final String U32 = "<u4";
    /** Little-endian uint64, used for CSR row pointers. */
    public static final String U64 = "<u8";
    /** Little-endian int32. */
    public static final String I32 = "<i4";

    private ArrayInterface() {
    }

    /** Descriptor for a 1-D buffer. */
    public static String of(MemorySegment buffer, String typestr, long length) {
        return of(buffer, typestr, new long[] { length });
    }

    /** Descriptor for a 2-D row-major buffer. */
    public static String of(MemorySegment buffer, String typestr, long rows, long cols) {
        return of(buffer, typestr, new long[] { rows, cols });
    }

    /** Descriptor for an arbitrarily shaped, C-contiguous buffer. */
    public static String of(MemorySegment buffer, String typestr, long[] shape) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"data\":[").append(buffer.address()).append(",true]")
          .append(",\"shape\":[");
        for (int i = 0; i < shape.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(shape[i]);
        }
        sb.append("],\"typestr\":\"").append(typestr).append('"')
          .append(",\"version\":3}");
        return sb.toString();
    }
}
