package ch.benedict.m321.chatservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Was das Gateway an den chat-service schickt.
 *
 * Bewusst OHNE id und ohne Zeitstempel: beides vergibt der Server.
 * Ein Client, der sich seine eigene Nachrichten-ID ausdenken darf,
 * kann fremde Nachrichten überschreiben.
 *
 * @param roomId     der Raum, in den die Nachricht gehört
 * @param senderId   die sub-Kennung des Absenders aus Keycloak
 * @param senderName der Anzeigename, damit die Historie lesbar bleibt
 * @param content    der Text der Nachricht
 */
public record SendMessageRequest(
        @NotNull UUID roomId,
        @NotBlank String senderId,
        @NotBlank String senderName,
        @NotBlank String content) {
}
