package com.merusphere.devops.xgboost.javaclient.linux64.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.merusphere.devops.xgboost.javaclient.linux64.XgbException;

/**
 * Locates and loads {@code libxgboost.so}.
 *
 * <p>The jextract-generated {@code XgboostH} resolves symbols through
 * {@link java.lang.foreign.SymbolLookup#loaderLookup()}, which only sees
 * libraries loaded by <em>this</em> class loader. Every public entry point in
 * the wrapper therefore calls {@link #ensureLoaded()} first. Loading is
 * idempotent and this class shares a class loader with the generated bindings,
 * so the lookup succeeds by the time any downcall handle is resolved.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>system property {@code xgboost.library.path} &mdash; a directory or a
 *       full path to the shared object</li>
 *   <li>environment variable {@code XGBOOST_LIBRARY_PATH} &mdash; same</li>
 *   <li>{@code System.loadLibrary("xgboost")}, i.e. {@code java.library.path},
 *       {@code LD_LIBRARY_PATH} and the ldconfig cache</li>
 * </ol>
 *
 * <p>No native payload is bundled in the jar. XGBoost links against libgomp and
 * the C++ runtime, so the shared object is a deployment dependency, installed
 * the same way any other native library would be.
 */
public final class NativeLibrary {

    public static final String LIBRARY_NAME = "xgboost";
    public static final String SO_NAME = System.mapLibraryName(LIBRARY_NAME); // libxgboost.so

    private static final String PROPERTY = "xgboost.library.path";
    private static final String ENV_VAR = "XGBOOST_LIBRARY_PATH";

    private static volatile boolean loaded;
    private static volatile String loadedFrom;

    private NativeLibrary() {
    }

    /** Loads the native library once. Safe to call from any thread, any number of times. */
    public static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (NativeLibrary.class) {
            if (loaded) {
                return;
            }
            Path explicit = explicitPath();
            try {
                if (explicit != null) {
                    System.load(explicit.toString());
                    loadedFrom = explicit.toString();
                } else {
                    System.loadLibrary(LIBRARY_NAME);
                    loadedFrom = "java.library.path:" + SO_NAME;
                }
            } catch (UnsatisfiedLinkError e) {
                throw new XgbException(diagnostic(explicit), e);
            }
            loaded = true;
        }
    }

    /** Where the library was loaded from, or {@code null} if it has not been loaded yet. */
    public static String loadedFrom() {
        return loadedFrom;
    }

    private static Path explicitPath() {
        for (String raw : List.of(orEmpty(System.getProperty(PROPERTY)), orEmpty(System.getenv(ENV_VAR)))) {
            if (raw.isBlank()) {
                continue;
            }
            Path p = Path.of(raw.trim());
            if (Files.isDirectory(p)) {
                p = p.resolve(SO_NAME);
            }
            if (Files.isRegularFile(p)) {
                return p.toAbsolutePath();
            }
            throw new XgbException("No " + SO_NAME + " at " + p.toAbsolutePath()
                    + " (from " + PROPERTY + " / " + ENV_VAR + ")");
        }
        return null;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String diagnostic(Path attempted) {
        StringBuilder sb = new StringBuilder("Could not load ").append(SO_NAME).append(". ");
        if (attempted != null) {
            sb.append("Tried ").append(attempted).append(". ");
        } else {
            sb.append("Searched java.library.path=")
              .append(System.getProperty("java.library.path")).append(". ");
        }
        sb.append("Set -D").append(PROPERTY).append("=/path/to/lib or ")
          .append(ENV_VAR).append("=/path/to/lib. ")
          .append("The library also needs libgomp and the C++ runtime on the loader path.");
        return sb.toString();
    }
}
