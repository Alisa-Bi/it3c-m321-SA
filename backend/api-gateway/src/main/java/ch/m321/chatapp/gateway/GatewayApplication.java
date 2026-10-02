package ch.m321.chatapp.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des API-Gateways.
 * Das Gateway ist der einzige Einstiegspunkt in die Backend-Services und
 * prueft eingehende Anfragen auf ein gueltiges JWT, bevor sie an
 * user-service oder chat-service weitergeleitet werden.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
