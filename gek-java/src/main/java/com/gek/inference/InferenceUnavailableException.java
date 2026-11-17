package com.gek.inference;

/** Thrown when a requested inference capability is not available in the current engine/runtime. */
public class InferenceUnavailableException extends RuntimeException {
    public InferenceUnavailableException(String message) { super(message); }
    public InferenceUnavailableException(String message, Throwable cause) { super(message, cause); }
}
