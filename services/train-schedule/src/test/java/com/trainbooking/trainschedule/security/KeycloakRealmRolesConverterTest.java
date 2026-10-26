package com.trainbooking.trainschedule.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class KeycloakRealmRolesConverterTest {

    private final KeycloakRealmRolesConverter converter = new KeycloakRealmRolesConverter();

    private static Jwt tokenWith(Map<String, Object> claims) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-1").issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).claims(c -> c.putAll(claims)).build();
    }

    @Test
    void realmRolesBecomeRolePrefixedAuthorities() {
        Jwt jwt = tokenWith(Map.of("realm_access", Map.of("roles", List.of("USER", "ADMIN"))));

        assertThat(converter.convert(jwt)).extracting(Object::toString).containsExactlyInAnyOrder("ROLE_USER", "ROLE_ADMIN");
    }

    @Test
    void rolesOutsideRealmAccessAreIgnored() {
        Jwt jwt = tokenWith(Map.of("resource_access", Map.of("account", Map.of("roles", List.of("ADMIN")))));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void missingOrMalformedClaimGivesNoAuthorities() {
        assertThat(converter.convert(tokenWith(Map.of("scope", "openid")))).isEmpty();
        assertThat(converter.convert(tokenWith(Map.of("realm_access", Map.of("roles", "ADMIN"))))).isEmpty();
    }
}
