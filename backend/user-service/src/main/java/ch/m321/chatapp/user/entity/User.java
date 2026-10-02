package ch.m321.chatapp.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Ein Benutzer der Chat-App.
 *
 * Die eigentliche Authentifizierung (Passwort, Login-Formular, Tokens)
 * uebernimmt vollstaendig Keycloak. Hier wird nur das fachliche Profil
 * gespeichert (Anzeigename, Benutzername), verknuepft ueber die
 * Keycloak-Benutzer-ID. Existiert beim ersten Login noch kein Profil,
 * legt der UserService es automatisch aus den JWT-Claims an.
 *
 * Tabellenname bewusst "users" statt "user", da "user" in PostgreSQL ein
 * reserviertes Wort ist.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Verweist auf die "sub"-Angabe im JWT von Keycloak. Eindeutig pro Person.
    @Column(nullable = false, unique = true)
    private String keycloakUserId;

    @Column(nullable = false, unique = true)
    private String username;

    private String displayName;

    @Column(nullable = false)
    private Instant createdAt;
}
