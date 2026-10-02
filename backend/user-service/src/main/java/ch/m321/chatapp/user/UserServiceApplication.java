package ch.m321.chatapp.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des User-Service.
 * Zustaendig fuer Benutzerprofile und Benutzersuche. Die Authentifizierung
 * selbst uebernimmt Keycloak, dieser Service verwaltet nur das Fachprofil.
 */
@SpringBootApplication
public class UserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserServiceApplication.class, args);
    }
}
