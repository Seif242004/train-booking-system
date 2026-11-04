package com.trainbooking.bookingseat.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two filter chains, because the callers are different:
 * <ol>
 *   <li>{@code /internal/**} is for other services (the snapshot push from Train & Schedule). It needs the shared API
 *       key and ignores JWTs.</li>
 *   <li>Everything else is for users: this service is an OAuth2 resource server and checks the Keycloak access token
 *       itself, even though the Gateway already did. Signature, expiry and issuer are validated by Spring from the
 *       configured issuer-uri; there is deliberately no audience check, as in the Gateway.</li>
 * </ol>
 */
@Configuration
// @Configuration declares beans; @EnableWebSecurity switches on Spring Security's filter chain for the web app.
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    // @Bean registers the returned object as a Spring bean. @Order(1): chains are tried in order and the first whose securityMatcher fits
    // the request wins, so /internal/** must come first. The @Value parameter reads the property trainbooking.internal-api-key
    // (application.properties, from the INTERNAL_API_KEY environment variable).
    @Order(1)
    SecurityFilterChain internalChain(HttpSecurity http, JsonErrorHandlers errorHandlers, JsonMapper jsonMapper,
                                      @Value("${trainbooking.internal-api-key}") String apiKey) throws Exception {
        http
                .securityMatcher("/internal/**")
                // CSRF (cross-site request forgery) protects cookie sessions; this chain has none.
                .csrf(csrf -> csrf.disable()) // no cookies or sessions here: the caller proves itself with the key header
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // The key filter runs before the anonymous filter, so by the time authorizeHttpRequests decides the caller is already
                // authenticated.
                .addFilterBefore(new InternalApiKeyFilter(apiKey, jsonMapper), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().hasAuthority(InternalApiKeyFilter.ROLE))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers));
        return http.build();
    }

    @Bean
    // The user chain has no securityMatcher: it catches every request that the internal chain did not claim.
    @Order(2)
    SecurityFilterChain userChain(HttpSecurity http, JsonErrorHandlers errorHandlers) throws Exception {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRolesConverter());

        http
                // Stateless API authenticated by a bearer token, not by a cookie: there is no session to protect
                // from cross-site request forgery.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // The WebSocket handshake carries its token as a subprotocol, which a filter cannot read as a bearer token:
                        // HandshakeAuthentication checks it and refuses the connection without a valid one.
                        // Rule order matters: first match wins. /ws is open at filter level (the handshake interceptor checks the token),
                        // /admin/** needs the
                        // ADMIN role (403 otherwise, SRS error contract), everything else needs any valid token (401 otherwise).
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                // Turns on bearer-token validation: the Authorization: Bearer <jwt> header is decoded, its signature is checked with the
                // keys of the
                // issuer-uri (fetched from Keycloak's JWKS and cached), and the roles converter above builds the authorities.
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers));
        return http.build();
    }
}
