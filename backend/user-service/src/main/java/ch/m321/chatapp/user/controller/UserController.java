package ch.m321.chatapp.user.controller;

import ch.m321.chatapp.user.dto.UserDto;
import ch.m321.chatapp.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST-Schnittstelle fuer Benutzerprofile.
 * Wird ausschliesslich ueber das API-Gateway aufgerufen, nie direkt von
 * aussen - das Gateway hat bereits geprueft, dass ein gueltiges JWT
 * mitgeschickt wurde, bevor die Anfrage hier ankommt.
 */
@RestController
@RequestMapping("/api/users")
@Slf4j
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    // Spring Security validiert das JWT (Signatur, Ablaufdatum, Aussteller)
    // automatisch im Hintergrund (siehe SecurityConfig) und reicht es hier
    // bereits fertig geparst als Jwt-Objekt herein - "Benutzer aus JWT lesen"
    // passiert also an dieser Stelle.
    @GetMapping("/me")
    public UserDto getCurrentUser(@AuthenticationPrincipal Jwt jwt) {
        return userService.getCurrentUser(jwt);
    }

    @GetMapping("/{id}")
    public UserDto getUserById(@PathVariable UUID id) {
        return userService.getUserById(id);
    }

    @GetMapping("/search")
    public List<UserDto> searchUsers(@RequestParam String query) {
        return userService.searchUsers(query);
    }
}
