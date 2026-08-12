package com.merusphere.devops.xgboost.javaclient.linux64.internal;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import com.merusphere.devops.xgboost.javaclient.linux64.XgbException;
import com.merusphere.devops.xgboost.javaclient.linux64.capi.XgboostH;

/**
 * Bridging helpers between the generated bindings and ordinary Java values.
 *
 * <p>Everything here is about the three things every XGBoost call needs: turn a
 * non-zero return code into an exception, read a {@code char*} the library owns,
 * and allocate out-parameters.
 */
public final class Ffm {

    /** Layout of {@code bst_ulong} (uint64_t). */
    public static final ValueLayout.OfLong BST_ULONG = ValueLayout.JAVA_LONG;

    private Ffm() {
    }

    /**
     * Throws {@link XgbException} if {@code rc} is non-zero.
     *
     * <p>The error string must be read immediately: XGBoost stores it per-thread
     * and overwrites it on the next failing call.
     */
    public static void check(int rc, String operation) {
        if (rc != 0) {
            throw new XgbException(operation, lastError());
        }
    }

    /** The message from {@code XGBGetLastError()}. */
    public static String lastError() {
        // c_api.h declares this with an empty parameter list, so jextract models
        // it as an unprototyped (variadic-capable) function with an invoker.
        MemorySegment msg = XgboostH.XGBGetLastError.makeInvoker().apply();
        return readString(msg);
    }

    /**
     * Reads a NUL-terminated string from a pointer the native side owns.
     *
     * <p>Pointers returned across the FFM boundary are zero-length by default;
     * they have to be reinterpreted before they can be dereferenced. Callers are
     * asserting the pointer is valid and NUL-terminated, which is exactly the
     * contract of every {@code char const**} out-parameter in c_api.h.
     */
    public static String readString(MemorySegment ptr) {
        if (ptr == null || ptr.equals(MemorySegment.NULL)) {
            return "";
        }
        return ptr.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** Allocates a NUL-terminated C string. */
    public static MemorySegment cString(Arena arena, String s) {
        return arena.allocateFrom(s);
    }

    /** Allocates an array of {@code char*} pointing at the given strings. */
    public static MemorySegment cStringArray(Arena arena, String... values) {
        MemorySegment array = arena.allocate(ValueLayout.ADDRESS, values.length);
        for (int i = 0; i < values.length; i++) {
            array.setAtIndex(ValueLayout.ADDRESS, i, arena.allocateFrom(values[i]));
        }
        return array;
    }

    /** Allocates a single pointer-sized out-parameter. */
    public static MemorySegment outPtr(Arena arena) {
        return arena.allocate(ValueLayout.ADDRESS);
    }

    /** Allocates a single {@code bst_ulong} out-parameter. */
    public static MemorySegment outULong(Arena arena) {
        return arena.allocate(BST_ULONG);
    }

    /** Reads a pointer written into an out-parameter. */
    public static MemorySegment readPtr(MemorySegment outPtr) {
        return outPtr.get(ValueLayout.ADDRESS, 0);
    }

    /** Reads a {@code bst_ulong} written into an out-parameter. */
    public static long readULong(MemorySegment out) {
        return out.get(BST_ULONG, 0);
    }

    /** Copies {@code count} floats out of a native buffer the library owns. */
    public static float[] copyFloats(MemorySegment ptr, long count) {
        if (count < 0 || count > Integer.MAX_VALUE) {
            throw new XgbException("Result of " + count + " floats does not fit in a Java array");
        }
        MemorySegment view = ptr.reinterpret(count * Float.BYTES);
        return view.toArray(ValueLayout.JAVA_FLOAT);
    }

    /** Copies {@code count} {@code bst_ulong} values out of a native buffer. */
    public static long[] copyULongs(MemorySegment ptr, long count) {
        MemorySegment view = ptr.reinterpret(count * Long.BYTES);
        return view.toArray(BST_ULONG);
    }

    /** Copies {@code count} bytes out of a native buffer the library owns. */
    public static byte[] copyBytes(MemorySegment ptr, long count) {
        if (count < 0 || count > Integer.MAX_VALUE) {
            throw new XgbException("Buffer of " + count + " bytes does not fit in a Java array");
        }
        return ptr.reinterpret(count).toArray(ValueLayout.JAVA_BYTE);
    }
}
