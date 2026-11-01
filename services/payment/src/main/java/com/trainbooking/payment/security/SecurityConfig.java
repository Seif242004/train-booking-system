package com.trainbooking.payment.security;

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
 * Payment has no user endpoints: users never call it, Booking and Seat does. So there are three kinds of request:
 * <ol>
 *   <li>{@code /internal/**} from other services: the shared API key;</li>
 *   <li>{@code /webhooks/**} from Stripe, through the Gateway: no login, but the signature of every event is verified before anything
 *       is read from it;</li>
 *   <li>anything else is refused.</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

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

    @Bean
    @Order(2)
    SecurityFilterChain webhookChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/webhooks/**")
                .csrf(csrf -> csrf.disable()) // Stripe sends no cookie; the signature is what authenticates the call
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().permitAll());
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain everythingElse(HttpSecurity http, JsonErrorHandlers errorHandlers) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests.anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(errorHandlers).accessDeniedHandler(errorHandlers));
        return http.build();
    }
}
