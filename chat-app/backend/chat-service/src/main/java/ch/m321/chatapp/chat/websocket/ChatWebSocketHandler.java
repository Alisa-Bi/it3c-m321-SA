package ch.m321.chatapp.chat.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * Nimmt WebSocket-Nachrichten entgegen. Aktuell nur ein Geruest zum Testen
 * der Verbindung; das Parsen des JSON-Payloads und die Weitergabe an den
 * MessageProducer folgen im naechsten Entwicklungsschritt.
 */
@Component
@Slf4j
public class ChatWebSocketHandler extends TextWebSocketHandler {

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket-Verbindung aufgebaut: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        log.info("Nachricht von Session {} empfangen: {}", session.getId(), message.getPayload());
        // TODO: Payload als IncomingChatMessage parsen, in ein NewMessageRequest
        // umwandeln und an MessageService.publishNewMessage(...) uebergeben -
        // derselbe Weg, den auch POST /api/messages nutzt.
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("WebSocket-Verbindung geschlossen: {} ({})", session.getId(), status);
    }
}
