package com.merusphere.devops.xgboost.javaclient.linux64;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;

import com.merusphere.devops.xgboost.javaclient.linux64.internal.Ffm;
import com.merusphere.devops.xgboost.javaclient.linux64.internal.NativeLibrary;
import com.merusphere.devops.xgboost.javaclient.linux64.capi.XgboostH;

/**
 * Library-level entry points: version, build info, and global configuration.
 *
 * <p>{@link DMatrix} and {@link Booster} load the native library on demand, so
 * calling anything here first is optional. It is useful as an early check that
 * {@code libxgboost.so} is where you think it is.
 */
public final class Xgb {

    /** The XGBoost release the vendored {@code c_api.h} was taken from. */
    public static final String HEADER_VERSION = "3.2.0";

    private Xgb() {
    }

    /** Loads {@code libxgboost.so} now rather than on first use. */
    public static void initialize() {
        NativeLibrary.ensureLoaded();
    }

    /** Where {@code libxgboost.so} was loaded from, or {@code null} if not yet loaded. */
    public static String libraryPath() {
        return NativeLibrary.loadedFrom();
    }

    /** The runtime library version, e.g. {@code "3.2.0"}. */
    public static String version() {
        NativeLibrary.ensureLoaded();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment major = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment minor = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment patch = arena.allocate(ValueLayout.JAVA_INT);
            XgboostH.XGBoostVersion(major, minor, patch);
            return major.get(ValueLayout.JAVA_INT, 0) + "."
                    + minor.get(ValueLayout.JAVA_INT, 0) + "."
                    + patch.get(ValueLayout.JAVA_INT, 0);
        }
    }

    /**
     * How the loaded library was compiled, as JSON: compiler, CUDA support,
     * OpenMP, and the exact version.
     */
    public static String buildInfo() {
        NativeLibrary.ensureLoaded();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = Ffm.outPtr(arena);
            Ffm.check(XgboostH.XGBuildInfo(out), "XGBuildInfo");
            return Ffm.readString(Ffm.readPtr(out));
        }
    }

    /**
     * Sets global configuration as JSON, e.g.
     * {@code {"verbosity": 1, "use_rmm": false}}.
     *
     * <p>Verbosity 0 silences XGBoost's stdout logging, which is usually what
     * you want in a server process.
     */
    public static void setGlobalConfig(String json) {
        NativeLibrary.ensureLoaded();
        try (Arena arena = Arena.ofConfined()) {
            Ffm.check(XgboostH.XGBSetGlobalConfig(Ffm.cString(arena, json)), "XGBSetGlobalConfig");
        }
    }

    /** The current global configuration as JSON. */
    public static String globalConfig() {
        NativeLibrary.ensureLoaded();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment out = Ffm.outPtr(arena);
            Ffm.check(XgboostH.XGBGetGlobalConfig(out), "XGBGetGlobalConfig");
            return Ffm.readString(Ffm.readPtr(out));
        }
    }

    /** Sets XGBoost's log level: 0 silent, 1 warning, 2 info, 3 debug. */
    public static void setVerbosity(int level) {
        setGlobalConfig("{\"verbosity\":" + level + "}");
    }
}
