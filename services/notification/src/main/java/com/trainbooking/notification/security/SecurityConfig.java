package com.trainbooking.notification.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * Notification has no user endpoints: users never call it, Booking and Seat does (CLAUDE.md, "Services"). So everything under
 * {@code /internal} needs the shared API key, and anything else is refused. The Gateway never forwards to this service.
 */
// @Configuration declares the beans below; @EnableWebSecurity turns on Spring Security's web support. There is no JWT here: this service
// has no users, only the shared key (CLAUDE.md, Identity).
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    // Two filter chains. @Bean registers each; @Order(1) is tried first and the securityMatcher decides which requests a chain handles.
    // @Value injects a setting (trainbooking.internal-api-key, from INTERNAL_API_KEY).
    // Chain 1: only /internal/**. Stateless (no session, no cookie), so CSRF protection (a browser-cookie attack) is not needed and is
    // disabled.
    // The key filter runs before the anonymous filter; then only ROLE_INTERNAL is allowed.
    @Order(1)
    SecurityFilterChain internalChain(HttpSecurity http, JsonErrorHandlers errorHandlers, JsonMapper jsonMapper,
                                      @Value("${trainbooking.internal-api-key}") String apiKey) throws Exception {
        http
                .securityMatcher("/internal/**")
                .csrf(csrf -> csrf.disable()) // no cookies or sessions: the caller proves itself with the key header
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new InternalApiKeyFilter(apiKey, jsonMapper), AnonymousAuthenticationFilter.class)
                .authorizeHttpRequests(requests -> requests.anyRequest().hasAuthority(InternalApiKeyFilter.ROLE))
                .exceptionHandling(errors -> errors.authenticationEntryPoint(errorHandlers).accessDeniedHandler(errorHandlers));
        return http.build();
    }

    @Bean
    // Chain 2: everything that is not /internal, denied for all (403 forbidden). This service has no public routes.
    @Order(2)
    SecurityFilterChain everythingElse(HttpSecurity http, JsonErrorHandlers errorHandlers) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(errorHandlers).accessDeniedHandler(errorHandlers));
        return http.build();
    }
}
