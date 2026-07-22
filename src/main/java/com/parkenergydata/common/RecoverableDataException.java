package com.parkenergydata.common;

public class RecoverableDataException extends RuntimeException {
    public RecoverableDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
