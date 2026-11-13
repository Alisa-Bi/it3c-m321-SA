package ch.m321.chatapp.chat.history;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Duration;

/**
 * Konfiguration des lesenden Datenbankzugriffs.
 *
 * Das JdbcTemplate erhaelt das Zeitlimit fuer Abfragen beim Nachholen
 * (chat.sse.replay-db-timeout, Standard 5 Sekunden). Der Verbindungspool baut beim
 * Start keine Verbindung auf (siehe application.yml). Der Dienst startet und stellt
 * Live-Nachrichten deshalb auch dann zu, wenn PostgreSQL nicht erreichbar ist.
 */
@Configuration
public class MessageHistoryConfig {

    // Erzeugt das JdbcTemplate. Das Zeitlimit wird auf ganze Sekunden aufgerundet, mindestens 1.
    @Bean
    public JdbcTemplate historyJdbcTemplate(DataSource dataSource,
                                            @Value("${chat.sse.replay-db-timeout}") Duration replayDbTimeout) {
        JdbcTemplate template = new JdbcTemplate(dataSource);
        long seconds = (replayDbTimeout.toMillis() + 999) / 1000;
        template.setQueryTimeout((int) Math.max(1, seconds));
        return template;
    }
}
