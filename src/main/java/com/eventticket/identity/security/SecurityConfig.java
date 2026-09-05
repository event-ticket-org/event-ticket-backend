package com.eventticket.identity.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    private final SecretKeySpec key;

    public SecurityConfig(@Value("${app.security.jwt-secret}") String secret) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtTenantFilter tenantFilter) throws Exception {
        return http
                // A stateless bearer-token API: there is no session and no browser form to
                // forge a request from, so CSRF protection has nothing to protect.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/register", "/auth/verify-email",
                                "/auth/login", "/auth/refresh").permitAll()
                        .requestMatchers("/public/**", "/webhooks/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                .addFilterAfter(tenantFilter, org.springframework.security.web.access.intercept.AuthorizationFilter.class)
                .build();
    }

    @Bean
    public JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withSecretKey(key).build();
    }

    /**
     * BCrypt at the default strength. Passwords are the one place a deliberately slow hash is
     * the point, unlike the high-entropy random tokens in {@code SecureTokens}.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
