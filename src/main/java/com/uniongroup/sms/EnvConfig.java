package com.uniongroup.sms;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// Loads configuration from the real environment first, then from a local .env file when present.
final class EnvConfig {

    private static final Map<String, String> DOT_ENV = loadDotEnv();

    private EnvConfig() {
    }

    static String get(String key) {
        String systemValue = System.getenv(key);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }

        String fileValue = DOT_ENV.get(key);
        if (fileValue != null && !fileValue.isBlank()) {
            return fileValue;
        }

        return null;
    }

    private static Map<String, String> loadDotEnv() {
        Map<String, String> values = new HashMap<>();
        Path path = Path.of(".env");
        if (!Files.exists(path)) {
            return values;
        }

        try {
            List<String> lines = Files.readAllLines(path);
            for (String rawLine : lines) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                int separator = line.indexOf('=');
                if (separator <= 0) {
                    continue;
                }

                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                values.put(key, stripQuotes(value));
            }
        } catch (IOException ignored) {
            // If .env cannot be read, startup will still fail clearly when a required key is missing.
        }

        return values;
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2) {
            boolean doubleQuoted = value.startsWith("\"") && value.endsWith("\"");
            boolean singleQuoted = value.startsWith("'") && value.endsWith("'");
            if (doubleQuoted || singleQuoted) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
