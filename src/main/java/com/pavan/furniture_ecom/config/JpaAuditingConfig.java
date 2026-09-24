package com.pavan.furniture_ecom.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Objects;
import java.util.Optional;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditorAware")
public class JpaAuditingConfig {

    @Bean
    AuditorAware<String> auditorAware() {
        return () -> {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            if(auth instanceof JwtAuthenticationToken jwtAuthenticationToken) {
                String email = jwtAuthenticationToken.getToken().getClaimAsString("email");
                return Optional.of(email != null ? email : Objects.requireNonNull(jwtAuthenticationToken.getToken().getSubject()));
            }

            return Optional.of("system");
        };
    }
}
