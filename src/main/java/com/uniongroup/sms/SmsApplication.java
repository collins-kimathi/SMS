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
import java.security.SecureRandom;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SmsApplication {

    private static final int DEFAULT_PORT = 8080;
    private static final String STATIC_ROOT = "/static";
    private static final String SESSION_COOKIE = "sms_session";
    private static final long SESSION_TTL_MILLIS = 8L * 60L * 60L * 1000L;
    private static final String DB_URL = envOrDefault("SMS_DB_URL", "jdbc:mysql://localhost:3306/sms_db?serverTimezone=UTC");
    private static final String DB_USER = envOrDefault("SMS_DB_USER", "root");
    private static final String DB_PASSWORD = envOrDefault("SMS_DB_PASSWORD", "1234");
    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();
    private static final Map<String, SessionInfo> SESSIONS = new ConcurrentHashMap<>();
    private static final SecureRandom SESSION_RANDOM = new SecureRandom();

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
        initializeDatabaseState();
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
                case "/api/session":
                    requireMethod(exchange, "GET");
                    handleSession(exchange);
                    break;
                case "/api/logout":
                    requireMethod(exchange, "POST");
                    handleLogout(exchange);
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
                case "/api/users":
                    if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleUsersList(exchange);
                    } else if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                        handleCreateUser(exchange);
                    } else {
                        sendMethodNotAllowed(exchange);  
                    }
                    break;
                case "/api/users/update":
                    requireMethod(exchange, "POST");
                    handleUpdateUser(exchange);
                    break;
                case "/api/users/delete":
                    requireMethod(exchange, "POST");
                    handleDeleteUser(exchange);
                    break;
                case "/api/reports/access":
                    requireMethod(exchange, "GET");
                    handleAccessReport(exchange);
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

                SessionInfo session = createSession(
                    resultSet.getInt("user_id"),
                    resultSet.getString("username"),
                    defaultString(resultSet.getString("role"), "Security")
                );
                setSessionCookie(exchange, session.sessionId);

                String response = "{"
                    + "\"userId\":" + resultSet.getInt("user_id") + ","
                    + "\"username\":\"" + escapeJson(resultSet.getString("username")) + "\","
                    + "\"role\":\"" + escapeJson(defaultString(resultSet.getString("role"), "Security")) + "\""
                    + "}";
                sendJson(exchange, 200, response);
            }
        }
    }

    private static void handleSession(HttpExchange exchange) throws IOException {
        SessionInfo session = requireAuthenticated(exchange);
        sendJson(exchange, 200, sessionToJson(session));
    }

    private static void handleLogout(HttpExchange exchange) throws IOException {
        SessionInfo session = getOptionalSession(exchange);
        if (session != null) {
            SESSIONS.remove(session.sessionId);
        }
        clearSessionCookie(exchange);
        sendJson(exchange, 200, "{\"message\":\"Logged out successfully\"}");
    }

    private static void handleVisitorsList(HttpExchange exchange) throws IOException, SQLException {
        requireAuthenticated(exchange);
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
        SessionInfo session = requireAuthenticated(exchange);
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
            requireSecurityOfficer(session);

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
        requireAuthenticated(exchange);
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
        requireAuthenticated(exchange);
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
        SessionInfo session = requireAuthenticated(exchange);
        Map<String, String> form = parseFormBody(exchange);
        int visitorId = parseRequiredInt(form.get("visitorId"), "Visitor is required");
        int employeeId = parseRequiredInt(form.get("employeeId"), "Employee is required");

        try (Connection connection = getConnection()) {
            requireSecurityOfficer(session);

            if (hasActiveAccessLog(connection, visitorId)) {
                sendJson(exchange, 409, "{\"message\":\"Visitor already has an active access record\"}");
                return;
            }

            String sql = "INSERT INTO access_logs (visitor_id, employee_id, user_id, visit_date, entry_time, exit_time) VALUES (?, ?, ?, ?, ?, NULL)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, visitorId);
                statement.setInt(2, employeeId);
                statement.setInt(3, session.userId);
                statement.setDate(4, Date.valueOf(LocalDate.now()));
                statement.setTime(5, Time.valueOf(LocalTime.now().withSecond(0).withNano(0)));
                statement.executeUpdate();
            }
        }

        sendJson(exchange, 201, "{\"message\":\"Entry recorded successfully\"}");
    }

    private static void handleRecordExit(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        Map<String, String> form = parseFormBody(exchange);
        int visitorId = parseRequiredInt(form.get("visitorId"), "Visitor is required");
        String sql = "UPDATE access_logs SET exit_time = ? WHERE visitor_id = ? AND exit_time IS NULL ORDER BY log_id DESC LIMIT 1";

        try (Connection connection = getConnection()) {
            requireSecurityOfficer(session);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTime(1, Time.valueOf(LocalTime.now().withSecond(0).withNano(0)));
                statement.setInt(2, visitorId);
                int updated = statement.executeUpdate();

                if (updated == 0) {
                    sendJson(exchange, 404, "{\"message\":\"No active record found\"}");
                    return;
                }
            }
        }

        sendJson(exchange, 200, "{\"message\":\"Exit recorded successfully\"}");
    }

    private static void handleIncidentsList(HttpExchange exchange) throws IOException, SQLException {
        requireAuthenticated(exchange);
        String sql = ""
            + "SELECT i.incident_id, i.title, i.incident_type, i.location, i.status, i.action_taken, "
            + "i.description, i.severity, i.reported_at, u.user_id, u.username "
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
                    + "\"incidentType\":\"" + escapeJson(resultSet.getString("incident_type")) + "\","
                    + "\"location\":\"" + escapeJson(resultSet.getString("location")) + "\","
                    + "\"status\":\"" + escapeJson(resultSet.getString("status")) + "\","
                    + "\"actionTaken\":\"" + escapeJson(defaultString(resultSet.getString("action_taken"), "")) + "\","
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
        SessionInfo session = requireAuthenticated(exchange);
        Map<String, String> form = parseFormBody(exchange);
        String title = trim(form.get("title"));
        String incidentType = trim(form.get("incidentType"));
        String location = trim(form.get("location"));
        String severity = trim(form.get("severity"));
        String status = trim(form.get("status"));
        String actionTaken = trim(form.get("actionTaken"));
        String description = trim(form.get("description"));

        if (title.isEmpty() || incidentType.isEmpty() || location.isEmpty() || severity.isEmpty() || status.isEmpty() || description.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"All incident fields are required\"}");
            return;
        }

        String sql = "INSERT INTO incidents (title, incident_type, location, status, action_taken, description, severity, user_id, reported_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection connection = getConnection()) {
            requireSecurityOfficer(session);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, title);
                statement.setString(2, incidentType);
                statement.setString(3, location);
                statement.setString(4, status);
                statement.setString(5, actionTaken);
                statement.setString(6, description);
                statement.setString(7, severity);
                statement.setInt(8, session.userId);
                statement.setTimestamp(9, Timestamp.valueOf(java.time.LocalDateTime.now().withNano(0)));
                statement.executeUpdate();
            }
        }

        sendJson(exchange, 201, "{\"message\":\"Incident report saved successfully\"}");
    }

    private static void handleUsersList(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        String sql = "SELECT user_id, username, role FROM users ORDER BY user_id ASC";
        List<String> rows = new ArrayList<>();

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            requireAdmin(session);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add("{"
                        + "\"userId\":" + resultSet.getInt("user_id") + ","
                        + "\"username\":\"" + escapeJson(resultSet.getString("username")) + "\","
                        + "\"role\":\"" + escapeJson(resultSet.getString("role")) + "\""
                        + "}");
                }
            }
        }

        sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    private static void handleCreateUser(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        Map<String, String> form = parseFormBody(exchange);
        String username = trim(form.get("username"));
        String password = trim(form.get("password"));
        String role = trim(form.get("role"));

        if (username.isEmpty() || password.isEmpty() || role.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"All user fields are required\"}");
            return;
        }

        try (Connection connection = getConnection()) {
            requireAdmin(session);

            if (usernameExists(connection, username, null)) {
                sendJson(exchange, 409, "{\"message\":\"Username already exists\"}");
                return;
            }

            String sql = "INSERT INTO users (username, password, role) VALUES (?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                statement.setString(2, password);
                statement.setString(3, role);
                statement.executeUpdate();
            }
        }

        sendJson(exchange, 201, "{\"message\":\"User created successfully\"}");
    }

    private static void handleUpdateUser(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        Map<String, String> form = parseFormBody(exchange);
        int targetUserId = parseRequiredInt(form.get("targetUserId"), "Target user is required");
        String username = trim(form.get("username"));
        String password = trim(form.get("password"));
        String role = trim(form.get("role"));

        if (username.isEmpty() || role.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"Username and role are required\"}");
            return;
        }

        try (Connection connection = getConnection()) {
            requireAdmin(session);

            if (usernameExists(connection, username, targetUserId)) {
                sendJson(exchange, 409, "{\"message\":\"Username already exists\"}");
                return;
            }

            String sql = password.isEmpty()
                ? "UPDATE users SET username = ?, role = ? WHERE user_id = ?"
                : "UPDATE users SET username = ?, password = ?, role = ? WHERE user_id = ?";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                if (password.isEmpty()) {
                    statement.setString(2, role);
                    statement.setInt(3, targetUserId);
                } else {
                    statement.setString(2, password);
                    statement.setString(3, role);
                    statement.setInt(4, targetUserId);
                }

                int updated = statement.executeUpdate();
                if (updated == 0) {
                    sendJson(exchange, 404, "{\"message\":\"User not found\"}");
                    return;
                }
            }
        }

        sendJson(exchange, 200, "{\"message\":\"User updated successfully\"}");
    }

    private static void handleDeleteUser(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        Map<String, String> form = parseFormBody(exchange);
        int targetUserId = parseRequiredInt(form.get("targetUserId"), "Target user is required");

        if (session.userId == targetUserId) {
            sendJson(exchange, 400, "{\"message\":\"You cannot delete the currently signed-in admin\"}");
            return;
        }

        try (Connection connection = getConnection()) {
            requireAdmin(session);

            String sql = "DELETE FROM users WHERE user_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, targetUserId);
                int deleted = statement.executeUpdate();
                if (deleted == 0) {
                    sendJson(exchange, 404, "{\"message\":\"User not found\"}");
                    return;
                }
            } catch (SQLException exception) {
                if (exception.getErrorCode() == 1451) {
                    sendJson(exchange, 409, "{\"message\":\"User cannot be deleted because there are related records\"}");
                    return;
                }
                throw exception;
            }
        }

        sendJson(exchange, 200, "{\"message\":\"User deleted successfully\"}");
    }

    private static void handleAccessReport(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        requireAdmin(session);

        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String startDate = trim(query.get("startDate"));
        String endDate = trim(query.get("endDate"));

        if (startDate.isEmpty() || endDate.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"Select both start date and end date\"}");
            return;
        }

        String sql = ""
            + "SELECT al.log_id, al.visit_date, al.entry_time, al.exit_time, "
            + "v.name AS visitor_name, e.name AS employee_name, e.department, u.username "
            + "FROM access_logs al "
            + "JOIN visitors v ON al.visitor_id = v.visitor_id "
            + "JOIN employees e ON al.employee_id = e.employee_id "
            + "JOIN users u ON al.user_id = u.user_id "
            + "WHERE al.visit_date BETWEEN ? AND ? "
            + "ORDER BY al.visit_date DESC, al.log_id DESC";

        List<String> rows = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDate(1, Date.valueOf(startDate));
            statement.setDate(2, Date.valueOf(endDate));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String exitTime = resultSet.getTime("exit_time") == null ? "" : resultSet.getTime("exit_time").toString();
                    rows.add("{"
                        + "\"id\":" + resultSet.getInt("log_id") + ","
                        + "\"date\":\"" + resultSet.getDate("visit_date") + "\","
                        + "\"visitorName\":\"" + escapeJson(resultSet.getString("visitor_name")) + "\","
                        + "\"host\":\"" + escapeJson(resultSet.getString("employee_name") + " (" + resultSet.getString("department") + ")") + "\","
                        + "\"entryTime\":\"" + resultSet.getTime("entry_time") + "\","
                        + "\"exitTime\":\"" + escapeJson(exitTime) + "\","
                        + "\"recordedBy\":\"" + escapeJson(resultSet.getString("username")) + "\""
                        + "}");
                }
            }
        }

        sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
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

    private static void requireSecurityOfficer(SessionInfo session) {
        if (!isSecurityRole(session.role)) {
            throw new SecurityException("Only Security Officers can perform this action");
        }
    }

    private static void requireAdmin(SessionInfo session) {
        if (!isAdminRole(session.role)) {
            throw new SecurityException("Only Admin users can perform this action");
        }
    }

    private static boolean isSecurityRole(String role) {
        String normalizedRole = defaultString(role, "").toLowerCase().replace(" ", "");
        return "security".equals(normalizedRole) || "securityofficer".equals(normalizedRole);
    }

    private static boolean isAdminRole(String role) {
        return "admin".equals(defaultString(role, "").toLowerCase().replace(" ", ""));
    }

    private static boolean usernameExists(Connection connection, String username, Integer excludedUserId) throws SQLException {
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

    private static int parseRequiredInt(String value, String message) {
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

    private static void initializeDatabaseState() throws IOException {
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "ALTER TABLE incidents "
                     + "ADD COLUMN IF NOT EXISTS incident_type VARCHAR(50) NOT NULL DEFAULT 'Security', "
                     + "ADD COLUMN IF NOT EXISTS location VARCHAR(150) NOT NULL DEFAULT 'Main Facility', "
                     + "ADD COLUMN IF NOT EXISTS status VARCHAR(30) NOT NULL DEFAULT 'Open', "
                     + "ADD COLUMN IF NOT EXISTS action_taken TEXT NULL"
             )) {
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new IOException("Failed to initialize database state", exception);
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

    private static Map<String, String> parseQuery(URI uri) {
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

    private static SessionInfo requireAuthenticated(HttpExchange exchange) {
        SessionInfo session = getOptionalSession(exchange);
        if (session == null) {
            throw new UnauthorizedException("Login required");
        }
        return session;
    }

    private static SessionInfo getOptionalSession(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null || cookieHeader.isBlank()) {
            return null;
        }

        String sessionId = null;
        String[] cookies = cookieHeader.split(";");
        for (String cookie : cookies) {
            String[] pair = cookie.trim().split("=", 2);
            if (pair.length == 2 && SESSION_COOKIE.equals(pair[0])) {
                sessionId = pair[1];
                break;
            }
        }

        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }

        SessionInfo session = SESSIONS.get(sessionId);
        if (session == null) {
            return null;
        }

        if (session.expiresAtMillis < System.currentTimeMillis()) {
            SESSIONS.remove(sessionId);
            return null;
        }

        return session;
    }

    private static SessionInfo createSession(int userId, String username, String role) {
        String sessionId = generateSessionId();
        SessionInfo session = new SessionInfo(sessionId, userId, username, role, System.currentTimeMillis() + SESSION_TTL_MILLIS);
        SESSIONS.put(sessionId, session);
        return session;
    }

    private static String generateSessionId() {
        byte[] bytes = new byte[32];
        SESSION_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void setSessionCookie(HttpExchange exchange, String sessionId) {
        exchange.getResponseHeaders().add(
            "Set-Cookie",
            SESSION_COOKIE + "=" + sessionId + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (SESSION_TTL_MILLIS / 1000L)
        );
    }

    private static void clearSessionCookie(HttpExchange exchange) {
        exchange.getResponseHeaders().add(
            "Set-Cookie",
            SESSION_COOKIE + "=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"
        );
    }

    private static String sessionToJson(SessionInfo session) {
        return "{"
            + "\"userId\":" + session.userId + ","
            + "\"username\":\"" + escapeJson(session.username) + "\","
            + "\"role\":\"" + escapeJson(session.role) + "\""
            + "}";
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

    private static final class SessionInfo {
        private final String sessionId;
        private final int userId;
        private final String username;
        private final String role;
        private final long expiresAtMillis;

        private SessionInfo(String sessionId, int userId, String username, String role, long expiresAtMillis) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.username = username;
            this.role = role;
            this.expiresAtMillis = expiresAtMillis;
        }
    }

    private static final class UnauthorizedException extends RuntimeException {
        private UnauthorizedException(String message) {
            super(message);
        }
    }
}
