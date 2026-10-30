package com.dcontrol.controller.common.security;

/** Value of the {@code typ} claim. A token is only accepted where its type is expected. */
public enum TokenType {
    ACCESS("access"),
    EMAIL_VERIFY("email_verify"),
    PASSWORD_RESET("password_reset");

    private final String claim;

    TokenType(String claim) {
        this.claim = claim;
    }

    public String claim() {
        return claim;
    }
}
