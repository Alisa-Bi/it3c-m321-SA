package ch.m321.chatapp.user.service;

import ch.m321.chatapp.user.dto.UserDto;
import ch.m321.chatapp.user.entity.User;
import ch.m321.chatapp.user.mapper.UserMapper;
import ch.m321.chatapp.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fachlogik rund um Benutzerprofile.
 *
 * Die Authentifizierung selbst passiert bereits vorher in Keycloak; dieser
 * Service kuemmert sich nur noch um das fachliche Profil (Benutzername,
 * Anzeigename, Suche). Beim allerersten Login eines Benutzers gibt es noch
 * kein lokales Profil - deshalb wird es hier automatisch aus den
 * JWT-Claims angelegt (Just-in-Time-Provisioning), statt eine separate
 * Registrierung zu verlangen.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;

    // Liefert das Profil des eingeloggten Benutzers anhand der Keycloak-ID
    // im JWT ("sub"-Claim). Existiert noch kein lokales Profil, wird eines
    // aus den JWT-Claims angelegt - so muss sich niemand separat registrieren.
    public UserDto getCurrentUser(Jwt jwt) {
        String keycloakUserId = jwt.getSubject();
        Optional<User> foundUser = userRepository.findByKeycloakUserId(keycloakUserId);

        if (foundUser.isPresent()) {
            return userMapper.toDto(foundUser.get());
        }

        User newUser = createUserFromJwt(jwt);
        User savedUser = userRepository.save(newUser);
        log.info("Neues Benutzerprofil angelegt fuer Keycloak-ID {}", keycloakUserId);
        return userMapper.toDto(savedUser);
    }

    // Liefert ein beliebiges Benutzerprofil anhand seiner internen ID,
    // z. B. um den Namen eines Gesprächspartners anzuzeigen.
    public UserDto getUserById(UUID id) {
        Optional<User> foundUser = userRepository.findById(id);
        if (foundUser.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Benutzer nicht gefunden");
        }
        return userMapper.toDto(foundUser.get());
    }

    // Sucht Benutzer anhand eines Teils des Benutzernamens (Gross-/Kleinschreibung
    // wird ignoriert), z. B. um jemanden zu einem Gruppenchat hinzuzufuegen.
    public List<UserDto> searchUsers(String query) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Suchbegriff darf nicht leer sein");
        }

        List<User> matchingUsers = userRepository.findByUsernameContainingIgnoreCase(query);
        List<UserDto> result = new ArrayList<>();
        for (User user : matchingUsers) {
            result.add(userMapper.toDto(user));
        }
        return result;
    }

    // Baut aus den JWT-Claims ein neues User-Profil. "preferred_username" und
    // "name" sind Standard-Claims, die Keycloak bei jedem Login mitschickt -
    // dafuer ist im Realm keine zusaetzliche Konfiguration noetig.
    private User createUserFromJwt(Jwt jwt) {
        String username = jwt.getClaimAsString("preferred_username");
        String displayName = jwt.getClaimAsString("name");
        if (displayName == null || displayName.isBlank()) {
            displayName = username;
        }

        User user = new User();
        user.setKeycloakUserId(jwt.getSubject());
        user.setUsername(username);
        user.setDisplayName(displayName);
        user.setCreatedAt(Instant.now());
        return user;
    }
}
