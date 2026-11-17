package com.gek.inference;

public class SidecarException extends RuntimeException {
    public SidecarException(String message) { super(message); }
    public SidecarException(String message, Throwable cause) { super(message, cause); }
}
