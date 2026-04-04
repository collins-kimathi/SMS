package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Handles access log entry, exit, update, and delete endpoints.
final class AccessController {

    private AccessController() {
    }

    static void handleList(HttpExchange exchange) throws IOException, SQLException {
        SmsApplication.requireAuthenticated(exchange);
        String sql = ""
            + "SELECT al.log_id, al.visitor_id, al.employee_id, al.user_id, al.visit_date, al.entry_time, al.exit_time, "
            + "v.name AS visitor_name, e.name AS employee_name, e.department, u.username "
            + "FROM access_logs al "
            + "JOIN visitors v ON al.visitor_id = v.visitor_id "
            + "JOIN employees e ON al.employee_id = e.employee_id "
            + "JOIN users u ON al.user_id = u.user_id "
            + "ORDER BY al.log_id DESC";

        List<String> rows = new ArrayList<>();
        try (Connection connection = SmsApplication.getConnection();
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
                    + "\"exitTime\":\"" + SmsApplication.escapeJson(exitTime) + "\","
                    + "\"visitorName\":\"" + SmsApplication.escapeJson(resultSet.getString("visitor_name")) + "\","
                    + "\"host\":\"" + SmsApplication.escapeJson(resultSet.getString("employee_name") + " (" + resultSet.getString("department") + ")") + "\","
                    + "\"recordedBy\":\"" + SmsApplication.escapeJson(resultSet.getString("username")) + "\""
                    + "}");
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    static void handleRecordEntry(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int visitorId = SmsApplication.parseRequiredInt(form.get("visitorId"), "Visitor is required");
        int employeeId = SmsApplication.parseRequiredInt(form.get("employeeId"), "Employee is required");

        try (Connection connection = SmsApplication.getConnection()) {
            SmsApplication.requireSecurityOfficer(session);

            if (SmsApplication.hasActiveAccessLog(connection, visitorId)) {
                SmsApplication.sendJson(exchange, 409, "{\"message\":\"Visitor already has an active access record\"}");
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
            SmsApplication.recordAuditAction(connection, session.userId, "CREATE", "access_log", String.valueOf(visitorId), "Recorded visitor entry");
        }

        SmsApplication.sendJson(exchange, 201, "{\"message\":\"Entry recorded successfully\"}");
    }

    static void handleRecordExit(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int visitorId = SmsApplication.parseRequiredInt(form.get("visitorId"), "Visitor is required");
        String sql = "UPDATE access_logs SET exit_time = ? WHERE visitor_id = ? AND exit_time IS NULL ORDER BY log_id DESC LIMIT 1";

        try (Connection connection = SmsApplication.getConnection()) {
            SmsApplication.requireSecurityOfficer(session);

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setTime(1, Time.valueOf(LocalTime.now().withSecond(0).withNano(0)));
                statement.setInt(2, visitorId);
                int updated = statement.executeUpdate();

                if (updated == 0) {
                    SmsApplication.sendJson(exchange, 404, "{\"message\":\"No active record found\"}");
                    return;
                }
            }
            SmsApplication.recordAuditAction(connection, session.userId, "UPDATE", "access_log", String.valueOf(visitorId), "Recorded visitor exit");
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Exit recorded successfully\"}");
    }

    static void handleUpdate(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireSecurityOfficer(session);

        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int logId = SmsApplication.parseRequiredInt(form.get("logId"), "Access record is required");
        int visitorId = SmsApplication.parseRequiredInt(form.get("visitorId"), "Visitor is required");
        int employeeId = SmsApplication.parseRequiredInt(form.get("employeeId"), "Employee is required");
        String visitDate = SmsApplication.trim(form.get("visitDate"));
        String entryTime = SmsApplication.trim(form.get("entryTime"));
        String exitTime = SmsApplication.trim(form.get("exitTime"));

        if (visitDate.isEmpty() || entryTime.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Visit date and entry time are required\"}");
            return;
        }

        String sql = "UPDATE access_logs SET visitor_id = ?, employee_id = ?, visit_date = ?, entry_time = ?, exit_time = ? WHERE log_id = ?";
        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, visitorId);
            statement.setInt(2, employeeId);
            statement.setDate(3, Date.valueOf(visitDate));
            statement.setTime(4, Time.valueOf(SmsApplication.normalizeTime(entryTime)));
            if (exitTime.isEmpty()) {
                statement.setNull(5, java.sql.Types.TIME);
            } else {
                statement.setTime(5, Time.valueOf(SmsApplication.normalizeTime(exitTime)));
            }
            statement.setInt(6, logId);
            int updated = statement.executeUpdate();
            if (updated == 0) {
                SmsApplication.sendJson(exchange, 404, "{\"message\":\"Access record not found\"}");
                return;
            }
            SmsApplication.recordAuditAction(connection, session.userId, "UPDATE", "access_log", String.valueOf(logId), "Updated access record");
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Access record updated successfully\"}");
    }

    static void handleDelete(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireSecurityOfficer(session);

        Map<String, String> form = SmsApplication.parseFormBody(exchange);
        int logId = SmsApplication.parseRequiredInt(form.get("logId"), "Access record is required");

        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement("DELETE FROM access_logs WHERE log_id = ?")) {
            statement.setInt(1, logId);
            int deleted = statement.executeUpdate();
            if (deleted == 0) {
                SmsApplication.sendJson(exchange, 404, "{\"message\":\"Access record not found\"}");
                return;
            }
            SmsApplication.recordAuditAction(connection, session.userId, "DELETE", "access_log", String.valueOf(logId), "Deleted access record");
        }

        SmsApplication.sendJson(exchange, 200, "{\"message\":\"Access record deleted successfully\"}");
    }
}
