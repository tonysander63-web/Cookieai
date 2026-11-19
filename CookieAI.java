
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.io.*;

public class CookieAI {

    static final int PORT = Integer.parseInt(
        System.getenv().getOrDefault("PORT", "8080")
    );

    static final String MODEL = "llama-3.3-70b-versatile";
    static final Path MEMORY = Path.of("cookieai-memory.txt");

    static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(20))
        .build();

    public static void main(String[] args) throws Exception {

        HttpServer server = HttpServer.create(
            new InetSocketAddress("0.0.0.0", PORT), 0
        );

        server.createContext("/", CookieAI::home);
        server.createContext("/chat", CookieAI::chat);

        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("CookieAI läuft auf Port " + PORT);
    }

    static void home(HttpExchange ex) throws IOException {

        String html = """
        <!DOCTYPE html>
        <html lang="de">
        <head>
        <meta charset="UTF-8">
        <meta name="viewport"
              content="width=device-width,initial-scale=1">
        <title>CookieAI</title>

        <style>
        body {
            background:#090914;
            color:white;
            font-family:Arial;
            margin:0;
        }
        header {
            background:#17132c;
            padding:20px;
            font-size:25px;
            font-weight:bold;
        }
        main {
            max-width:800px;
            margin:auto;
            padding:15px;
        }
        #chat {
            min-height:65vh;
            display:flex;
            flex-direction:column;
            gap:12px;
        }
        .msg {
            padding:13px;
            border-radius:14px;
            max-width:85%;
            white-space:pre-wrap;
            overflow-wrap:anywhere;
        }
        .bot { background:#211d3b; }
        .user {
            background:#6336a8;
            align-self:flex-end;
        }
        form { display:flex; gap:8px; }
        textarea {
            flex:1;
            padding:12px;
            background:#171629;
            color:white;
            border:1px solid #444;
            border-radius:10px;
        }
        button {
            background:#8956ed;
            color:white;
            border:0;
            border-radius:10px;
            padding:12px;
        }
        </style>
        </head>

        <body>
        <header>🍪 CookieAI</header>

        <main>
        <div id="chat">
        <div class="msg bot">
        Hallo! Ich bin CookieAI.
        Was möchtest du wissen?
        </div>
        </div>

        <form id="form">
        <textarea id="message"
        placeholder="Schreib CookieAI..."
        required></textarea>
        <button>Senden</button>
        </form>
        </main>

        <script>
        const chat = document.getElementById("chat");
        const form = document.getElementById("form");
        const input = document.getElementById("message");

        function add(text, type) {
            const el = document.createElement("div");
            el.className = "msg " + type;
            el.textContent = text;
            chat.appendChild(el);
            el.scrollIntoView({behavior:"smooth"});
        }

        form.addEventListener("submit", async e => {
            e.preventDefault();

            const message = input.value.trim();
            if (!message) return;

            input.value = "";
            add(message, "user");

            const wait = document.createElement("div");
            wait.className = "msg bot";
            wait.textContent = "CookieAI denkt nach...";
            chat.appendChild(wait);

            try {
                const response = await fetch("/chat", {
                    method:"POST",
                    headers:{"Content-Type":"application/json"},
                    body:JSON.stringify({message})
                });

                const data = await response.json();
                wait.textContent = data.reply || data.error;
            } catch(error) {
                wait.textContent = "Verbindungsfehler.";
            }
        });
        </script>
        </body>
        </html>
        """;

        send(ex, 200, html, "text/html; charset=utf-8");
    }

    static void chat(HttpExchange ex) throws IOException {

        String key = System.getenv("GROQ_API_KEY");

        if (key == null || key.isBlank()) {
            sendJson(ex, 500,
                "{\"error\":\"Groq API-Schlüssel fehlt.\"}");
            return;
        }

        if (!ex.getRequestMethod().equals("POST")) {
            sendJson(ex, 405,
                "{\"error\":\"POST erforderlich.\"}");
            return;
        }

        try {
            String body = new String(
                ex.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8
            );

            String message = getJsonString(body, "message");

            if (message == null || message.isBlank()) {
                sendJson(ex, 400,
                    "{\"error\":\"Nachricht fehlt.\"}");
                return;
            }

            if (message.length() > 4000) {
                sendJson(ex, 413,
                    "{\"error\":\"Nachricht zu lang.\"}");
                return;
            }

            String lower = message.toLowerCase();

            if (lower.startsWith("merke:")) {
                String note = message.substring(6).trim();

                Files.writeString(
                    MEMORY,
                    note + System.lineSeparator(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
                );

                sendJson(ex, 200,
                    "{\"reply\":\"Ich habe mir das gemerkt!\"}");
                return;
            }

            if (lower.startsWith("vergiss:")) {
                String note = message.substring(8).trim();

                if (Files.exists(MEMORY)) {
                    var lines = Files.readAllLines(MEMORY);
                    lines.removeIf(s -> s.equalsIgnoreCase(note));
                    Files.write(MEMORY, lines);
                }

                sendJson(ex, 200,
                    "{\"reply\":\"Notiz entfernt, falls vorhanden.\"}");
                return;
            }

            String memory = Files.exists(MEMORY)
                ? Files.readString(MEMORY)
                : "Noch keine Notizen.";

            if (memory.length() > 5000) {
                memory = memory.substring(0, 5000);
            }

            String prompt =
                "Du bist CookieAI, ein freundlicher deutscher KI-Assistent. "
                + "Antworte verständlich und ehrlich. "
                + "Gespeicherte Notizen:\n" + memory
                + "\nNachricht: " + message;

            String json =
                "{\"model\":\"" + MODEL + "\","
                + "\"messages\":[{\"role\":\"user\",\"content\":\""
                + escape(prompt) + "\"}],"
                + "\"max_tokens\":700}";

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(
                    "https://api.groq.com/openai/v1/chat/completions"
                ))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

            HttpResponse<String> result = CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString()
            );

            if (result.statusCode() < 200 ||
                result.statusCode() >= 300) {
                sendJson(ex, 502,
                    "{\"error\":\"Groq-Fehler: HTTP "
                    + result.statusCode() + "\"}");
                return;
            }

            String answer = getJsonString(result.body(), "content");

            if (answer == null) {
                answer = "Die Antwort konnte nicht gelesen werden.";
            }

            sendJson(ex, 200,
                "{\"reply\":\"" + escape(answer) + "\"}");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendJson(ex, 500,
                "{\"error\":\"Anfrage unterbrochen.\"}");
        } catch (Exception e) {
            sendJson(ex, 500,
                "{\"error\":\"Serverfehler.\"}");
        }
    }

    static String getJsonString(String json, String name) {
        String marker = "\"" + name + "\"";
        int i = json.indexOf(marker);
        if (i < 0) return null;

        i = json.indexOf(':', i);
        if (i < 0) return null;

        i = json.indexOf('"', i);
        if (i < 0) return null;

        StringBuilder out = new StringBuilder();
        boolean escaped = false;

        for (i++; i < json.length(); i++) {
            char c = json.charAt(i);

            if (escaped) {
                switch (c) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    default -> out.append(c);
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return out.toString();
            } else {
                out.append(c);
            }
        }
        return null;
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    static void sendJson(
        HttpExchange ex, int status, String text
    ) throws IOException {
        send(ex, status, text, "application/json; charset=utf-8");
    }

    static void send(
        HttpExchange ex, int status,
        String text, String type
    ) throws IOException {

        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, data.length);

        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }
}
