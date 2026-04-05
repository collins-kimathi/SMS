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
import java.nio.file.Files;
import java.nio.file.Path;
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

    // Core application settings, security constants, and required database configuration.
    private static final int DEFAULT_PORT = 8080;
    private static final String STATIC_ROOT = "/static";
    private static final Path SOURCE_STATIC_DIR = Path.of("src", "main", "resources", "static");
    private static final String SESSION_COOKIE = "sms_session";
    private static final long SESSION_TTL_MILLIS = 8L * 60L * 60L * 1000L;
    private static final String DB_URL = requireEnv("SMS_DB_URL");
    private static final String DB_USER = requireEnv("SMS_DB_USER");
    private static final String DB_PASSWORD = requireEnv("SMS_DB_PASSWORD");
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

    // Application entry point: verify startup state, then start the built-in HTTP server.
    public static void main(String[] args) throws IOException {
        int port = resolvePort(args);
        initializeDatabaseState();
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", SmsApplication::handleRequest);
        server.setExecutor(null);
        server.start();

        System.out.println("Union Security server running at http://localhost:" + port);
        System.out.println("Database URL: " + DB_URL);
    }

    // First routing layer: decide between API traffic and static frontend files.
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

    // API router: map each /api path to a dedicated handler method below.
    private static void handleApiRequest(HttpExchange exchange, String path) throws IOException {
        try {
            switch (path) {
                case "/api/login":
                    requireMethod(exchange, "POST");
                    AuthController.handleLogin(exchange);
                    break;
                case "/api/session":
                    requireMethod(exchange, "GET");
                    AuthController.handleSession(exchange);
                    break;
                case "/api/logout":
                    requireMethod(exchange, "POST");
                    AuthController.handleLogout(exchange);
                    break;
                case "/api/visitors":
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        VisitorController.handleList(exchange);
                    } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        VisitorController.handleCreate(exchange);
                    } else {
                        sendMethodNotAllowed(exchange);
                    }
                    break;
                case "/api/visitors/update":
                    requireMethod(exchange, "POST");
                    VisitorController.handleUpdate(exchange);
                    break;
                case "/api/visitors/delete":
                    requireMethod(exchange, "POST");
                    VisitorController.handleDelete(exchange);
                    break;
                case "/api/employees":
                    requireMethod(exchange, "GET");
                    EmployeeController.handleList(exchange);
                    break;
                case "/api/employees/create":
                    requireMethod(exchange, "POST");
                    EmployeeController.handleCreate(exchange);
                    break;
                case "/api/employees/update":
                    requireMethod(exchange, "POST");
                    EmployeeController.handleUpdate(exchange);
                    break;
                case "/api/employees/delete":
                    requireMethod(exchange, "POST");
                    EmployeeController.handleDelete(exchange);
                    break;
                case "/api/access-logs":
                    requireMethod(exchange, "GET");
                    AccessController.handleList(exchange);
                    break;
                case "/api/access-logs/entry":
                    requireMethod(exchange, "POST");
                    AccessController.handleRecordEntry(exchange);
                    break;
                case "/api/access-logs/exit":
                    requireMethod(exchange, "POST");
                    AccessController.handleRecordExit(exchange);
                    break;
                case "/api/access-logs/update":
                    requireMethod(exchange, "POST");
                    AccessController.handleUpdate(exchange);
                    break;
                case "/api/access-logs/delete":
                    requireMethod(exchange, "POST");
                    AccessController.handleDelete(exchange);
                    break;
                case "/api/incidents":
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        IncidentController.handleList(exchange);
                    } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        IncidentController.handleCreate(exchange);
                    } else {
                        sendMethodNotAllowed(exchange);
                    }
                    break;
                case "/api/incidents/update":
                    requireMethod(exchange, "POST");
                    IncidentController.handleUpdate(exchange);
                    break;
                case "/api/users":
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        UserController.handleList(exchange);
                    } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        UserController.handleCreate(exchange);
                    } else {
                        sendMethodNotAllowed(exchange);  
                    }
                    break;
                case "/api/users/update":
                    requireMethod(exchange, "POST");
                    UserController.handleUpdate(exchange);
                    break;
                case "/api/users/delete":
                    requireMethod(exchange, "POST");
                    UserController.handleDelete(exchange);
                    break;
                case "/api/reports/access":
                    requireMethod(exchange, "GET");
                    ReportController.handleAccessReport(exchange);
                    break;
                case "/api/reports/incidents":
                    requireMethod(exchange, "GET");
                    ReportController.handleIncidentReport(exchange);
                    break;
                case "/api/reports/visitors":
                    requireMethod(exchange, "GET");
                    ReportController.handleVisitorReport(exchange);
                    break;
                case "/api/reports/audit":
                    requireMethod(exchange, "GET");
                    ReportController.handleAuditReport(exchange);
                    break;
                case "/api/reports/export":
                    requireMethod(exchange, "GET");
                    ReportController.handleExport(exchange);
                    break;
                default:
                    sendJson(exchange, 404, "{\"message\":\"Endpoint not found\"}");
                    break;
            }
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, "{\"message\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (IllegalStateException exception) {
            sendJson(exchange, 405, "{\"message\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (UnauthorizedException exception) {
            sendJson(exchange, 401, "{\"message\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (SecurityException exception) {
            sendJson(exchange, 403, "{\"message\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (SQLException exception) {
            exception.printStackTrace();
            sendJson(exchange, 500, "{\"message\":\"Database error\",\"detail\":\"" + escapeJson(exception.getMessage()) + "\"}");
        } catch (Exception exception) {
            exception.printStackTrace();
            sendJson(exchange, 500, "{\"message\":\"Server error\",\"detail\":\"" + escapeJson(exception.getMessage()) + "\"}");
        }
    }

    // Endpoint implementations now live in feature-specific controller classes.

    // ---------------------------------------------------------------------
    // Shared validation, authorization, and audit helpers
    // ---------------------------------------------------------------------
    static boolean visitorExists(Connection connection, String nationalId) throws SQLException {
        String sql = "SELECT 1 FROM visitors WHERE national_id = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nationalId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    static boolean visitorExists(Connection connection, String nationalId, int excludedVisitorId) throws SQLException {
        String sql = "SELECT 1 FROM visitors WHERE national_id = ? AND visitor_id <> ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nationalId);
            statement.setInt(2, excludedVisitorId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    static boolean hasActiveAccessLog(Connection connection, int visitorId) throws SQLException {
        String sql = "SELECT 1 FROM access_logs WHERE visitor_id = ? AND exit_time IS NULL LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, visitorId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    static void recordAuditAction(Connection connection, Integer userId, String actionType, String entityType, String entityId, String details) throws SQLException {
        String sql = "INSERT INTO audit_logs (user_id, username, action_type, entity_type, entity_id, details, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (userId == null) {
                statement.setNull(1, java.sql.Types.INTEGER);
                statement.setString(2, "System");
            } else {
                statement.setInt(1, userId);
                statement.setString(2, resolveUsername(connection, userId));
            }
            statement.setString(3, actionType);
            statement.setString(4, entityType);
            statement.setString(5, entityId);
            statement.setString(6, details);
            statement.setTimestamp(7, Timestamp.valueOf(java.time.LocalDateTime.now().withNano(0)));
            statement.executeUpdate();
        }
    }

    static String resolveUsername(Connection connection, int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT username FROM users WHERE user_id = ?")) {
            statement.setInt(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("username") : "Unknown";
            }
        }
    }

    static void requireSecurityOfficer(SessionInfo session) {
        if (!isSecurityRole(session.role)) {
            throw new SecurityException("Only Security Officers can perform this action");
        }
    }

    static void requireAdmin(SessionInfo session) {
        if (!isAdminRole(session.role)) {
            throw new SecurityException("Only Admin users can perform this action");
        }
    }

    static void requireIncidentManager(SessionInfo session) {
        if (!isAdminRole(session.role) && !isSecurityRole(session.role)) {
            throw new SecurityException("Only authorized staff can update incidents");
        }
    }

    static boolean isSecurityRole(String role) {
        String normalizedRole = defaultString(role, "").toLowerCase().replace(" ", "");
        return "security".equals(normalizedRole) || "securityofficer".equals(normalizedRole);
    }

    static boolean isAdminRole(String role) {
        return "admin".equals(defaultString(role, "").toLowerCase().replace(" ", ""));
    }

    static boolean usernameExists(Connection connection, String username, Integer excludedUserId) throws SQLException {
        String sql = excludedUserId == null
            ? "SELECT 1 FROM users WHERE username = ? LIMIT 1"
            : "SELECT 1 FROM users WHERE username = ? AND user_id <> ? LIMIT 1";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            if (excludedUserId != null) {
                statement.setInt(2, excludedUserId);
            }

            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    static int parseRequiredInt(String value, String message) {
        String trimmedValue = trim(value);
        if (trimmedValue.isEmpty()) {
            throw new IllegalArgumentException(message);
        }

        try {
            return Integer.parseInt(trimmedValue);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(message, exception);
        }
    }

    static String normalizeTime(String value) {
        String trimmedValue = trim(value);
        if (trimmedValue.matches("^\\d{2}:\\d{2}$")) {
            return trimmedValue + ":00";
        }
        return trimmedValue;
    }

    // ---------------------------------------------------------------------
    // Startup database preparation and compatibility checks
    // ---------------------------------------------------------------------
    private static void initializeDatabaseState() throws IOException {
        DatabaseBootstrap.initialize(DB_URL, DB_USER, DB_PASSWORD);
    }

    // ---------------------------------------------------------------------
    // HTTP parsing, session lookup, and response helpers
    // ---------------------------------------------------------------------
    private static void serveStaticResource(HttpExchange exchange, String path) throws IOException {
        Path sourcePath = SOURCE_STATIC_DIR.resolve(path.startsWith("/") ? path.substring(1) : path).normalize();
        if (sourcePath.startsWith(SOURCE_STATIC_DIR) && Files.isRegularFile(sourcePath)) {
            byte[] content = Files.readAllBytes(sourcePath);
            exchange.getResponseHeaders().set("Content-Type", contentTypeFor(path));
            exchange.sendResponseHeaders(200, content.length);

            try (OutputStream outputStream = exchange.getResponseBody()) {
                outputStream.write(content);
            }
            return;
        }

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

    static Map<String, String> parseFormBody(HttpExchange exchange) throws IOException {
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

    static Map<String, String> parseQuery(URI uri) {
        Map<String, String> values = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null || query.isBlank()) {
            return values;
        }

        String[] pairs = query.split("&");
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

    static SessionInfo requireAuthenticated(HttpExchange exchange) {
        return SessionManager.requireAuthenticated(exchange, DB_URL, DB_USER, DB_PASSWORD, SESSION_COOKIE);
    }

    static SessionInfo getOptionalSession(HttpExchange exchange) {
        return SessionManager.getOptionalSession(exchange, DB_URL, DB_USER, DB_PASSWORD, SESSION_COOKIE);
    }

    static String sessionToJson(SessionInfo session) {
        return "{"
            + "\"userId\":" + session.userId + ","
            + "\"username\":\"" + escapeJson(session.username) + "\","
            + "\"role\":\"" + escapeJson(session.role) + "\""
            + "}";
    }

    static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
    }

    static String dbUrl() {
        return DB_URL;
    }

    static String dbUser() {
        return DB_USER;
    }

    static String dbPassword() {
        return DB_PASSWORD;
    }

    static String sessionCookieName() {
        return SESSION_COOKIE;
    }

    static long sessionTtlMillis() {
        return SESSION_TTL_MILLIS;
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

    static void sendJson(HttpExchange exchange, int statusCode, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(statusCode, payload.length);

        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        }
    }

    static void sendText(HttpExchange exchange, int statusCode, String message) throws IOException {
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

    private static String requireEnv(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key);
        }
        return value;
    }

    static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }

    // This app writes JSON manually, so values must be escaped before they are inserted into responses.
    static String escapeJson(String value) {
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
