package com.uniongroup.sms;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

// Handles admin report endpoints and CSV export.
final class ReportController {

    private ReportController() {
    }

    static void handleAccessReport(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> query = SmsApplication.parseQuery(exchange.getRequestURI());
        String startDate = SmsApplication.trim(query.get("startDate"));
        String endDate = SmsApplication.trim(query.get("endDate"));

        if (startDate.isEmpty() || endDate.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Select both start date and end date\"}");
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
        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDate(1, Date.valueOf(startDate));
            statement.setDate(2, Date.valueOf(endDate));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String exitTime = resultSet.getTime("exit_time") == null ? "" : resultSet.getTime("exit_time").toString();
                    rows.add("{"
                        + "\"id\":" + resultSet.getInt("log_id") + ","
                        + "\"date\":\"" + resultSet.getDate("visit_date") + "\","
                        + "\"visitorName\":\"" + SmsApplication.escapeJson(resultSet.getString("visitor_name")) + "\","
                        + "\"host\":\"" + SmsApplication.escapeJson(resultSet.getString("employee_name") + " (" + resultSet.getString("department") + ")") + "\","
                        + "\"entryTime\":\"" + resultSet.getTime("entry_time") + "\","
                        + "\"exitTime\":\"" + SmsApplication.escapeJson(exitTime) + "\","
                        + "\"recordedBy\":\"" + SmsApplication.escapeJson(resultSet.getString("username")) + "\""
                        + "}");
                }
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    static void handleIncidentReport(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> query = SmsApplication.parseQuery(exchange.getRequestURI());
        String startDate = SmsApplication.trim(query.get("startDate"));
        String endDate = SmsApplication.trim(query.get("endDate"));

        if (startDate.isEmpty() || endDate.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Select both start date and end date\"}");
            return;
        }

        String sql = ""
            + "SELECT i.incident_id, i.reported_at, i.title, i.incident_type, i.location, i.severity, i.status, u.username "
            + "FROM incidents i "
            + "JOIN users u ON i.user_id = u.user_id "
            + "WHERE DATE(i.reported_at) BETWEEN ? AND ? "
            + "ORDER BY i.reported_at DESC, i.incident_id DESC";

        List<String> rows = new ArrayList<>();
        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDate(1, Date.valueOf(startDate));
            statement.setDate(2, Date.valueOf(endDate));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add("{"
                        + "\"id\":" + resultSet.getInt("incident_id") + ","
                        + "\"date\":\"" + resultSet.getTimestamp("reported_at").toLocalDateTime().toLocalDate() + "\","
                        + "\"title\":\"" + SmsApplication.escapeJson(resultSet.getString("title")) + "\","
                        + "\"incidentType\":\"" + SmsApplication.escapeJson(resultSet.getString("incident_type")) + "\","
                        + "\"location\":\"" + SmsApplication.escapeJson(resultSet.getString("location")) + "\","
                        + "\"severity\":\"" + SmsApplication.escapeJson(resultSet.getString("severity")) + "\","
                        + "\"status\":\"" + SmsApplication.escapeJson(resultSet.getString("status")) + "\","
                        + "\"reportedBy\":\"" + SmsApplication.escapeJson(resultSet.getString("username")) + "\""
                        + "}");
                }
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    static void handleVisitorReport(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> query = SmsApplication.parseQuery(exchange.getRequestURI());
        String startDate = SmsApplication.trim(query.get("startDate"));
        String endDate = SmsApplication.trim(query.get("endDate"));

        if (startDate.isEmpty() || endDate.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Select both start date and end date\"}");
            return;
        }

        String sql = ""
            + "SELECT visitor_id, name, national_id, phone_number, purpose_of_visit, created_at "
            + "FROM visitors "
            + "WHERE DATE(created_at) BETWEEN ? AND ? "
            + "ORDER BY created_at DESC, visitor_id DESC";

        List<String> rows = new ArrayList<>();
        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDate(1, Date.valueOf(startDate));
            statement.setDate(2, Date.valueOf(endDate));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add("{"
                        + "\"id\":" + resultSet.getInt("visitor_id") + ","
                        + "\"date\":\"" + resultSet.getTimestamp("created_at").toLocalDateTime().toLocalDate() + "\","
                        + "\"name\":\"" + SmsApplication.escapeJson(resultSet.getString("name")) + "\","
                        + "\"nationalId\":\"" + SmsApplication.escapeJson(resultSet.getString("national_id")) + "\","
                        + "\"phoneNumber\":\"" + SmsApplication.escapeJson(resultSet.getString("phone_number")) + "\","
                        + "\"purposeOfVisit\":\"" + SmsApplication.escapeJson(resultSet.getString("purpose_of_visit")) + "\""
                        + "}");
                }
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    static void handleAuditReport(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> query = SmsApplication.parseQuery(exchange.getRequestURI());
        String startDate = SmsApplication.trim(query.get("startDate"));
        String endDate = SmsApplication.trim(query.get("endDate"));

        if (startDate.isEmpty() || endDate.isEmpty()) {
            SmsApplication.sendJson(exchange, 400, "{\"message\":\"Select both start date and end date\"}");
            return;
        }

        String sql = ""
            + "SELECT audit_id, action_type, entity_type, entity_id, details, created_at, username "
            + "FROM audit_logs "
            + "WHERE DATE(created_at) BETWEEN ? AND ? "
            + "ORDER BY created_at DESC, audit_id DESC";

        List<String> rows = new ArrayList<>();
        try (Connection connection = SmsApplication.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setDate(1, Date.valueOf(startDate));
            statement.setDate(2, Date.valueOf(endDate));

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add("{"
                        + "\"id\":" + resultSet.getInt("audit_id") + ","
                        + "\"date\":\"" + resultSet.getTimestamp("created_at").toLocalDateTime().toLocalDate() + "\","
                        + "\"actionType\":\"" + SmsApplication.escapeJson(resultSet.getString("action_type")) + "\","
                        + "\"entityType\":\"" + SmsApplication.escapeJson(resultSet.getString("entity_type")) + "\","
                        + "\"entityId\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(resultSet.getString("entity_id"), "")) + "\","
                        + "\"details\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(resultSet.getString("details"), "")) + "\","
                        + "\"username\":\"" + SmsApplication.escapeJson(SmsApplication.defaultString(resultSet.getString("username"), "System")) + "\""
                        + "}");
                }
            }
        }

        SmsApplication.sendJson(exchange, 200, "[" + String.join(",", rows) + "]");
    }

    static void handleExport(HttpExchange exchange) throws IOException, SQLException {
        SessionInfo session = SmsApplication.requireAuthenticated(exchange);
        SmsApplication.requireAdmin(session);

        Map<String, String> query = SmsApplication.parseQuery(exchange.getRequestURI());
        String type = SmsApplication.trim(query.get("type"));
        String startDate = SmsApplication.trim(query.get("startDate"));
        String endDate = SmsApplication.trim(query.get("endDate"));

        if (type.isEmpty() || startDate.isEmpty() || endDate.isEmpty()) {
            SmsApplication.sendText(exchange, 400, "Missing export parameters");
            return;
        }

        String csv = CsvReportBuilder.build(
            type,
            startDate,
            endDate,
            SmsApplication.dbUrl(),
            SmsApplication.dbUser(),
            SmsApplication.dbPassword()
        );
        byte[] payload = csv.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=UTF-8");
        exchange.getResponseHeaders().set("Content-Disposition",
            "attachment; filename=\"" + type + "-report-" + startDate + "-to-" + endDate + ".csv\"");
        exchange.sendResponseHeaders(200, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.getResponseBody().close();
    }
}
