package ch.m321.chatapp.chat.service;

import ch.m321.chatapp.chat.dto.ChatRoomDto;
import ch.m321.chatapp.chat.dto.NewChatRoomRequest;
import ch.m321.chatapp.chat.entity.ChatRoom;
import ch.m321.chatapp.chat.mapper.ChatRoomMapper;
import ch.m321.chatapp.chat.repository.ChatRoomRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Fachlogik rund um Chatraeume: Anlegen, Nachladen und Auflisten.
 * Die Mitgliederverwaltung (wer ist in welchem Raum) folgt in einem
 * spaeteren Schritt - anders als bei Nachrichten braucht das Anlegen eines
 * Chatraums keine RabbitMQ-Entkopplung, es ist eine einfache, schnelle
 * Datenbankoperation.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMapper chatRoomMapper;

    public List<ChatRoomDto> getAllChatRooms() {
        List<ChatRoom> chatRooms = chatRoomRepository.findAll();
        List<ChatRoomDto> result = new ArrayList<>();
        for (ChatRoom chatRoom : chatRooms) {
            result.add(chatRoomMapper.toDto(chatRoom));
        }
        return result;
    }

    public ChatRoomDto getChatRoomById(UUID id) {
        Optional<ChatRoom> foundRoom = chatRoomRepository.findById(id);
        if (foundRoom.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Chatraum nicht gefunden");
        }
        return chatRoomMapper.toDto(foundRoom.get());
    }

    // Legt einen neuen Chatraum an.
    public ChatRoomDto createChatRoom(NewChatRoomRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name darf nicht leer sein");
        }
        if (request.type() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Typ (DIRECT oder GROUP) muss angegeben werden");
        }

        ChatRoom newRoom = new ChatRoom();
        newRoom.setName(request.name());
        newRoom.setType(request.type());
        newRoom.setCreatedAt(Instant.now());
        ChatRoom savedRoom = chatRoomRepository.save(newRoom);
        log.info("Neuer Chatraum angelegt: {}", savedRoom.getId());
        return chatRoomMapper.toDto(savedRoom);
    }
}
