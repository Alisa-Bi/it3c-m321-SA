package ch.m321.chatapp.chat.controller;

import ch.m321.chatapp.chat.dto.ChatRoomDto;
import ch.m321.chatapp.chat.dto.NewChatRoomRequest;
import ch.m321.chatapp.chat.service.ChatRoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST-Schnittstelle fuer Chatraeume.
 */
@RestController
@RequestMapping("/api/chats")
@Slf4j
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    @GetMapping
    public List<ChatRoomDto> getAllChatRooms() {
        return chatRoomService.getAllChatRooms();
    }

    @PostMapping
    public ChatRoomDto createChatRoom(@RequestBody NewChatRoomRequest request) {
        return chatRoomService.createChatRoom(request);
    }

    @GetMapping("/{id}")
    public ChatRoomDto getChatRoomById(@PathVariable UUID id) {
        return chatRoomService.getChatRoomById(id);
    }
}
