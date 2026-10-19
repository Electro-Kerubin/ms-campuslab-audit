package org.campuslab.audit.controller;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * La SecurityConfig exige un JwtDecoder para armar la cadena de filtros. En estos tests nunca se invoca
 * (jwt() de spring-security-test inyecta la autenticación directamente): basta con uno que falle si lo llaman.
 */
@TestConfiguration
class FakeJwtDecoderConfig {

    @Bean
    JwtDecoder jwtDecoder() {
        return token -> {
            throw new IllegalStateException("Los tests deben autenticarse con jwt()");
        };
    }
}
