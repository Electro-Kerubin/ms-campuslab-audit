package org.campuslab.audit.security;

import java.util.Collection;
import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Seguridad del microservicio (mismo esquema que bookings y catalog).
 *
 * - Valida el JWT de Azure AD (issuer, firma, expiración) vía issuer-uri en application.yml.
 * - Convierte el claim "roles" del token en roles de Spring (ROLE_ADMIN, ROLE_AUDITOR...).
 * - Sin sesiones: cada request debe traer su Bearer token.
 * - La auditoría es sensible: el controlador exige rol ADMIN o AUDITOR.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity // activa los @PreAuthorize del controller
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/audit/**").authenticated()
                        .anyRequest().denyAll())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthConverter())))
                .csrf(csrf -> csrf.disable()); // sin cookies de sesión, CSRF no aplica

        return http.build();
    }

    /**
     * Azure AD entrega los roles en el claim "roles" (ej: ["ADMIN","AUDITOR"]).
     * Este converter los transforma en ROLE_ADMIN, ROLE_AUDITOR, etc.
     */
    private JwtAuthenticationConverter jwtAuthConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Collection<String> roles = jwt.getClaimAsStringList("roles");
            if (roles == null) return List.of();

            return roles.stream()
                    .map((String rol) -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + rol.toUpperCase()))
                    .toList();
        });
        return converter;
    }
}
