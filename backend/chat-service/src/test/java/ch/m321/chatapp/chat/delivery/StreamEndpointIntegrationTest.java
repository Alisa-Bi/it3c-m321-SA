package ch.m321.chatapp.chat.delivery;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Prueft den Endpunkt GET /stream mit einem echten HTTP-Client auf einem
 * zufaelligen Port (docs/spec-live-zustellung.md, Abschnitte 2.2 und 2.4):
 * Status 200 und Content-Type, 400 bei fehlender oder ungueltiger roomId,
 * zwei gleichzeitige Verbindungen mit Heartbeat und das Entfernen einer
 * getrennten Verbindung aus der Registry.
 *
 * RabbitMQ wird hier nicht gebraucht: der Listener ist ausgeschaltet, der
 * Heartbeat auf 200 ms verkuerzt, damit der Test schnell bleibt.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "chat.sse.heartbeat-interval=200ms"
        })
class StreamEndpointIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SseConnectionRegistry registry;

    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    // Baut die Anfrage fuer /stream mit dem angegebenen Query-Text (ohne "?").
    private HttpRequest streamRequest(String query) {
        String url = "http://localhost:" + port + "/stream";
        if (query != null) {
            url = url + "?" + query;
        }
        return HttpRequest.newBuilder(URI.create(url)).GET().build();
    }

    // Oeffnet eine Verbindung und gibt die Antwort mit dem Body als InputStream zurueck.
    private HttpResponse<InputStream> open(String query) throws Exception {
        return client.send(streamRequest(query), HttpResponse.BodyHandlers.ofInputStream());
    }

    // Liest Zeilen, bis eine Zeile genau dem Erwarteten entspricht. Nach dem Zeitlimit
    // schlaegt der Test fehl, statt ewig auf readLine() zu warten.
    private void readUntilLine(BufferedReader reader, String expectedLine) throws Exception {
        CompletableFuture<Boolean> reading = CompletableFuture.supplyAsync(() -> {
            try {
                String line = reader.readLine();
                while (line != null) {
                    if (line.equals(expectedLine)) {
                        return true;
                    }
                    line = reader.readLine();
                }
                return false;
            } catch (IOException e) {
                return false;
            }
        });
        Boolean found = reading.get(10, TimeUnit.SECONDS);
        assertThat(found).as("Zeile '%s' wurde gelesen", expectedLine).isTrue();
    }

    // Eine gueltige roomId ergibt Status 200 und den Content-Type text/event-stream.
    @Test
    void validRoomId_returns200AndEventStream() throws Exception {
        UUID roomId = UUID.randomUUID();

        HttpResponse<InputStream> response = open("roomId=" + roomId);
        try {
            assertThat(response.statusCode()).isEqualTo(200);
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            assertThat(contentType).startsWith("text/event-stream");
            assertThat(registry.connectionCount(roomId)).isEqualTo(1);
        } finally {
            response.body().close();
        }
    }

    // Ohne roomId antwortet der Endpunkt mit 400.
    @Test
    void missingRoomId_returns400() throws Exception {
        HttpResponse<InputStream> response = open(null);
        response.body().close();

        assertThat(response.statusCode()).isEqualTo(400);
    }

    // Eine roomId, die keine UUID ist, ergibt 400, und es wird keine Verbindung eingetragen.
    @Test
    void invalidRoomId_returns400() throws Exception {
        int before = registry.totalConnectionCount();

        HttpResponse<InputStream> response = open("roomId=not-a-uuid");
        response.body().close();

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(registry.totalConnectionCount()).isEqualTo(before);
    }

    // Zwei gleichzeitige Verbindungen im selben Raum sind beide eingetragen und erhalten Heartbeats.
    @Test
    void twoConnections_bothRegisteredAndReceiveHeartbeat() throws Exception {
        UUID roomId = UUID.randomUUID();

        HttpResponse<InputStream> first = open("roomId=" + roomId);
        HttpResponse<InputStream> second = open("roomId=" + roomId);
        try {
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(second.statusCode()).isEqualTo(200);
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(registry.connectionCount(roomId)).isEqualTo(2));

            BufferedReader firstReader = new BufferedReader(new InputStreamReader(first.body(), StandardCharsets.UTF_8));
            BufferedReader secondReader = new BufferedReader(new InputStreamReader(second.body(), StandardCharsets.UTF_8));
            readUntilLine(firstReader, ":heartbeat");
            readUntilLine(secondReader, ":heartbeat");
        } finally {
            first.body().close();
            second.body().close();
        }
    }

    // Trennt ein Client die Verbindung, wird sie spaetestens beim naechsten Heartbeat entfernt;
    // die andere Verbindung im Raum bleibt bestehen.
    @Test
    void disconnectedClient_isRemovedFromRegistry() throws Exception {
        UUID roomId = UUID.randomUUID();

        HttpResponse<InputStream> staying = open("roomId=" + roomId);
        HttpResponse<InputStream> leaving = open("roomId=" + roomId);
        try {
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> assertThat(registry.connectionCount(roomId)).isEqualTo(2));

            leaving.body().close();

            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(registry.connectionCount(roomId)).isEqualTo(1));
        } finally {
            staying.body().close();
        }

        await().atMost(Duration.ofSeconds(15))
                .untilAsserted(() -> assertThat(registry.connectionCount(roomId)).isZero());
    }
}
