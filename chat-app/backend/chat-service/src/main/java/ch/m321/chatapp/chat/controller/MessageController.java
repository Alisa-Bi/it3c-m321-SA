package ch.m321.chatapp.chat.controller;

import ch.m321.chatapp.chat.dto.MessageDto;
import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.service.MessageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST-Schnittstelle fuer Chatnachrichten.
 *
 * Der eigentliche Versand laeuft asynchron ueber RabbitMQ (siehe
 * MessageService/MessageProducer/MessageConsumer): dieser Endpunkt nimmt
 * die Anfrage nur entgegen und gibt sie weiter, gespeichert wird sie erst
 * im MessageConsumer. Deshalb liefert POST hier bewusst 202 Accepted statt
 * der fertig gespeicherten Nachricht zurueck - wer die gespeicherte
 * Nachricht sehen will, ruft anschliessend GET /{roomId} auf (oder erhaelt
 * sie spaeter per WebSocket-Broadcast).
 */
@RestController
@RequestMapping("/api/messages")
@Slf4j
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    @GetMapping("/{roomId}")
    public List<MessageDto> getMessageHistory(@PathVariable UUID roomId) {
        return messageService.getMessageHistory(roomId);
    }

    @PostMapping
    public ResponseEntity<Void> sendMessage(@RequestBody NewMessageRequest request) {
        messageService.publishNewMessage(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }
}
