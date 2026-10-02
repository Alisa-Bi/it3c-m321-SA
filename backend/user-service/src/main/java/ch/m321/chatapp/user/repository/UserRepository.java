package ch.m321.chatapp.user.repository;

import ch.m321.chatapp.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Datenzugriff fuer Benutzer. Spring Data JPA erzeugt die Implementierung
 * dieses Interfaces automatisch - die Methodennamen werden direkt in
 * SQL-Abfragen uebersetzt.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    // Wird beim Login gebraucht: Profil zur Keycloak-ID des JWT finden.
    Optional<User> findByKeycloakUserId(String keycloakUserId);

    // Wird von der Benutzersuche gebraucht.
    List<User> findByUsernameContainingIgnoreCase(String usernamePart);
}
