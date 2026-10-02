package ch.m321.chatapp.chat.controller;

import ch.m321.chatapp.chat.dto.NewMessageRequest;
import ch.m321.chatapp.chat.service.MessageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft, dass POST /messages (ohne Authentifizierung, ohne /api-Praefix,
 * siehe PLANUNG.md 3.1 und die Formulierung in S3/S4/S6) 202 Accepted
 * liefert und den Request an den Service weiterreicht.
 */
@WebMvcTest(MessageController.class)
class MessageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private MessageService messageService;

    // Kein eigener JWT-Mock noetig: chat-service hat keine SecurityConfig mehr.
    @Test
    void postMessage_returnsAccepted() throws Exception {
        NewMessageRequest request = new NewMessageRequest(null, java.util.UUID.randomUUID(), "user-1", "Alice", "Hallo", null);

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted());

        verify(messageService).publishNewMessage(request);
    }
}
