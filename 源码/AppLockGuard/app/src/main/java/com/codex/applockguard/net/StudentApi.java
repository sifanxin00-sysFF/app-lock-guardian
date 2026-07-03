package com.codex.applockguard.net;

import org.json.JSONObject;
import org.json.JSONArray;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class StudentApi {
    public ApiResult sync(JSONObject body) throws Exception {
        JSONObject json = post("/api/student/sync", body);
        return new ApiResult(json.optBoolean("ok"), json.optString("message", ""));
    }

    public ApiResult reportPermissions(JSONArray permissions) throws Exception {
        JSONObject body = new JSONObject();
        body.put("permissions", permissions);
        JSONObject json = post("/api/student/permissions/report", body);
        return new ApiResult(json.optBoolean("ok"), json.optString("message", ""));
    }

    public ApiResult reportGuardStart(int durationMinutes, String mode) throws Exception {
        JSONObject body = new JSONObject();
        body.put("durationMinutes", durationMinutes);
        body.put("mode", mode == null ? "focus" : mode);
        JSONObject json = post("/api/student/guard/start", body);
        return new ApiResult(json.optBoolean("ok"), json.optString("message", ""));
    }

    public ApiResult reportGuardEnd() throws Exception {
        JSONObject json = post("/api/student/guard/end", new JSONObject());
        return new ApiResult(json.optBoolean("ok"), json.optString("message", ""));
    }

    public ApiResult updateWhitelist(JSONObject body) throws Exception {
        JSONObject json = post("/api/student/whitelist/update", body);
        return new ApiResult(json.optBoolean("ok"), json.optString("message", ""));
    }

    private JSONObject post(String path, JSONObject body) throws Exception {
        HttpURLConnection connection = open(path);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream os = connection.getOutputStream();
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8))) {
            writer.write(body.toString());
        }
        return readJson(connection);
    }

    private HttpURLConnection open(String path) throws Exception {
        URL url = new URL(AppConfig.BASE_URL + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("Pragma", "no-cache");
        return connection;
    }

    private JSONObject readJson(HttpURLConnection connection) throws Exception {
        InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) {
            throw new IllegalStateException("empty response");
        }
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
            }
        }
        return new JSONObject(out.toString());
    }

    public static final class ApiResult {
        public final boolean ok;
        public final String message;

        ApiResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }
}
