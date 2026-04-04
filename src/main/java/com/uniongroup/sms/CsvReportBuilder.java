package com.uniongroup.sms;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

// Report exporter used by CSV downloads so report formatting stays outside the HTTP entry point.
final class CsvReportBuilder {

    private CsvReportBuilder() {
    }

    static String build(String type, String startDate, String endDate, String dbUrl, String dbUser, String dbPassword) throws SQLException {
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
            try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
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
            try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
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
            try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
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
            try (Connection connection = DriverManager.getConnection(dbUrl, dbUser, dbPassword);
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

    private static String defaultString(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
