package ch.m321.chatapp.chat.messaging;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Prueft mit einem echten RabbitMQ, dass POST /messages bei ausgefallenem
 * RabbitMQ mit HTTP 503 antwortet und nicht mit einem unklaren Serverfehler
 * (docs/spec-live-zustellung.md, Abschnitte 4.1 und 6.9).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PublishFailureIntegrationTest {

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    // Verbindet die Anwendung mit dem Testcontainer. Das kurze Verbindungs-
    // Timeout sorgt dafuer, dass der Test bei ausgefallenem RabbitMQ schnell
    // scheitert, statt lange zu warten.
    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername);
        registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword);
        // Dieser Test braucht den Zustell-Listener nicht und soll ihn nicht stoeren.
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.connection-timeout", () -> "3000");
    }

    @Autowired
    private MockMvc mockMvc;

    // Erst mit laufendem RabbitMQ eine Nachricht senden (HTTP 202), dann den
    // Container anhalten und noch einmal senden: Jetzt muss HTTP 503 kommen.
    @Test
    void postMessage_whenBrokerStops_answersServiceUnavailable() throws Exception {
        String requestBody = "{\"roomId\":\"" + UUID.randomUUID() + "\",\"senderId\":\"user-1\","
                + "\"senderName\":\"Alice\",\"content\":\"Hallo\"}";

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted());

        rabbitmq.stop();

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isServiceUnavailable());
    }
}
