package ch.m321.chatapp.chat.dto;

/**
 * Rohes JSON-Paket, das ueber den WebSocket "/ws/chat" hereinkommt.
 * Wird beim Empfang geparst und danach in eine echte MessageDto ueberfuehrt.
 * Beispiel: {"type": "MESSAGE", "roomId": "...", "content": "Hallo"}
 */
public record IncomingChatMessage(String type, String roomId, String content) {
}
