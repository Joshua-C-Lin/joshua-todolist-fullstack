package com.example.todolist;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    private static final Map<String, String> DOT_ENV = loadDotEnv(Path.of("backend/.env"));
    private static final int PORT = parsePort(configValue("PORT"), 8080);
    private static final String CORS_ALLOWED_ORIGIN = configValue("CORS_ALLOWED_ORIGIN", "http://localhost:5173");
    private static final TodoRepository REPOSITORY = createRepository();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/api/todos", Main::handleTodos);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();

        System.out.println("Todo API server is running at http://localhost:" + PORT);
        System.out.println("Storage: " + REPOSITORY.name());
    }

    private static TodoRepository createRepository() {
        String supabaseUrl = configValue("SUPABASE_URL");
        String supabaseKey = configValue("SUPABASE_KEY");

        if (isBlank(supabaseUrl) || isBlank(supabaseKey)) {
            return new InMemoryTodoRepository();
        }

        return new SupabaseTodoRepository(supabaseUrl, supabaseKey);
    }

    private static String configValue(String key) {
        return configValue(key, null);
    }

    private static String configValue(String key, String defaultValue) {
        String value = System.getenv(key);
        if (!isBlank(value)) {
            return value;
        }
        return DOT_ENV.getOrDefault(key, defaultValue);
    }

    private static int parsePort(String value, int defaultPort) {
        if (isBlank(value)) {
            return defaultPort;
        }

        try {
            int port = Integer.parseInt(value);
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("PORT must be between 1 and 65535");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("PORT must be a number", exception);
        }
    }

    private static Map<String, String> loadDotEnv(Path path) {
        Map<String, String> values = new HashMap<>();
        if (!Files.exists(path)) {
            return values;
        }

        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isBlank() || trimmed.startsWith("#")) {
                    continue;
                }

                int equalsIndex = trimmed.indexOf('=');
                if (equalsIndex <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, equalsIndex).trim();
                String value = trimmed.substring(equalsIndex + 1).trim();
                values.put(key, stripQuotes(value));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot read backend/.env", exception);
        }

        return values;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void handleTodos(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);

        if ("OPTIONS".equals(exchange.getRequestMethod())) {
            send(exchange, 204, "");
            return;
        }

        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String[] parts = path.split("/");

            if (parts.length == 3) {
                handleCollection(exchange, method);
                return;
            }

            if (parts.length == 4) {
                int id = Integer.parseInt(parts[3]);
                handleItem(exchange, method, id);
                return;
            }

            sendJson(exchange, 404, "{\"message\":\"Not found\"}");
        } catch (NumberFormatException exception) {
            sendJson(exchange, 400, "{\"message\":\"Invalid todo id\"}");
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, "{\"message\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (Exception exception) {
            sendJson(exchange, 500, "{\"message\":\"" + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private static void handleCollection(HttpExchange exchange, String method) throws IOException {
        switch (method) {
            case "GET" -> sendJson(exchange, 200, toJson(REPOSITORY.findAll()));
            case "POST" -> {
                TodoPayload payload = parsePayload(readBody(exchange));
                Todo todo = REPOSITORY.create(payload.title());
                sendJson(exchange, 201, todo.toJson());
            }
            default -> sendJson(exchange, 405, "{\"message\":\"Method not allowed\"}");
        }
    }

    private static void handleItem(HttpExchange exchange, String method, int id) throws IOException {
        switch (method) {
            case "PUT" -> {
                TodoPayload payload = parsePayload(readBody(exchange));
                Optional<Todo> updated = REPOSITORY.update(id, payload.title(), payload.completed());
                if (updated.isEmpty()) {
                    sendJson(exchange, 404, "{\"message\":\"Todo not found\"}");
                    return;
                }
                sendJson(exchange, 200, updated.get().toJson());
            }
            case "DELETE" -> {
                if (!REPOSITORY.delete(id)) {
                    sendJson(exchange, 404, "{\"message\":\"Todo not found\"}");
                    return;
                }
                send(exchange, 204, "");
            }
            default -> sendJson(exchange, 405, "{\"message\":\"Method not allowed\"}");
        }
    }

    private static TodoPayload parsePayload(String json) {
        String title = extractString(json, "title").orElse(null);
        Boolean completed = extractBoolean(json, "completed").orElse(null);

        if (title != null) {
            title = title.trim();
            if (title.isBlank()) {
                throw new IllegalArgumentException("Title cannot be blank");
            }
        }

        return new TodoPayload(title, completed);
    }

    private static Optional<String> extractString(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            return Optional.empty();
        }

        int colonIndex = json.indexOf(':', keyIndex + key.length());
        if (colonIndex < 0) {
            throw new IllegalArgumentException("Invalid JSON payload");
        }

        int valueStart = skipWhitespace(json, colonIndex + 1);
        if (valueStart >= json.length() || json.charAt(valueStart) != '"') {
            throw new IllegalArgumentException(fieldName + " must be a string");
        }

        StringBuilder value = new StringBuilder();
        boolean escaping = false;
        for (int index = valueStart + 1; index < json.length(); index++) {
            char current = json.charAt(index);
            if (escaping) {
                value.append(switch (current) {
                    case '"', '\\', '/' -> current;
                    case 'b' -> '\b';
                    case 'f' -> '\f';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    case 't' -> '\t';
                    default -> current;
                });
                escaping = false;
                continue;
            }

            if (current == '\\') {
                escaping = true;
                continue;
            }

            if (current == '"') {
                return Optional.of(value.toString());
            }

            value.append(current);
        }

        throw new IllegalArgumentException("Invalid JSON string");
    }

    private static Optional<Integer> extractInteger(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            return Optional.empty();
        }

        int colonIndex = json.indexOf(':', keyIndex + key.length());
        if (colonIndex < 0) {
            throw new IllegalArgumentException("Invalid JSON payload");
        }

        int valueStart = skipWhitespace(json, colonIndex + 1);
        int valueEnd = valueStart;
        while (valueEnd < json.length() && Character.isDigit(json.charAt(valueEnd))) {
            valueEnd++;
        }

        if (valueEnd == valueStart) {
            throw new IllegalArgumentException(fieldName + " must be a number");
        }

        return Optional.of(Integer.parseInt(json.substring(valueStart, valueEnd)));
    }

    private static Optional<Boolean> extractBoolean(String json, String fieldName) {
        String key = "\"" + fieldName + "\"";
        int keyIndex = json.indexOf(key);
        if (keyIndex < 0) {
            return Optional.empty();
        }

        int colonIndex = json.indexOf(':', keyIndex + key.length());
        if (colonIndex < 0) {
            throw new IllegalArgumentException("Invalid JSON payload");
        }

        int valueStart = skipWhitespace(json, colonIndex + 1);
        if (json.startsWith("true", valueStart)) {
            return Optional.of(true);
        }
        if (json.startsWith("false", valueStart)) {
            return Optional.of(false);
        }

        throw new IllegalArgumentException(fieldName + " must be a boolean");
    }

    private static int skipWhitespace(String value, int start) {
        int index = start;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream inputStream = exchange.getRequestBody()) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, statusCode, body);
    }

    private static void send(HttpExchange exchange, int statusCode, String body) throws IOException {
        if (statusCode == 204) {
            exchange.sendResponseHeaders(statusCode, -1);
            return;
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(bytes);
        }
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", CORS_ALLOWED_ORIGIN);
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static String toJson(List<Todo> todos) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < todos.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(todos.get(index).toJson());
        }
        return builder.append(']').toString();
    }

    private static String escapeJson(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static List<String> splitJsonObjects(String arrayJson) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int objectStart = -1;
        boolean inString = false;
        boolean escaping = false;

        for (int index = 0; index < arrayJson.length(); index++) {
            char current = arrayJson.charAt(index);

            if (inString) {
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }

            if (current == '"') {
                inString = true;
                continue;
            }

            if (current == '{') {
                if (depth == 0) {
                    objectStart = index;
                }
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    objects.add(arrayJson.substring(objectStart, index + 1));
                    objectStart = -1;
                }
            }
        }

        return objects;
    }

    private static Todo parseTodo(String json) {
        int id = extractInteger(json, "id").orElseThrow(() -> new IllegalArgumentException("Missing todo id"));
        String title = extractString(json, "title").orElseThrow(() -> new IllegalArgumentException("Missing todo title"));
        boolean completed = extractBoolean(json, "completed").orElse(false);
        String createdAt = extractString(json, "created_at")
            .or(() -> extractString(json, "createdAt"))
            .orElse(Instant.now().toString());

        return new Todo(id, title, completed, createdAt);
    }

    private record TodoPayload(String title, Boolean completed) {
    }

    private record Todo(int id, String title, boolean completed, String createdAt) {
        private String toJson() {
            return "{"
                + "\"id\":" + id + ","
                + "\"title\":\"" + escapeJson(title) + "\","
                + "\"completed\":" + completed + ","
                + "\"createdAt\":\"" + createdAt + "\""
                + "}";
        }
    }

    private interface TodoRepository {
        String name();

        List<Todo> findAll();

        Todo create(String title);

        Optional<Todo> update(int id, String title, Boolean completed);

        boolean delete(int id);
    }

    private static final class InMemoryTodoRepository implements TodoRepository {
        private final AtomicInteger nextId = new AtomicInteger(1);
        private final List<Todo> todos = new ArrayList<>();

        private InMemoryTodoRepository() {
            create("Learn Vue 3 with TypeScript");
            create("Connect the Java REST API");
        }

        @Override
        public String name() {
            return "in-memory";
        }

        @Override
        public synchronized List<Todo> findAll() {
            return List.copyOf(todos);
        }

        @Override
        public synchronized Todo create(String title) {
            if (isBlank(title)) {
                throw new IllegalArgumentException("Title cannot be blank");
            }

            Todo todo = new Todo(nextId.getAndIncrement(), title.trim(), false, Instant.now().toString());
            todos.add(todo);
            return todo;
        }

        @Override
        public synchronized Optional<Todo> update(int id, String title, Boolean completed) {
            for (int index = 0; index < todos.size(); index++) {
                Todo current = todos.get(index);
                if (current.id() != id) {
                    continue;
                }

                Todo updated = new Todo(
                    current.id(),
                    title == null ? current.title() : title,
                    completed == null ? current.completed() : completed,
                    current.createdAt()
                );
                todos.set(index, updated);
                return Optional.of(updated);
            }
            return Optional.empty();
        }

        @Override
        public synchronized boolean delete(int id) {
            return todos.removeIf(todo -> todo.id() == id);
        }
    }

    private static final class SupabaseTodoRepository implements TodoRepository {
        private final HttpClient httpClient = HttpClient.newHttpClient();
        private final String apiKey;
        private final String todosEndpoint;

        private SupabaseTodoRepository(String supabaseUrl, String apiKey) {
            this.apiKey = apiKey;
            this.todosEndpoint = supabaseUrl.replaceAll("/+$", "") + "/rest/v1/todos";
        }

        @Override
        public String name() {
            return "supabase";
        }

        @Override
        public List<Todo> findAll() {
            HttpRequest request = baseRequest(URI.create(todosEndpoint + "?select=id,title,completed,created_at&order=id.asc"))
                .GET()
                .build();

            return parseTodoArray(sendSupabaseRequest(request, 200));
        }

        @Override
        public Todo create(String title) {
            if (isBlank(title)) {
                throw new IllegalArgumentException("Title cannot be blank");
            }

            String body = "{"
                + "\"title\":\"" + escapeJson(title.trim()) + "\","
                + "\"completed\":false"
                + "}";

            HttpRequest request = baseRequest(URI.create(todosEndpoint))
                .header("Content-Type", "application/json")
                .header("Prefer", "return=representation")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

            return firstTodo(sendSupabaseRequest(request, 201));
        }

        @Override
        public Optional<Todo> update(int id, String title, Boolean completed) {
            List<String> fields = new ArrayList<>();
            if (title != null) {
                fields.add("\"title\":\"" + escapeJson(title) + "\"");
            }
            if (completed != null) {
                fields.add("\"completed\":" + completed);
            }
            if (fields.isEmpty()) {
                return findById(id);
            }

            HttpRequest request = baseRequest(URI.create(todosEndpoint + "?id=eq." + id))
                .header("Content-Type", "application/json")
                .header("Prefer", "return=representation")
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{" + String.join(",", fields) + "}"))
                .build();

            List<Todo> updated = parseTodoArray(sendSupabaseRequest(request, 200));
            return updated.stream().findFirst();
        }

        @Override
        public boolean delete(int id) {
            HttpRequest request = baseRequest(URI.create(todosEndpoint + "?id=eq." + id))
                .header("Prefer", "return=representation")
                .DELETE()
                .build();

            return !parseTodoArray(sendSupabaseRequest(request, 200)).isEmpty();
        }

        private Optional<Todo> findById(int id) {
            HttpRequest request = baseRequest(URI.create(todosEndpoint + "?id=eq." + id + "&select=id,title,completed,created_at"))
                .GET()
                .build();

            return parseTodoArray(sendSupabaseRequest(request, 200)).stream().findFirst();
        }

        private HttpRequest.Builder baseRequest(URI uri) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .header("Accept", "application/json")
                .header("apikey", apiKey);

            if (apiKey.startsWith("eyJ")) {
                builder.header("Authorization", "Bearer " + apiKey);
            }

            return builder;
        }

        private String sendSupabaseRequest(HttpRequest request, int expectedStatus) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != expectedStatus) {
                    throw new IllegalStateException(
                        "Supabase request failed with status " + response.statusCode() + ": " + response.body()
                    );
                }
                return response.body();
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot connect to Supabase", exception);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Supabase request was interrupted", exception);
            }
        }

        private Todo firstTodo(String arrayJson) {
            return parseTodoArray(arrayJson).stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Supabase did not return a todo"));
        }

        private List<Todo> parseTodoArray(String arrayJson) {
            return splitJsonObjects(arrayJson).stream()
                .map(Main::parseTodo)
                .toList();
        }
    }
}
