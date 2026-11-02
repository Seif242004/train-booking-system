package com.trainbooking.trainschedule.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The service-to-service entry of Train & Schedule: Booking and Seat tells it that a trip was cancelled (Flow 8). Everything under
 * {@code /internal} needs the shared API key and ignores JWTs; the Gateway never forwards it. It is its own configuration class (and chain)
 * so that the controller tests of the user endpoints, which only load {@link SecurityConfig}, do not need the key.
 */
@Configuration
public class InternalSecurityConfig {

    @Bean
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
}
