package ch.m321.chatapp.chat.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security-Konfiguration des Chat-Service.
 * REST-Endpunkte verlangen ein gueltiges JWT. Der WebSocket-Endpunkt ist
 * noch NICHT abgesichert (siehe TODO) - das folgt, sobald das Frontend den
 * Token beim Verbindungsaufbau mitschicken kann (siehe ARCHITECTURE.md,
 * Punkt 5). Diese Luecke wird hier bewusst offen dokumentiert statt
 * verschwiegen.
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
                        // TODO: WebSocket-Handshake ueber ein Token im Query-Parameter
                        // absichern, sobald das Frontend das unterstuetzt.
                        .requestMatchers("/ws/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .build();
    }
}
