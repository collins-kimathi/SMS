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
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
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
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class SmsApplication {

    private static final int DEFAULT_PORT = 8080;
    private static final String STATIC_ROOT = "/static";
    private static final String DB_URL = envOrDefault("SMS_DB_URL", "jdbc:mysql://localhost:3306/sms_db?serverTimezone=UTC");
    private static final String DB_USER = envOrDefault("SMS_DB_USER", "root");
    private static final String DB_PASSWORD = envOrDefault("SMS_DB_PASSWORD", "1234");
    private static final String PASSWORD_SCHEME = "pbkdf2";
    private static final int PASSWORD_ITERATIONS = 65536;
    private static final int PASSWORD_KEY_LENGTH = 256;
    private static final int PASSWORD_SALT_BYTES = 16;
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
        initializeSecurityState();
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

        String sql = "SELECT user_id, username, password, role FROM users WHERE username = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    sendJson(exchange, 401, "{\"message\":\"Invalid login credentials\"}");
                    return;
                }

                String storedPassword = resultSet.getString("password");
                if (!verifyPassword(password, storedPassword)) {
                    sendJson(exchange, 401, "{\"message\":\"Invalid login credentials\"}");
                    return;
                }

                upgradeLegacyPasswordIfNeeded(connection, resultSet.getInt("user_id"), storedPassword, password);

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
        int userId = parseRequiredInt(form.get("userId"), "User is required");

        if (name.isEmpty() || nationalId.isEmpty() || phoneNumber.isEmpty() || purposeOfVisit.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"All visitor fields are required\"}");
            return;
        }

        try (Connection connection = getConnection()) {
            requireSecurityOfficer(connection, userId);

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
        int visitorId = parseRequiredInt(form.get("visitorId"), "Visitor is required");
        int employeeId = parseRequiredInt(form.get("employeeId"), "Employee is required");
        int userId = parseRequiredInt(form.get("userId"), "User is required");

        try (Connection connection = getConnection()) {
            requireSecurityOfficer(connection, userId);

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
        int visitorId = parseRequiredInt(form.get("visitorId"), "Visitor is required");
        int userId = parseRequiredInt(form.get("userId"), "User is required");
        String sql = "UPDATE access_logs SET exit_time = ? WHERE visitor_id = ? AND exit_time IS NULL ORDER BY log_id DESC LIMIT 1";

        try (Connection connection = getConnection()) {
            requireSecurityOfficer(connection, userId);

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
        int userId = parseRequiredInt(form.get("userId"), "User is required");

        if (title.isEmpty() || severity.isEmpty() || description.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"All incident fields are required\"}");
            return;
        }

        String sql = "INSERT INTO incidents (title, description, severity, user_id, reported_at) VALUES (?, ?, ?, ?, ?)";

        try (Connection connection = getConnection()) {
            requireSecurityOfficer(connection, userId);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, title);
                statement.setString(2, description);
                statement.setString(3, severity);
                statement.setInt(4, userId);
                statement.setTimestamp(5, Timestamp.valueOf(java.time.LocalDateTime.now().withNano(0)));
                statement.executeUpdate();
            }
        }

        sendJson(exchange, 201, "{\"message\":\"Incident report saved successfully\"}");
    }

    private static void handleUsersList(HttpExchange exchange) throws IOException, SQLException {
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        int userId = parseRequiredInt(query.get("userId"), "User is required");
        String sql = "SELECT user_id, username, role FROM users ORDER BY user_id ASC";
        List<String> rows = new ArrayList<>();

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            requireAdmin(connection, userId);

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
        Map<String, String> form = parseFormBody(exchange);
        int adminUserId = parseRequiredInt(form.get("adminUserId"), "Admin user is required");
        String username = trim(form.get("username"));
        String password = trim(form.get("password"));
        String role = trim(form.get("role"));

        if (username.isEmpty() || password.isEmpty() || role.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"All user fields are required\"}");
            return;
        }

        try (Connection connection = getConnection()) {
            requireAdmin(connection, adminUserId);

            if (usernameExists(connection, username, null)) {
                sendJson(exchange, 409, "{\"message\":\"Username already exists\"}");
                return;
            }

            String sql = "INSERT INTO users (username, password, role) VALUES (?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, username);
                statement.setString(2, hashPassword(password));
                statement.setString(3, role);
                statement.executeUpdate();
            }
        }

        sendJson(exchange, 201, "{\"message\":\"User created successfully\"}");
    }

    private static void handleUpdateUser(HttpExchange exchange) throws IOException, SQLException {
        Map<String, String> form = parseFormBody(exchange);
        int adminUserId = parseRequiredInt(form.get("adminUserId"), "Admin user is required");
        int targetUserId = parseRequiredInt(form.get("targetUserId"), "Target user is required");
        String username = trim(form.get("username"));
        String password = trim(form.get("password"));
        String role = trim(form.get("role"));

        if (username.isEmpty() || role.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"Username and role are required\"}");
            return;
        }

        try (Connection connection = getConnection()) {
            requireAdmin(connection, adminUserId);

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
                    statement.setString(2, hashPassword(password));
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
        Map<String, String> form = parseFormBody(exchange);
        int adminUserId = parseRequiredInt(form.get("adminUserId"), "Admin user is required");
        int targetUserId = parseRequiredInt(form.get("targetUserId"), "Target user is required");

        if (adminUserId == targetUserId) {
            sendJson(exchange, 400, "{\"message\":\"You cannot delete the currently signed-in admin\"}");
            return;
        }

        try (Connection connection = getConnection()) {
            requireAdmin(connection, adminUserId);

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
        Map<String, String> query = parseQuery(exchange.getRequestURI());
        int userId = parseRequiredInt(query.get("userId"), "User is required");
        String startDate = trim(query.get("startDate"));
        String endDate = trim(query.get("endDate"));

        if (startDate.isEmpty() || endDate.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"Select both start date and end date\"}");
            return;
        }

        String sql = ""
            + "SELECT al.log_id, al.visit_date, al.entry_time, al.exit_time, v.name AS visitor_name, "
            + "e.name AS employee_name, e.department, u.username "
            + "FROM access_logs al "
            + "JOIN visitors v ON al.visitor_id = v.visitor_id "
            + "JOIN employees e ON al.employee_id = e.employee_id "
            + "JOIN users u ON al.user_id = u.user_id "
            + "WHERE al.visit_date BETWEEN ? AND ? "
            + "ORDER BY al.visit_date DESC, al.log_id DESC";

        List<String> rows = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            requireAdmin(connection, userId);
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

    private static void requireSecurityOfficer(Connection connection, int userId) throws SQLException {
        String role = findUserRole(connection, userId);
        if (!isSecurityRole(role)) {
            throw new SecurityException("Only Security Officers can perform this action");
        }
    }

    private static void requireAdmin(Connection connection, int userId) throws SQLException {
        String role = findUserRole(connection, userId);
        if (!isAdminRole(role)) {
            throw new SecurityException("Only Admin users can perform this action");
        }
    }

    private static String findUserRole(Connection connection, int userId) throws SQLException {
        String sql = "SELECT role FROM users WHERE user_id = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw new SecurityException("User account not found");
                }
                return defaultString(resultSet.getString("role"), "");
            }
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

    private static void initializeSecurityState() throws IOException {
        try (Connection connection = getConnection()) {
            ensureDefaultUser(connection, "admin", "1234", "Admin");
            ensureDefaultUser(connection, "officer", "1234", "Security");
        } catch (SQLException exception) {
            throw new IOException("Failed to initialize security state", exception);
        }
    }

    private static void ensureDefaultUser(Connection connection, String username, String password, String role) throws SQLException {
        String selectSql = "SELECT user_id, password FROM users WHERE username = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setString(1, username);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String storedPassword = resultSet.getString("password");
                    if (!isHashedPassword(storedPassword) && password.equals(storedPassword)) {
                        updateStoredPassword(connection, resultSet.getInt("user_id"), hashPassword(password));
                    }
                    return;
                }
            }
        }

        String insertSql = "INSERT INTO users (username, password, role) VALUES (?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(insertSql)) {
            statement.setString(1, username);
            statement.setString(2, hashPassword(password));
            statement.setString(3, role);
            statement.executeUpdate();
        }
    }

    private static void upgradeLegacyPasswordIfNeeded(Connection connection, int userId, String storedPassword, String rawPassword) throws SQLException {
        if (isHashedPassword(storedPassword)) {
            return;
        }

        if (rawPassword.equals(storedPassword)) {
            updateStoredPassword(connection, userId, hashPassword(rawPassword));
        }
    }

    private static void updateStoredPassword(Connection connection, int userId, String hashedPassword) throws SQLException {
        String sql = "UPDATE users SET password = ? WHERE user_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hashedPassword);
            statement.setInt(2, userId);
            statement.executeUpdate();
        }
    }

    private static boolean verifyPassword(String rawPassword, String storedPassword) {
        if (storedPassword == null || storedPassword.isBlank()) {
            return false;
        }

        if (!isHashedPassword(storedPassword)) {
            return rawPassword.equals(storedPassword);
        }

        try {
            String[] parts = storedPassword.split("\\$");
            if (parts.length != 4 || !PASSWORD_SCHEME.equals(parts[0])) {
                return false;
            }

            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, iterations);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isHashedPassword(String storedPassword) {
        return storedPassword != null && storedPassword.startsWith(PASSWORD_SCHEME + "$");
    }

    private static String hashPassword(String rawPassword) {
        byte[] salt = new byte[PASSWORD_SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, PASSWORD_ITERATIONS);
        return PASSWORD_SCHEME
            + "$" + PASSWORD_ITERATIONS
            + "$" + Base64.getEncoder().encodeToString(salt)
            + "$" + Base64.getEncoder().encodeToString(hash);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, PASSWORD_KEY_LENGTH);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Password hashing failed", exception);
        } finally {
            spec.clearPassword();
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
