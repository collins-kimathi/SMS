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
import java.security.spec.InvalidKeySpecException;
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
    private static final String SESSION_COOKIE = "sms_session";
    private static final long SESSION_TTL_MILLIS = 8L * 60L * 60L * 1000L;
    private static final String DB_URL = envOrDefault("SMS_DB_URL", "jdbc:mysql://localhost:3306/sms_db?serverTimezone=UTC");
    private static final String DB_USER = envOrDefault("SMS_DB_USER", "root");
    private static final String DB_PASSWORD = envOrDefault("SMS_DB_PASSWORD", "1234");
    private static final String PASSWORD_PREFIX = "pbkdf2$";
    private static final int PASSWORD_ITERATIONS = 120000;
    private static final int PASSWORD_KEY_LENGTH = 256;
    private static final Map<String, String> CONTENT_TYPES = new HashMap<>();
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
                case "/api/visitors/update":
                    requireMethod(exchange, "POST");
                    handleUpdateVisitor(exchange);
                    break;
                case "/api/visitors/delete":
                    requireMethod(exchange, "POST");
                    handleDeleteVisitor(exchange);
                    break;
                case "/api/employees":
                    requireMethod(exchange, "GET");
                    handleEmployeesList(exchange);
                    break;
                case "/api/employees/create":
                    requireMethod(exchange, "POST");
                    handleCreateEmployee(exchange);
                    break;
                case "/api/employees/update":
                    requireMethod(exchange, "POST");
                    handleUpdateEmployee(exchange);
                    break;
                case "/api/employees/delete":
                    requireMethod(exchange, "POST");
                    handleDeleteEmployee(exchange);
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
                case "/api/access-logs/update":
                    requireMethod(exchange, "POST");
                    handleUpdateAccessLog(exchange);
                    break;
                case "/api/access-logs/delete":
                    requireMethod(exchange, "POST");
                    handleDeleteAccessLog(exchange);
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
                case "/api/incidents/update":
                    requireMethod(exchange, "POST");
                    handleUpdateIncident(exchange);
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
                case "/api/reports/incidents":
                    requireMethod(exchange, "GET");
                    handleIncidentReport(exchange);
                    break;
                case "/api/reports/visitors":
                    requireMethod(exchange, "GET");
                    handleVisitorReport(exchange);
                    break;
                case "/api/reports/audit":
                    requireMethod(exchange, "GET");
                    handleAuditReport(exchange);
                    break;
                case "/api/reports/export":
                    requireMethod(exchange, "GET");
                    handleExportReport(exchange);
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

        String sql = "SELECT user_id, username, password, role FROM users WHERE username = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);

            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    sendJson(exchange, 401, "{\"message\":\"Invalid login credentials\"}");
                    return;
                }

                String storedPassword = defaultString(resultSet.getString("password"), "");
                if (!verifyPassword(password, storedPassword)) {
                    sendJson(exchange, 401, "{\"message\":\"Invalid login credentials\"}");
                    return;
                }

                // Upgrade legacy plain-text rows as soon as a valid login proves ownership.
                upgradePlainTextPasswordIfNeeded(connection, resultSet.getInt("user_id"), storedPassword);

                SessionInfo session = createSession(
                    connection,
                    resultSet.getInt("user_id"),
                    resultSet.getString("username"),
                    defaultString(resultSet.getString("role"), "Security")
                );
                recordAuditAction(connection, session.userId, "LOGIN", "session", session.sessionId, "User signed in");
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
            try (Connection connection = getConnection()) {
                deleteSession(connection, session.sessionId);
                recordAuditAction(connection, session.userId, "LOGOUT", "session", session.sessionId, "User signed out");
            } catch (SQLException exception) {
                throw new IOException("Failed to end session", exception);
            }
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
            recordAuditAction(connection, session.userId, "CREATE", "visitor", nationalId, "Registered visitor " + name);
        }

        sendJson(exchange, 201, "{\"message\":\"Visitor registered successfully\"}");
    }

    private static void handleUpdateVisitor(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        requireSecurityOfficer(session);

        Map<String, String> form = parseFormBody(exchange);
        int visitorId = parseRequiredInt(form.get("visitorId"), "Visitor is required");
        String name = trim(form.get("name"));
        String nationalId = trim(form.get("nationalId"));
        String phoneNumber = trim(form.get("phoneNumber"));
        String purposeOfVisit = trim(form.get("purposeOfVisit"));

        if (name.isEmpty() || nationalId.isEmpty() || phoneNumber.isEmpty() || purposeOfVisit.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"All visitor fields are required\"}");
            return;
        }

        try (Connection connection = getConnection()) {
            if (visitorExists(connection, nationalId, visitorId)) {
                sendJson(exchange, 409, "{\"message\":\"Another visitor already uses that ID\"}");
                return;
            }

            String sql = "UPDATE visitors SET name = ?, national_id = ?, phone_number = ?, purpose_of_visit = ? WHERE visitor_id = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, name);
                statement.setString(2, nationalId);
                statement.setString(3, phoneNumber);
                statement.setString(4, purposeOfVisit);
                statement.setInt(5, visitorId);
                int updated = statement.executeUpdate();
                if (updated == 0) {
                    sendJson(exchange, 404, "{\"message\":\"Visitor not found\"}");
                    return;
                }
            }
            recordAuditAction(connection, session.userId, "UPDATE", "visitor", String.valueOf(visitorId), "Updated visitor " + name);
        }

        sendJson(exchange, 200, "{\"message\":\"Visitor updated successfully\"}");
    }

    private static void handleDeleteVisitor(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        requireSecurityOfficer(session);

        Map<String, String> form = parseFormBody(exchange);
        int visitorId = parseRequiredInt(form.get("visitorId"), "Visitor is required");

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM visitors WHERE visitor_id = ?")) {
            statement.setInt(1, visitorId);
            int deleted = statement.executeUpdate();
            if (deleted == 0) {
                sendJson(exchange, 404, "{\"message\":\"Visitor not found\"}");
                return;
            }
            recordAuditAction(connection, session.userId, "DELETE", "visitor", String.valueOf(visitorId), "Deleted visitor record");
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1451) {
                sendJson(exchange, 409, "{\"message\":\"Visitor cannot be deleted because there are related access logs\"}");
                return;
            }
            throw exception;
        }

        sendJson(exchange, 200, "{\"message\":\"Visitor deleted successfully\"}");
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

    private static void handleCreateEmployee(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        requireAdmin(session);

        Map<String, String> form = parseFormBody(exchange);
        String name = trim(form.get("name"));
        String department = trim(form.get("department"));
        String phoneNumber = trim(form.get("phoneNumber"));

        if (name.isEmpty() || department.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"Employee name and department are required\"}");
            return;
        }

        String sql = "INSERT INTO employees (name, department, phone_number) VALUES (?, ?, ?)";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, department);
            statement.setString(3, phoneNumber);
            statement.executeUpdate();
            recordAuditAction(connection, session.userId, "CREATE", "employee", name, "Created employee " + name);
        }

        sendJson(exchange, 201, "{\"message\":\"Employee created successfully\"}");
    }

    private static void handleUpdateEmployee(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        requireAdmin(session);

        Map<String, String> form = parseFormBody(exchange);
        int employeeId = parseRequiredInt(form.get("employeeId"), "Employee is required");
        String name = trim(form.get("name"));
        String department = trim(form.get("department"));
        String phoneNumber = trim(form.get("phoneNumber"));

        if (name.isEmpty() || department.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"Employee name and department are required\"}");
            return;
        }

        String sql = "UPDATE employees SET name = ?, department = ?, phone_number = ? WHERE employee_id = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, department);
            statement.setString(3, phoneNumber);
            statement.setInt(4, employeeId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                sendJson(exchange, 404, "{\"message\":\"Employee not found\"}");
                return;
            }
            recordAuditAction(connection, session.userId, "UPDATE", "employee", String.valueOf(employeeId), "Updated employee " + name);
        }

        sendJson(exchange, 200, "{\"message\":\"Employee updated successfully\"}");
    }

    private static void handleDeleteEmployee(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        requireAdmin(session);

        Map<String, String> form = parseFormBody(exchange);
        int employeeId = parseRequiredInt(form.get("employeeId"), "Employee is required");

        String sql = "DELETE FROM employees WHERE employee_id = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, employeeId);
            int deleted = statement.executeUpdate();
            if (deleted == 0) {
                sendJson(exchange, 404, "{\"message\":\"Employee not found\"}");
                return;
            }
            recordAuditAction(connection, session.userId, "DELETE", "employee", String.valueOf(employeeId), "Deleted employee record");
        } catch (SQLException exception) {
            if (exception.getErrorCode() == 1451) {
                sendJson(exchange, 409, "{\"message\":\"Employee cannot be deleted because there are related access logs\"}");
                return;
            }
            throw exception;
        }

        sendJson(exchange, 200, "{\"message\":\"Employee deleted successfully\"}");
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
            recordAuditAction(connection, session.userId, "CREATE", "access_log", String.valueOf(visitorId), "Recorded visitor entry");
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
            recordAuditAction(connection, session.userId, "UPDATE", "access_log", String.valueOf(visitorId), "Recorded visitor exit");
        }

        sendJson(exchange, 200, "{\"message\":\"Exit recorded successfully\"}");
    }

    private static void handleUpdateAccessLog(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        requireSecurityOfficer(session);

        Map<String, String> form = parseFormBody(exchange);
        int logId = parseRequiredInt(form.get("logId"), "Access record is required");
        int visitorId = parseRequiredInt(form.get("visitorId"), "Visitor is required");
        int employeeId = parseRequiredInt(form.get("employeeId"), "Employee is required");
        String visitDate = trim(form.get("visitDate"));
        String entryTime = trim(form.get("entryTime"));
        String exitTime = trim(form.get("exitTime"));

        if (visitDate.isEmpty() || entryTime.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"Visit date and entry time are required\"}");
            return;
        }

        String sql = "UPDATE access_logs SET visitor_id = ?, employee_id = ?, visit_date = ?, entry_time = ?, exit_time = ? WHERE log_id = ?";
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, visitorId);
            statement.setInt(2, employeeId);
            statement.setDate(3, Date.valueOf(visitDate));
            statement.setTime(4, Time.valueOf(normalizeTime(entryTime)));
            if (exitTime.isEmpty()) {
                statement.setNull(5, java.sql.Types.TIME);
            } else {
                statement.setTime(5, Time.valueOf(normalizeTime(exitTime)));
            }
            statement.setInt(6, logId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                sendJson(exchange, 404, "{\"message\":\"Access record not found\"}");
                return;
            }
            recordAuditAction(connection, session.userId, "UPDATE", "access_log", String.valueOf(logId), "Updated access record");
        }

        sendJson(exchange, 200, "{\"message\":\"Access record updated successfully\"}");
    }

    private static void handleDeleteAccessLog(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        requireSecurityOfficer(session);

        Map<String, String> form = parseFormBody(exchange);
        int logId = parseRequiredInt(form.get("logId"), "Access record is required");

        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM access_logs WHERE log_id = ?")) {
            statement.setInt(1, logId);
            int deleted = statement.executeUpdate();
            if (deleted == 0) {
                sendJson(exchange, 404, "{\"message\":\"Access record not found\"}");
                return;
            }
            recordAuditAction(connection, session.userId, "DELETE", "access_log", String.valueOf(logId), "Deleted access record");
        }

        sendJson(exchange, 200, "{\"message\":\"Access record deleted successfully\"}");
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
            recordAuditAction(connection, session.userId, "CREATE", "incident", title, "Created incident " + title);
        }

        sendJson(exchange, 201, "{\"message\":\"Incident report saved successfully\"}");
    }

    private static void handleUpdateIncident(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        Map<String, String> form = parseFormBody(exchange);
        int incidentId = parseRequiredInt(form.get("incidentId"), "Incident is required");
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

        String sql = "UPDATE incidents SET title = ?, incident_type = ?, location = ?, status = ?, action_taken = ?, description = ?, severity = ? WHERE incident_id = ?";

        try (Connection connection = getConnection()) {
            requireIncidentManager(session);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, title);
                statement.setString(2, incidentType);
                statement.setString(3, location);
                statement.setString(4, status);
                statement.setString(5, actionTaken);
                statement.setString(6, description);
                statement.setString(7, severity);
                statement.setInt(8, incidentId);

                int updated = statement.executeUpdate();
                if (updated == 0) {
                    sendJson(exchange, 404, "{\"message\":\"Incident not found\"}");
                    return;
                }
            }
            recordAuditAction(connection, session.userId, "UPDATE", "incident", String.valueOf(incidentId), "Updated incident " + title);
        }

        sendJson(exchange, 200, "{\"message\":\"Incident updated successfully\"}");
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
                statement.setString(2, hashPassword(password));
                statement.setString(3, role);
                statement.executeUpdate();
            }
            recordAuditAction(connection, session.userId, "CREATE", "user", username, "Created user " + username);
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

            String currentUsername = null;
            String currentRole = null;
            try (PreparedStatement currentStatement = connection.prepareStatement(
                "SELECT username, role FROM users WHERE user_id = ?")) {
                currentStatement.setInt(1, targetUserId);
                try (ResultSet resultSet = currentStatement.executeQuery()) {
                    if (!resultSet.next()) {
                        sendJson(exchange, 404, "{\"message\":\"User not found\"}");
                        return;
                    }
                    currentUsername = defaultString(resultSet.getString("username"), "");
                    currentRole = defaultString(resultSet.getString("role"), "");
                }
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

            boolean revokeSessions = !password.isEmpty()
                || !currentUsername.equals(username)
                || !currentRole.equals(role);
            if (revokeSessions) {
                deleteSessionsForUser(connection, targetUserId);
            }
            recordAuditAction(connection, session.userId, "UPDATE", "user", String.valueOf(targetUserId), "Updated user " + username);

            sendJson(exchange, 200, "{"
                + "\"message\":\"User updated successfully\","
                + "\"sessionRevoked\":" + (revokeSessions ? "true" : "false") + ","
                + "\"updatedCurrentUser\":" + (targetUserId == session.userId ? "true" : "false")
                + "}");
            return;
        }
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
                recordAuditAction(connection, session.userId, "DELETE", "user", String.valueOf(targetUserId), "Deleted user account");
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

    private static void handleIncidentReport(HttpExchange exchange) throws IOException, SQLException {
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
            + "SELECT i.incident_id, i.reported_at, i.title, i.incident_type, i.location, i.severity, i.status, u.username "
            + "FROM incidents i "
            + "JOIN users u ON i.user_id = u.user_id "
            + "WHERE DATE(i.reported_at) BETWEEN ? AND ? "
            + "ORDER BY i.reported_at DESC, i.incident_id DESC";

        List<String> rows = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDate(1, Date.valueOf(startDate));
            statement.setDate(2, Date.valueOf(endDate));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add("{"
                        + "\"id\":" + resultSet.getInt("incident_id") + ","
                        + "\"date\":\"" + resultSet.getTimestamp("reported_at").toLocalDateTime().toLocalDate() + "\","
                        + "\"title\":\"" + escapeJson(resultSet.getString("title")) + "\","
                        + "\"incidentType\":\"" + escapeJson(resultSet.getString("incident_type")) + "\","
                        + "\"location\":\"" + escapeJson(resultSet.getString("location")) + "\","
                        + "\"severity\":\"" + escapeJson(resultSet.getString("severity")) + "\","
                        + "\"status\":\"" + escapeJson(resultSet.getString("status")) + "\","
                        + "\"reportedBy\":\"" + escapeJson(resultSet.getString("username")) + "\""
                        + "}");
                }
            }
        }

        sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    private static void handleVisitorReport(HttpExchange exchange) throws IOException, SQLException {
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
            + "SELECT visitor_id, name, national_id, phone_number, purpose_of_visit, created_at "
            + "FROM visitors "
            + "WHERE DATE(created_at) BETWEEN ? AND ? "
            + "ORDER BY created_at DESC, visitor_id DESC";

        List<String> rows = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDate(1, Date.valueOf(startDate));
            statement.setDate(2, Date.valueOf(endDate));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add("{"
                        + "\"id\":" + resultSet.getInt("visitor_id") + ","
                        + "\"date\":\"" + resultSet.getTimestamp("created_at").toLocalDateTime().toLocalDate() + "\","
                        + "\"name\":\"" + escapeJson(resultSet.getString("name")) + "\","
                        + "\"nationalId\":\"" + escapeJson(resultSet.getString("national_id")) + "\","
                        + "\"phoneNumber\":\"" + escapeJson(resultSet.getString("phone_number")) + "\","
                        + "\"purposeOfVisit\":\"" + escapeJson(resultSet.getString("purpose_of_visit")) + "\""
                        + "}");
                }
            }
        }

        sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    private static void handleAuditReport(HttpExchange exchange) throws IOException, SQLException {
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
            + "SELECT audit_id, action_type, entity_type, entity_id, details, created_at, username "
            + "FROM audit_logs "
            + "WHERE DATE(created_at) BETWEEN ? AND ? "
            + "ORDER BY created_at DESC, audit_id DESC";

        List<String> rows = new ArrayList<>();
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDate(1, Date.valueOf(startDate));
            statement.setDate(2, Date.valueOf(endDate));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add("{"
                        + "\"id\":" + resultSet.getInt("audit_id") + ","
                        + "\"date\":\"" + resultSet.getTimestamp("created_at").toLocalDateTime().toLocalDate() + "\","
                        + "\"actionType\":\"" + escapeJson(resultSet.getString("action_type")) + "\","
                        + "\"entityType\":\"" + escapeJson(resultSet.getString("entity_type")) + "\","
                        + "\"entityId\":\"" + escapeJson(defaultString(resultSet.getString("entity_id"), "")) + "\","
                        + "\"details\":\"" + escapeJson(defaultString(resultSet.getString("details"), "")) + "\","
                        + "\"username\":\"" + escapeJson(defaultString(resultSet.getString("username"), "System")) + "\""
                        + "}");
                }
            }
        }

        sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    private static void handleExportReport(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = requireAuthenticated(exchange);
        requireAdmin(session);

        Map<String, String> query = parseQuery(exchange.getRequestURI());
        String type = trim(query.get("type"));
        String startDate = trim(query.get("startDate"));
        String endDate = trim(query.get("endDate"));

        if (type.isEmpty() || startDate.isEmpty() || endDate.isEmpty()) {
            sendText(exchange, 400, "Missing export parameters");
            return;
        }

        String csv = buildCsvReport(type, startDate, endDate);
        byte[] payload = csv.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Disposition",
            "attachment; filename=\"" + type + "-report-" + startDate + "-to-" + endDate + ".csv\"");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream outputStream = exchange.getResponseBody()) {
            outputStream.write(payload);
        }
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

    private static boolean visitorExists(Connection connection, String nationalId, int excludedVisitorId) throws SQLException {
        String sql = "SELECT 1 FROM visitors WHERE national_id = ? AND visitor_id <> ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, nationalId);
            statement.setInt(2, excludedVisitorId);
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

    private static void recordAuditAction(Connection connection, Integer userId, String actionType, String entityType, String entityId, String details) throws SQLException {
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

    private static String resolveUsername(Connection connection, int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT username FROM users WHERE user_id = ?")) {
            statement.setInt(1, userId);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("username") : "Unknown";
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

    private static void requireIncidentManager(SessionInfo session) {
        if (!isAdminRole(session.role) && !isSecurityRole(session.role)) {
            throw new SecurityException("Only authorized staff can update incidents");
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

    private static String normalizeTime(String value) {
        String trimmedValue = trim(value);
        if (trimmedValue.matches("^\\d{2}:\\d{2}$")) {
            return trimmedValue + ":00";
        }
        return trimmedValue;
    }

    private static void initializeDatabaseState() throws IOException {
        try (Connection connection = getConnection()) {
            // Keep older databases compatible with the current code without requiring a manual reset.
            ensureColumnExists(connection, "incidents", "incident_type",
                "ALTER TABLE incidents ADD COLUMN incident_type VARCHAR(50) NOT NULL DEFAULT 'Security'");
            ensureColumnExists(connection, "incidents", "location",
                "ALTER TABLE incidents ADD COLUMN location VARCHAR(150) NOT NULL DEFAULT 'Main Facility'");
            ensureColumnExists(connection, "incidents", "status",
                "ALTER TABLE incidents ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'Open'");
            ensureColumnExists(connection, "incidents", "action_taken",
                "ALTER TABLE incidents ADD COLUMN action_taken TEXT NULL");
            ensureColumnExists(connection, "visitors", "created_at",
                "ALTER TABLE visitors ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP");
            ensureTableExists(connection, "CREATE TABLE IF NOT EXISTS sessions ("
                + "session_id VARCHAR(128) PRIMARY KEY,"
                + "user_id INT NOT NULL,"
                + "username VARCHAR(50) NOT NULL,"
                + "role VARCHAR(20) NOT NULL,"
                + "expires_at DATETIME NOT NULL,"
                + "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE CASCADE)");
            ensureTableExists(connection, "CREATE TABLE IF NOT EXISTS audit_logs ("
                + "audit_id INT AUTO_INCREMENT PRIMARY KEY,"
                + "user_id INT NULL,"
                + "username VARCHAR(50) NOT NULL,"
                + "action_type VARCHAR(30) NOT NULL,"
                + "entity_type VARCHAR(50) NOT NULL,"
                + "entity_id VARCHAR(100) NULL,"
                + "details TEXT NOT NULL,"
                + "created_at DATETIME NOT NULL,"
                + "FOREIGN KEY (user_id) REFERENCES users(user_id) ON DELETE SET NULL)");
            cleanupExpiredSessions(connection);
            migrateLegacyPasswords(connection);
        } catch (SQLException exception) {
            throw new IOException("Failed to initialize database state", exception);
        }
    }

    private static void ensureTableExists(Connection connection, String createSql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(createSql)) {
            statement.executeUpdate();
        }
    }

    private static void ensureColumnExists(Connection connection, String tableName, String columnName, String alterSql) throws SQLException {
        String checkSql = "SELECT 1 FROM information_schema.columns "
            + "WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ? LIMIT 1";

        try (PreparedStatement checkStatement = connection.prepareStatement(checkSql)) {
            checkStatement.setString(1, tableName);
            checkStatement.setString(2, columnName);

            try (ResultSet resultSet = checkStatement.executeQuery()) {
                if (resultSet.next()) {
                    return;
                }
            }
        }

        try (PreparedStatement alterStatement = connection.prepareStatement(alterSql)) {
            alterStatement.executeUpdate();
        }
    }

    private static void cleanupExpiredSessions(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE expires_at < ?")) {
            statement.setTimestamp(1, Timestamp.valueOf(java.time.LocalDateTime.now().withNano(0)));
            statement.executeUpdate();
        }
    }

    private static void migrateLegacyPasswords(Connection connection) throws SQLException {
        String selectSql = "SELECT user_id, password FROM users";
        String updateSql = "UPDATE users SET password = ? WHERE user_id = ?";

        try (PreparedStatement selectStatement = connection.prepareStatement(selectSql);
             ResultSet resultSet = selectStatement.executeQuery();
             PreparedStatement updateStatement = connection.prepareStatement(updateSql)) {
            while (resultSet.next()) {
                String storedPassword = defaultString(resultSet.getString("password"), "");
                if (isHashedPassword(storedPassword)) {
                    continue;
                }

                updateStatement.setString(1, hashPassword(storedPassword));
                updateStatement.setInt(2, resultSet.getInt("user_id"));
                updateStatement.addBatch();
            }

            updateStatement.executeBatch();
        }
    }

    private static boolean isHashedPassword(String password) {
        return password != null && password.startsWith(PASSWORD_PREFIX);
    }

    private static boolean verifyPassword(String rawPassword, String storedPassword) {
        if (!isHashedPassword(storedPassword)) {
            return storedPassword.equals(rawPassword);
        }

        String[] parts = storedPassword.split("\\$");
        if (parts.length != 4) {
            return false;
        }

        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = decodeBase64(parts[2]);
            byte[] expected = decodeBase64(parts[3]);
            byte[] actual = derivePassword(rawPassword.toCharArray(), salt, iterations, expected.length * 8);
            return constantTimeEquals(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void upgradePlainTextPasswordIfNeeded(Connection connection, int userId, String storedPassword) throws SQLException {
        if (isHashedPassword(storedPassword)) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("UPDATE users SET password = ? WHERE user_id = ?")) {
            statement.setString(1, hashPassword(storedPassword));
            statement.setInt(2, userId);
            statement.executeUpdate();
        }
    }

    private static String hashPassword(String password) {
        byte[] salt = new byte[16];
        SESSION_RANDOM.nextBytes(salt);
        byte[] hash = derivePassword(password.toCharArray(), salt, PASSWORD_ITERATIONS, PASSWORD_KEY_LENGTH);
        return PASSWORD_PREFIX + PASSWORD_ITERATIONS + "$" + encodeBase64(salt) + "$" + encodeBase64(hash);
    }

    private static byte[] derivePassword(char[] password, byte[] salt, int iterations, int keyLength) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (InvalidKeySpecException | java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Unable to hash password", exception);
        } finally {
            spec.clearPassword();
        }
    }

    private static boolean constantTimeEquals(byte[] left, byte[] right) {
        if (left.length != right.length) {
            return false;
        }

        int result = 0;
        for (int index = 0; index < left.length; index++) {
            result |= left[index] ^ right[index];
        }
        return result == 0;
    }

    private static String encodeBase64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] decodeBase64(String value) {
        int paddingNeeded = (4 - (value.length() % 4)) % 4;
        return Base64.getUrlDecoder().decode(value + "=".repeat(paddingNeeded));
    }

    private static String buildCsvReport(String type, String startDate, String endDate) throws SQLException {
        // Reuse the same date-range semantics as the on-screen reports so exports match what users see.
        List<String[]> rows = new ArrayList<>();
        if ("access".equals(type)) {
            rows.add(new String[] {"Date", "Visitor", "Host", "Entry", "Exit", "Recorded By"});
            String sql = ""
                + "SELECT al.visit_date, v.name AS visitor_name, e.name AS employee_name, e.department, u.username, al.entry_time, al.exit_time "
                + "FROM access_logs al "
                + "JOIN visitors v ON al.visitor_id = v.visitor_id "
                + "JOIN employees e ON al.employee_id = e.employee_id "
                + "JOIN users u ON al.user_id = u.user_id "
                + "WHERE al.visit_date BETWEEN ? AND ? ORDER BY al.visit_date DESC, al.log_id DESC";
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setDate(1, Date.valueOf(startDate));
                statement.setDate(2, Date.valueOf(endDate));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new String[] {
                            String.valueOf(resultSet.getDate("visit_date")),
                            resultSet.getString("visitor_name"),
                            resultSet.getString("employee_name") + " (" + resultSet.getString("department") + ")",
                            String.valueOf(resultSet.getTime("entry_time")),
                            resultSet.getTime("exit_time") == null ? "" : String.valueOf(resultSet.getTime("exit_time")),
                            resultSet.getString("username")
                        });
                    }
                }
            }
        } else if ("incidents".equals(type)) {
            rows.add(new String[] {"Date", "Title", "Type", "Location", "Severity", "Status", "Reported By"});
            String sql = ""
                + "SELECT DATE(reported_at) AS report_date, title, incident_type, location, severity, status, username "
                + "FROM incidents i JOIN users u ON i.user_id = u.user_id "
                + "WHERE DATE(reported_at) BETWEEN ? AND ? ORDER BY reported_at DESC, incident_id DESC";
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setDate(1, Date.valueOf(startDate));
                statement.setDate(2, Date.valueOf(endDate));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new String[] {
                            String.valueOf(resultSet.getDate("report_date")),
                            resultSet.getString("title"),
                            resultSet.getString("incident_type"),
                            resultSet.getString("location"),
                            resultSet.getString("severity"),
                            resultSet.getString("status"),
                            resultSet.getString("username")
                        });
                    }
                }
            }
        } else if ("visitors".equals(type)) {
            rows.add(new String[] {"Date", "Visitor", "ID / Passport", "Phone", "Purpose"});
            String sql = "SELECT DATE(created_at) AS created_date, name, national_id, phone_number, purpose_of_visit "
                + "FROM visitors WHERE DATE(created_at) BETWEEN ? AND ? ORDER BY created_at DESC, visitor_id DESC";
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setDate(1, Date.valueOf(startDate));
                statement.setDate(2, Date.valueOf(endDate));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new String[] {
                            String.valueOf(resultSet.getDate("created_date")),
                            resultSet.getString("name"),
                            resultSet.getString("national_id"),
                            resultSet.getString("phone_number"),
                            resultSet.getString("purpose_of_visit")
                        });
                    }
                }
            }
        } else if ("audit".equals(type)) {
            rows.add(new String[] {"Date", "Action", "Entity", "Entity ID", "User", "Details"});
            String sql = "SELECT DATE(created_at) AS created_date, action_type, entity_type, entity_id, username, details "
                + "FROM audit_logs WHERE DATE(created_at) BETWEEN ? AND ? ORDER BY created_at DESC, audit_id DESC";
            try (Connection connection = getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setDate(1, Date.valueOf(startDate));
                statement.setDate(2, Date.valueOf(endDate));
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(new String[] {
                            String.valueOf(resultSet.getDate("created_date")),
                            resultSet.getString("action_type"),
                            resultSet.getString("entity_type"),
                            defaultString(resultSet.getString("entity_id"), ""),
                            resultSet.getString("username"),
                            resultSet.getString("details")
                        });
                    }
                }
            }
        } else {
            throw new IllegalArgumentException("Unsupported report type");
        }

        StringBuilder builder = new StringBuilder();
        for (String[] row : rows) {
            for (int index = 0; index < row.length; index++) {
                if (index > 0) {
                    builder.append(',');
                }
                builder.append('"').append(defaultString(row[index], "").replace("\"", "\"\"")).append('"');
            }
            builder.append('\n');
        }
        return builder.toString();
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

        // Sessions are loaded from MySQL so restarts do not rely on an in-memory Java map.
        try (Connection connection = getConnection();
             PreparedStatement statement = connection.prepareStatement(
                 "SELECT session_id, user_id, username, role, expires_at FROM sessions WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }

                Timestamp expiresAt = resultSet.getTimestamp("expires_at");
                if (expiresAt == null || expiresAt.getTime() < System.currentTimeMillis()) {
                    deleteSession(connection, sessionId);
                    return null;
                }

                return new SessionInfo(
                    resultSet.getString("session_id"),
                    resultSet.getInt("user_id"),
                    resultSet.getString("username"),
                    resultSet.getString("role"),
                    expiresAt.getTime()
                );
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to load session", exception);
        }
    }

    private static SessionInfo createSession(Connection connection, int userId, String username, String role) throws SQLException {
        String sessionId = generateSessionId();
        SessionInfo session = new SessionInfo(sessionId, userId, username, role, System.currentTimeMillis() + SESSION_TTL_MILLIS);
        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO sessions (session_id, user_id, username, role, expires_at) VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, sessionId);
            statement.setInt(2, userId);
            statement.setString(3, username);
            statement.setString(4, role);
            statement.setTimestamp(5, new Timestamp(session.expiresAtMillis));
            statement.executeUpdate();
        }
        return session;
    }

    private static void deleteSession(Connection connection, String sessionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE session_id = ?")) {
            statement.setString(1, sessionId);
            statement.executeUpdate();
        }
    }

    private static void deleteSessionsForUser(Connection connection, int userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM sessions WHERE user_id = ?")) {
            statement.setInt(1, userId);
            statement.executeUpdate();
        }
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
