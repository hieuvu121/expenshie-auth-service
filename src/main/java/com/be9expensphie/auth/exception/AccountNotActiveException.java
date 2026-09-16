package com.be9expensphie.auth.exception;

/** Thrown when credentials are not even considered because the account is not activated. */
public class AccountNotActiveException extends RuntimeException {
    public AccountNotActiveException(String message) {
        super(message);
    }
}
