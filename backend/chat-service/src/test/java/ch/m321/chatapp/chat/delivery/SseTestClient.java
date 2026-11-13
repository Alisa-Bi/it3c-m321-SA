package ch.m321.chatapp.chat.delivery;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Hilfsklasse fuer Tests: ein SSE-Client, der GET /stream oeffnet und in einem
 * eigenen Thread Zeile fuer Zeile liest. Ein Ereignis ist fertig, wenn die
 * Leerzeile kommt. Kommentarzeilen (connected, heartbeat) sind kein Ereignis.
 * Zeilen, die nicht zum SSE-Format passen, werden in formatViolations gesammelt.
 */
public final class SseTestClient {

    /** Ein vollstaendig empfangenes SSE-Ereignis. */
    public static final class ReceivedEvent {
        public final String id;
        public final String data;

        // id: Wert der id:-Zeile. data: Wert der data:-Zeile (der JSON-Text).
        ReceivedEvent(String id, String data) {
            this.id = id;
            this.data = data;
        }
    }

    public final List<ReceivedEvent> events = new CopyOnWriteArrayList<>();
    public final List<String> formatViolations = new CopyOnWriteArrayList<>();

    private final InputStream body;

    // Oeffnet die Verbindung zur angegebenen Instanz und startet den Lese-Thread.
    // Schlaegt fehl, wenn der Status nicht 200 ist.
    public SseTestClient(HttpClient httpClient, String baseUrl, UUID roomId) throws Exception {
        this(httpClient, baseUrl, roomId, null);
    }

    // Wie oben, aber mit dem Header Last-Event-ID, wenn lastEventId nicht null ist (Neuverbindung).
    public SseTestClient(HttpClient httpClient, String baseUrl, UUID roomId, UUID lastEventId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + "/stream?roomId=" + roomId)).GET();
        if (lastEventId != null) {
            builder.header("Last-Event-ID", lastEventId.toString());
        }
        HttpRequest request = builder.build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Unexpected status " + response.statusCode());
        }
        this.body = response.body();
        Thread readerThread = new Thread(this::readLoop, "test-sse-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    // Liest Zeilen und setzt daraus Ereignisse zusammen.
    private void readLoop() {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
        String id = null;
        String data = null;
        try {
            String line = reader.readLine();
            while (line != null) {
                if (line.startsWith(":")) {
                    // Kommentarzeile, kein Ereignis.
                } else if (line.startsWith("id:")) {
                    id = line.substring(3);
                } else if (line.startsWith("data:")) {
                    data = line.substring(5);
                } else if (line.isEmpty()) {
                    if (id != null && data != null) {
                        events.add(new ReceivedEvent(id, data));
                    } else if (id != null || data != null) {
                        formatViolations.add("Ereignis nur mit id oder nur mit data (id=" + id + ")");
                    }
                    // Eine Leerzeile ohne id und data beendet nur einen Kommentarblock (connected, heartbeat).
                    id = null;
                    data = null;
                } else {
                    formatViolations.add("Unerwartete Zeile: " + line);
                }
                line = reader.readLine();
            }
        } catch (IOException e) {
            // Verbindung vom Test geschlossen, das Ende ist gewollt.
        }
    }

    // Anzahl der bisher vollstaendig empfangenen Ereignisse.
    public int eventCount() {
        return events.size();
    }

    // Schliesst die Verbindung.
    public void close() throws IOException {
        body.close();
    }
}
