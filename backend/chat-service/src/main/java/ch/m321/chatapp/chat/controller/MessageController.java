package ch.m321.chatapp.chat.controller;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Einziger Endpunkt fuer neue Chatnachrichten.
 *
 * Kein Authentifizierungs-Schutz: chat-service vertraut dem internen Netz
 * (PLANUNG.md 3.1 - Token wird nur am, hier nicht Teil dieser Abgabe
 * befindlichen, Gateway geprueft). Pfad ohne /api-Praefix, wie in den
 * Abnahmeszenarien S3/S4/S6 verwendet. Antwortet immer 202 Accepted -
 * die Nachricht ist entgegengenommen und veroeffentlicht, aber noch nicht
 * gespeichert; das uebernimmt batch-writer asynchron.
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @PostMapping("/messages")
    public ResponseEntity<Void> sendMessage(@RequestBody NewMessageRequest request) {
        messageService.publishNewMessage(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
