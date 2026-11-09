package com.trainbooking.ticket.security;

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
 * Ticket has no user endpoints: users never call it, Booking and Seat does (CLAUDE.md, "Services"). So everything under
 * {@code /internal} needs the shared API key, and anything else is refused. The Gateway never forwards to this service.
 */
// {@code @Configuration} makes this a configuration class; {@code @EnableWebSecurity} switches on the web filter chain of Spring Security,
// which we define below as two {@code SecurityFilterChain} beans. Spring tries the chains in {@code @Order} and uses the first whose
// matcher fits the request.
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    // Chain 1: everything under /internal/**. {@code @Order(1)} makes it the first chain tried.
    // Steps: no CSRF protection (CSRF protects cookie sessions, and there are none); stateless (no session is created or read); our own key
    // filter runs before the anonymous filter, so it sets the login first; every request must hold the authority ROLE_INTERNAL.
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
    @Order(2)
    // Chain 2: every other path is denied (denyAll). The service has no user endpoints and nothing public.
    SecurityFilterChain everythingElse(HttpSecurity http, JsonErrorHandlers errorHandlers) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(errorHandlers).accessDeniedHandler(errorHandlers));
        return http.build();
    }
}
