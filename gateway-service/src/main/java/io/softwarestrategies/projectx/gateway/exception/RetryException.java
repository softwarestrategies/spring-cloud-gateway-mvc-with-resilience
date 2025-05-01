package io.softwarestrategies.projectx.gateway.exception;

public class RetryException extends RuntimeException {

    public RetryException(String message) {
        super(message);
    }
}
