package com.trainbooking.trainschedule.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * This service is an OAuth2 resource server: it checks the Keycloak access token itself, even though the Gateway
 * already did. The Gateway forwards the token unchanged, and this check means a request that reaches the service by
 * any other path is still refused. Signature, expiry and issuer are validated by Spring from the configured
 * issuer-uri; there is deliberately no audience check, for the same reason as in the Gateway.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(2) // after the internal chain of InternalSecurityConfig, which takes /internal/** first
    SecurityFilterChain securityFilterChain(HttpSecurity http, JsonErrorHandlers errorHandlers) throws Exception {
        JwtAuthenticationConverter jwtConverter = new JwtAuthenticationConverter();
        jwtConverter.setJwtGrantedAuthoritiesConverter(new KeycloakRealmRolesConverter());

        http
                // Stateless API authenticated by a bearer token, not by a cookie: there is no session to protect
                // from cross-site request forgery.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers))
                .oauth2ResourceServer(resource -> resource
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter))
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers));
        return http.build();
    }
}
