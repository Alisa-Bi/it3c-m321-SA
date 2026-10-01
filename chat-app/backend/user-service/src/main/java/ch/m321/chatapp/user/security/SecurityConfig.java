package ch.m321.chatapp.user.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security-Konfiguration des User-Service.
 *
 * Jede Anfrage (ausser dem Health-Check) benoetigt ein gueltiges JWT, das
 * von Keycloak ausgestellt wurde. Spring Security prueft dabei automatisch
 * Signatur, Ablaufdatum und Aussteller (issuer-uri aus application.yml)
 * und macht das geparste Token danach im Controller per
 * @AuthenticationPrincipal Jwt verfuegbar.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
