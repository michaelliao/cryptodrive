package org.puppylab.cryptodrive.core.exception;

public class CryptoFsException extends RuntimeException {

    public CryptoFsException() {
    }

    public CryptoFsException(String message) {
        super(message);
    }

    public CryptoFsException(Throwable cause) {
        super(cause);
    }

    public CryptoFsException(String message, Throwable cause) {
        super(message, cause);
    }
}
