package ch.m321.chatapp.chat;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des Chat-Service.
 * Zustaendig fuer Chatraeume, Nachrichten, den RabbitMQ-Nachrichtentransport
 * und die WebSocket-Echtzeitkommunikation.
 */
@SpringBootApplication
public class ChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
