package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SmsApplication {

    private static final int DEFAULT_PORT = 8080;
    private static final String STATIC_ROOT = "/static";
    private static final String DB_URL = envOrDefault("SMS_DB_URL", "jdbc:mysql://localhost:3306/sms_db?serverTimezone=UTC");
    private static final String DB_USER = envOrDefault("SMS_DB_USER", "root");
    private static final String DB_PASSWORD = envOrDefault("SMS_DB_PASSWORD", "1234");
    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();

    static {
        CONTENT_TYPES.put("html", "text/html; charset=UTF-8");
        CONTENT_TYPES.put("css", "text/css; charset=UTF-8");
        CONTENT_TYPES.put("js", "application/javascript; charset=UTF-8");
        CONTENT_TYPES.put("png", "image/png");
        CONTENT_TYPES.put("jpg", "image/jpeg");
        CONTENT_TYPES.put("jpeg", "image/jpeg");
        CONTENT_TYPES.put("svg", "image/svg+xml");
        CONTENT_TYPES.put("ico", "image/x-icon");
    }

    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", SmsApplication::handleRequest);
        server.setExecutor(null);
        server.start();

        System.out.println("Union Security server running at http://localhost:" + port);
        System.out.println("Database URL: " + DB_URL);
    }

    private static void handleRequest(HttpExchange exchange) throws IOException {
        addCorsHeaders(exchange);

        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(204, -1);
            return;
        }

        String path = normalizePath(exchange.getRequestURI());

        if (path.startsWith("/api/")) {
            handleApiRequest(exchange, path);
            return;
        }

        if ("/".equals(path)) {
            path = "/index.html";
        }

        serveStaticResource(exchange, path);
    }

    private static void handleApiRequest(HttpExchange exchange, String path) throws IOException {
        try {
            switch (path) {
                case "/api/login":
                    requireMethod(exchange, "POST");
                    handleLogin(exchange);
                    break;
                case "/api/visitors":
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleVisitorsList(exchange);
                    } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleCreateVisitor(exchange);
                    } else {
                        sendMethodNotAllowed(exchange);
                    }
                    break;
                case "/api/employees":
                    requireMethod(exchange, "GET");
                    handleEmployeesList(exchange);
                    break;
                case "/api/access-logs":
                    requireMethod(exchange, "GET");
                    handleAccessLogsList(exchange);
                    break;
                case "/api/access-logs/entry":
                    requireMethod(exchange, "POST");
                    handleRecordEntry(exchange);
                    break;
                case "/api/access-logs/exit":
                    requireMethod(exchange, "POST");
                    handleRecordExit(exchange);
                    break;
                case "/api/incidents":
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleIncidentsList(exchange);
                    } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleCreateIncident(exchange);
                    } else {
                        sendMethodNotAllowed(exchange);
                    }
                    break;
                default:
                    sendJson(exchange, 404, "{\"message\":\"Endpoint not found\"}");
                    break;
            }
        } catch (IllegalStateException exception) {
            sendJson(exchange, 405, "{\"message\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (SQLException exception) {
            exception.printStackTrace();
            sendJson(exchange, 500, "{\"message\":\"Database error\",\"detail\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (Exception exception) {
            exception.printStackTrace();
            sendJson(exchange, 500, "{\"message\":\"Server error\",\"detail\":\"" + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    private static void handleLogin(HttpExchange exchange) throws IOException, SQLException {
        Map<String, String> form = parseFormBody(exchange);
        String username = trim(form.get("username"));
        String password = trim(form.get("password"));

        if (username.isEmpty() || password.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"All fields are required\"}");
            return;
        }

        String sql = "SELECT user_id, username, role FROM users WHERE username = ? AND password = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            statement.setString(2, password);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    sendJson(exchange, 401, "{\"message\":\"Invalid login credentials\"}");
                    return;
                }

                String response = "{"
                    + "\"userId\":" + resultSet.getInt("user_id") + ","
                    + "\"username\":\"" + escapeJson(resultSet.getString("username")) + "\","
                    + "\"role\":\"" + escapeJson(defaultString(resultSet.getString("role"), "Security")) + "\""
                    + "}";
                sendJson(exchange, 200, response);
            }
        }
    }

    private static void handleVisitorsList(HttpExchange exchange) throws IOException, SQLException {
        String sql = "SELECT visitor_id, name, national_id, phone_number, purpose_of_visit FROM visitors ORDER BY visitor_id DESC";
        List<String> rows = new ArrayList<>();

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add("{"
                    + "\"id\":" + resultSet.getInt("visitor_id") + ","
                    + "\"name\":\"" + escapeJson(resultSet.getString("name")) + "\","
                    + "\"nationalId\":\"" + escapeJson(resultSet.getString("national_id")) + "\","
                    + "\"phoneNumber\":\"" + escapeJson(resultSet.getString("phone_number")) + "\","
                    + "\"purposeOfVisit\":\"" + escapeJson(resultSet.getString("purpose_of_visit")) + "\""
                    + "}");
            }
        }

        sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    private static void handleCreateVisitor(HttpExchange exchange) throws IOException, SQLException {
        Map<String, String> form = parseFormBody(exchange);
        String name = trim(form.get("name"));
        String nationalId = trim(form.get("nationalId"));
        String phoneNumber = trim(form.get("phoneNumber"));
        String purposeOfVisit = trim(form.get("purposeOfVisit"));

        if (name.isEmpty() || nationalId.isEmpty() || phoneNumber.isEmpty() || purposeOfVisit.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"All visitor fields are required\"}");
            return;
        }

        try (Connection connection = getConnection()) {
            if (visitorExists(connection, nationalId)) {
                sendJson(exchange, 409, "{\"message\":\"Visitor already registered\"}");
                return;
            }

            String sql = "INSERT INTO visitors (name, national_id, phone_number, purpose_of_visit) VALUES (?, ?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, name);
                statement.setString(2, nationalId);
                statement.setString(3, phoneNumber);
                statement.setString(4, purposeOfVisit);
                statement.executeUpdate();
            }
        }

        sendJson(exchange, 201, "{\"message\":\"Visitor registered successfully\"}");
    }

    private static void handleEmployeesList(HttpExchange exchange) throws IOException, SQLException {
        String sql = "SELECT employee_id, name, department, phone_number FROM employees ORDER BY name ASC";
        List<String> rows = new ArrayList<>();

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add("{"
                    + "\"id\":" + resultSet.getInt("employee_id") + ","
                    + "\"name\":\"" + escapeJson(resultSet.getString("name")) + "\","
                    + "\"department\":\"" + escapeJson(resultSet.getString("department")) + "\","
                    + "\"phoneNumber\":\"" + escapeJson(defaultString(resultSet.getString("phone_number"), "")) + "\""
                    + "}");
            }
        }

        sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    private static void handleAccessLogsList(HttpExchange exchange) throws IOException, SQLException {
        String sql = ""
            + "SELECT al.log_id, al.visitor_id, al.employee_id, al.user_id, al.visit_date, al.entry_time, al.exit_time, "
            + "v.name AS visitor_name, e.name AS employee_name, e.department, u.username "
            + "FROM access_logs al "
            + "JOIN visitors v ON al.visitor_id = v.visitor_id "
            + "JOIN employees e ON al.employee_id = e.employee_id "
            + "JOIN users u ON al.user_id = u.user_id "
            + "ORDER BY al.log_id DESC";

        List<String> rows = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String exitTime = resultSet.getTime("exit_time") == null ? "" : resultSet.getTime("exit_time").toString();
                rows.add("{"
                    + "\"id\":" + resultSet.getInt("log_id") + ","
                    + "\"visitorId\":" + resultSet.getInt("visitor_id") + ","
                    + "\"employeeId\":" + resultSet.getInt("employee_id") + ","
                    + "\"userId\":" + resultSet.getInt("user_id") + ","
                    + "\"date\":\"" + resultSet.getDate("visit_date") + "\","
                    + "\"entryTime\":\"" + resultSet.getTime("entry_time") + "\","
                    + "\"exitTime\":\"" + escapeJson(exitTime) + "\","
                    + "\"visitorName\":\"" + escapeJson(resultSet.getString("visitor_name")) + "\","
                    + "\"host\":\"" + escapeJson(resultSet.getString("employee_name") + " (" + resultSet.getString("department") + ")") + "\","
                    + "\"recordedBy\":\"" + escapeJson(resultSet.getString("username")) + "\""
                    + "}");
            }
        }

        sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    private static void handleRecordEntry(HttpExchange exchange) throws IOException, SQLException {
        Map<String, String> form = parseFormBody(exchange);
        String visitorIdRaw = trim(form.get("visitorId"));
        String employeeIdRaw = trim(form.get("employeeId"));
        String userIdRaw = trim(form.get("userId"));

        if (visitorIdRaw.isEmpty() || employeeIdRaw.isEmpty() || userIdRaw.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"Missing required information\"}");
            return;
        }

        int visitorId = Integer.parseInt(visitorIdRaw);
        int employeeId = Integer.parseInt(employeeIdRaw);
        int userId = Integer.parseInt(userIdRaw);

        try (Connection connection = getConnection()) {
            if (hasActiveAccessLog(connection, visitorId)) {
                sendJson(exchange, 409, "{\"message\":\"Visitor already has an active access record\"}");
                return;
            }

            String sql = "INSERT INTO access_logs (visitor_id, employee_id, user_id, visit_date, entry_time, exit_time) VALUES (?, ?, ?, ?, ?, NULL)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, visitorId);
                statement.setInt(2, employeeId);
                statement.setInt(3, userId);
                statement.setDate(4, Date.valueOf(LocalDate.now()));
                statement.setTime(5, Time.valueOf(LocalTime.now().withSecond(0).withNano(0)));
                statement.executeUpdate();
            }
        }

        sendJson(exchange, 201, "{\"message\":\"Entry recorded successfully\"}");
    }

    private static void handleRecordExit(HttpExchange exchange) throws IOException, SQLException {
        Map<String, String> form = parseFormBody(exchange);
        String visitorIdRaw = trim(form.get("visitorId"));

        if (visitorIdRaw.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"Visitor is required\"}");
            return;
        }

        int visitorId = Integer.parseInt(visitorIdRaw);
        String sql = "UPDATE access_logs SET exit_time = ? WHERE visitor_id = ? AND exit_time IS NULL ORDER BY log_id DESC LIMIT 1";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTime(1, Time.valueOf(LocalTime.now().withSecond(0).withNano(0)));
            statement.setInt(2, visitorId);
            int updated = statement.executeUpdate();

            if (updated == 0) {
                sendJson(exchange, 404, "{\"message\":\"No active record found\"}");
                return;
            }
        }

        sendJson(exchange, 200, "{\"message\":\"Exit recorded successfully\"}");
    }

    private static void handleIncidentsList(HttpExchange exchange) throws IOException, SQLException {
        String sql = ""
            + "SELECT i.incident_id, i.title, i.description, i.severity, i.reported_at, u.user_id, u.username "
            + "FROM incidents i "
            + "JOIN users u ON i.user_id = u.user_id "
            + "ORDER BY i.incident_id DESC";

        List<String> rows = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add("{"
                    + "\"id\":" + resultSet.getInt("incident_id") + ","
                    + "\"title\":\"" + escapeJson(resultSet.getString("title")) + "\","
                    + "\"description\":\"" + escapeJson(resultSet.getString("description")) + "\","
                    + "\"severity\":\"" + escapeJson(resultSet.getString("severity")) + "\","
                    + "\"date\":\"" + resultSet.getTimestamp("reported_at").toLocalDateTime().toLocalDate() + "\","
                    + "\"reportedBy\":\"" + escapeJson(resultSet.getString("username")) + "\","
                    + "\"userId\":" + resultSet.getInt("user_id")
                    + "}");
            }
        }

        sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    private static void handleCreateIncident(HttpExchange exchange) throws IOException, SQLException {
        Map<String, String> form = parseFormBody(exchange);
        String title = trim(form.get("title"));
        String severity = trim(form.get("severity"));
        String description = trim(form.get("description"));
        String userIdRaw = trim(form.get("userId"));

        if (title.isEmpty() || severity.isEmpty() || description.isEmpty() || userIdRaw.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"All incident fields are required\"}");
            return;
        }

        int userId = Integer.parseInt(userIdRaw);
        String sql = "INSERT INTO incidents (title, description, severity, user_id, reported_at) VALUES (?, ?, ?, ?, ?)";

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, title);
            statement.setString(2, description);
            statement.setString(3, severity);
            statement.setInt(4, userId);
            statement.setTimestamp(5, Timestamp.valueOf(java.time.LocalDateTime.now().withNano(0)));
            statement.executeUpdate();
        }

        sendJson(exchange, 201, "{\"message\":\"Incident report saved successfully\"}");
    }

    private static boolean visitorExists(Connection connection, String nationalId) throws SQLException {
        String sql = "SELECT 1 FROM visitors WHERE national_id = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nationalId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static boolean hasActiveAccessLog(Connection connection, int visitorId) throws SQLException {
        String sql = "SELECT 1 FROM access_logs WHERE visitor_id = ? AND exit_time IS NULL LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, visitorId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static void serveStaticResource(HttpExchange exchange, String path) throws IOException {
        String resourcePath = STATIC_ROOT + path;

        try (InputStream inputStream = SmsApplication.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                sendText(exchange, 404, "Page not found");
                return;
            }

            byte[] content = inputStream.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentTypeFor(path));
            exchange.sendResponseHeaders(200, content.length);

            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(content);
            }
        }
    }

    private static Map<String, String> parseFormBody(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> values = new LinkedHashMap<>();

        if (body.isBlank()) {
            return values;
        }

        String[] pairs = body.split("&");
        for (String pair : pairs) {
            if (pair.isBlank()) {
                continue;
            }

            String[] entry = pair.split("=", 2);
            String key = decode(entry[0]);
            String value = entry.length > 1 ? decode(entry[1]) : "";
            values.put(key, value);
        }

        return values;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    private static void requireMethod(HttpExchange exchange, String expectedMethod) {
        if (!expectedMethod.equalsIgnoreCase(exchange.getRequestMethod())) {
            throw new IllegalStateException("Method not allowed");
        }
    }

    private static void sendMethodNotAllowed(HttpExchange exchange) throws IOException {
        sendJson(exchange, 405, "{\"message\":\"Method not allowed\"}");
    }

    private static void addCorsHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private static String normalizePath(URI uri) {
        String path = uri.getPath();

        if (path == null || path.isBlank()) {
            return "/";
        }

        if (path.contains("..")) {
            return "/";
        }

        return path;
    }

    private static String contentTypeFor(String path) {
        int extensionIndex = path.lastIndexOf('.') + 1;
        if (extensionIndex <= 0 || extensionIndex >= path.length()) {
            return "application/octet-stream";
        }

        String extension = path.substring(extensionIndex).toLowerCase();
        return CONTENT_TYPES.getOrDefault(extension, "application/octet-stream");
    }

    private static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, payload.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        }
    }

    private static void sendText(HttpExchange exchange, int statusCode, String message) throws IOException {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, payload.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        }
    }

    private static int resolvePort(String[] args) {
        if (args.length > 0) {
            return parsePort(args[0]);
        }

        String envPort = System.getenv("PORT");
        if (envPort != null && !envPort.isBlank()) {
            return parsePort(envPort);
        }

        return DEFAULT_PORT;
    }

    private static int parsePort(String value) {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("Port must be between 1 and 65535.");
            }
            return port;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Port must be a number.", exception);
        }
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (char character : value.toCharArray()) {
            switch (character) {
                case '\\':
                    builder.append("\\\\");
                    break;
                case '"':
                    builder.append("\\\"");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    builder.append(character);
                    break;
            }
        }
        return builder.toString();
    }
}
