package com.paybridge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paybridge.api.ApiErrorWriter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {
  @Bean
  SecurityContextRepository securityContextRepository() {
    return new HttpSessionSecurityContextRepository();
  }

  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http,
      SecurityContextRepository securityContextRepository,
      ObjectMapper objectMapper) throws Exception {
    CookieCsrfTokenRepository csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
    csrfRepository.setCookiePath("/");
    return http
        .cors(Customizer.withDefaults())
        .csrf(csrf -> csrf
            .csrfTokenRepository(csrfRepository)
            .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
            .ignoringRequestMatchers("/api/v1/webhooks/receiver"))
        .securityContext(context -> context.securityContextRepository(securityContextRepository))
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/actuator/health", "/api/v1/version", "/api/v1/auth/csrf", "/api/v1/auth/profiles",
                "/api/v1/auth/profile-login", "/api/v1/webhooks/receiver").permitAll()
            .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
            .requestMatchers("/api/v1/**").authenticated()
            .anyRequest().denyAll())
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, exception) -> ApiErrorWriter.write(
                request, response, objectMapper, 401, "AUTH_REQUIRED", "Sign in to continue"))
            .accessDeniedHandler((request, response, exception) -> ApiErrorWriter.write(
                request, response, objectMapper, 403, "ACCESS_DENIED", "This action is not permitted")))
        .headers(headers -> headers
            .contentSecurityPolicy(csp -> csp.policyDirectives(
                "default-src 'self'; img-src 'self' data:; style-src 'self' 'unsafe-inline'; "
                    + "script-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'"))
            .frameOptions(frame -> frame.deny()))
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${paybridge.frontend-origin}") String frontendOrigin) {
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(List.of(frontendOrigin));
    configuration.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    configuration.setAllowedHeaders(List.of(
        "Content-Type", "X-Request-ID", "X-Trace-ID", "Idempotency-Key", "X-XSRF-TOKEN",
        "X-PayBridge-Signature"));
    configuration.setExposedHeaders(List.of("X-Request-ID", "X-Trace-ID"));
    configuration.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/v1/**", configuration);
    return source;
  }
}