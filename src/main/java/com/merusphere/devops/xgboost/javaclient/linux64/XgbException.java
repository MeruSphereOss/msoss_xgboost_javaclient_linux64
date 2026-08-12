package com.merusphere.devops.xgboost.javaclient.linux64;

/**
 * Thrown when an XGBoost C API call returns a non-zero status.
 *
 * <p>The message is whatever {@code XGBGetLastError()} reported for the failing
 * call. XGBoost keeps that string in thread-local storage, so it is only
 * meaningful on the thread that made the call &mdash; which is why it is
 * captured eagerly here rather than looked up lazily.
 */
public class XgbException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String operation;

    public XgbException(String operation, String nativeMessage) {
        super(operation + " failed: " + nativeMessage);
        this.operation = operation;
    }

    public XgbException(String message) {
        super(message);
        this.operation = null;
    }

    public XgbException(String message, Throwable cause) {
        super(message, cause);
        this.operation = null;
    }

    /** Name of the C entry point that failed, or {@code null} if not applicable. */
    public String operation() {
        return operation;
    }
}
