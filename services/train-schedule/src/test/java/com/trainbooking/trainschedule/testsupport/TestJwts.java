package com.trainbooking.trainschedule.testsupport;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Authenticated callers for MockMvc tests: an admin and an ordinary passenger. */
public final class TestJwts {

    public static final String ADMIN_SUB = "admin-sub";

    private TestJwts() {
    }

    public static RequestPostProcessor admin() {
        return jwt().jwt(token -> token.subject(ADMIN_SUB)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    public static RequestPostProcessor passenger() {
        return jwt().jwt(token -> token.subject("user-sub")).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }
}
