package com.dcontrol.controller.common.context;

import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Resolves the current {@link Actor}: an explicitly bound actor wins, otherwise the authenticated JWT,
 * otherwise anonymous.
 */
public final class ActorContext {

    public static final String ACCOUNT_CLAIM = "aid";

    private static final ThreadLocal<Actor> BOUND = new ThreadLocal<>();

    private ActorContext() {
    }

    public static Actor current() {
        Actor bound = BOUND.get();
        if (bound != null) {
            return bound;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken token) {
            return Actor.user(UUID.fromString(token.getToken().getClaimAsString(ACCOUNT_CLAIM)),
                    UUID.fromString(token.getToken().getSubject()), EventChannel.HTTP);
        }
        return Actor.anonymous();
    }

    public static <T> T callAs(Actor actor, Supplier<T> work) {
        Actor previous = BOUND.get();
        BOUND.set(actor);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                BOUND.remove();
            } else {
                BOUND.set(previous);
            }
        }
    }

    public static void runAs(Actor actor, Runnable work) {
        callAs(actor, () -> {
            work.run();
            return null;
        });
    }
}
