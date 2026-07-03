package com.codex.applockguard.net;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public final class ApprovalApi {
    public boolean health() throws Exception {
        JSONObject json = get("/health");
        return json.optBoolean("ok");
    }

    public BootstrapResult bootstrap(String deviceId, String deviceSecret, String deviceName) throws Exception {
        JSONObject body = new JSONObject();
        body.put("deviceId", deviceId);
        body.put("deviceSecret", deviceSecret);
        body.put("deviceName", deviceName);
        JSONObject json = post("/api/app/bootstrap", body);
        return new BootstrapResult(
                json.optBoolean("ok"),
                json.optBoolean("guardianConfigured"),
                json.optString("dashboardUrl"),
                json.optString("activationUrl")
        );
    }

    public CreateRequestResult createRequest(
            String deviceId,
            String deviceSecret,
            String requestType,
            String targetPackage,
            String targetLabel,
            int requestedMinutes,
            String requestReason
    ) throws Exception {
        JSONObject body = new JSONObject();
        body.put("deviceId", deviceId);
        body.put("deviceSecret", deviceSecret);
        body.put("requestType", requestType);
        body.put("targetPackage", targetPackage == null ? JSONObject.NULL : targetPackage);
        body.put("targetLabel", targetLabel == null ? JSONObject.NULL : targetLabel);
        body.put("requestedMinutes", requestedMinutes);
        body.put("requestReason", requestReason == null ? JSONObject.NULL : requestReason);
        JSONObject json = post("/api/app/requests", body);
        return new CreateRequestResult(
                json.optBoolean("ok"),
                json.optString("requestId"),
                json.optBoolean("reused")
        );
    }

    public CreateRequestResult createStudentReleaseRequest(
            String packageName,
            String appName,
            int durationMinutes,
            String reason
    ) throws Exception {
        JSONObject body = new JSONObject();
        body.put("requestType", "app_block");
        body.put("appId", packageName == null ? JSONObject.NULL : packageName);
        body.put("packageName", packageName == null ? JSONObject.NULL : packageName);
        body.put("appName", appName == null ? JSONObject.NULL : appName);
        body.put("durationMinutes", durationMinutes);
        body.put("reason", reason == null || reason.trim().isEmpty() ? "请求临时使用该应用" : reason);
        JSONObject json = post("/api/student/release/request", body);
        return new CreateRequestResult(
                json.optBoolean("ok"),
                json.optString("requestId"),
                false
        );
    }

    public RequestStatusResult getRequestStatus(String requestId, String deviceId, String deviceSecret) throws Exception {
        JSONObject json = get("/api/app/requests/" + requestId
                + "/status?deviceId=" + encode(deviceId)
                + "&deviceSecret=" + encode(deviceSecret));
        return new RequestStatusResult(
                json.optBoolean("ok"),
                json.optString("status"),
                json.optInt("approvedMinutes", 0),
                json.optString("approvedMode", "minutes"),
                json.optString("guardianNote")
        );
    }

    public RequestStatusResult getStudentReleaseRequestStatus(String requestId) throws Exception {
        JSONObject json = get("/api/student/release/request/" + encode(requestId));
        return new RequestStatusResult(
                json.optBoolean("ok"),
                json.optString("status"),
                json.optInt("durationMinutes", 0),
                "minutes",
                json.optString("description")
        );
    }

    public CommandResult getNextCommand(String deviceId, String deviceSecret) throws Exception {
        JSONObject json = get("/api/app/commands/next?deviceId=" + encode(deviceId)
                + "&deviceSecret=" + encode(deviceSecret));
        return new CommandResult(
                json.optBoolean("ok"),
                json.optBoolean("hasCommand"),
                json.optString("commandId"),
                json.optString("commandType"),
                json.optString("targetPackage"),
                json.optString("payloadText")
        );
    }

    public boolean markCommandApplied(
            String commandId,
            String deviceId,
            String deviceSecret,
            boolean success,
            String message
    ) throws Exception {
        JSONObject body = new JSONObject();
        body.put("deviceId", deviceId);
        body.put("deviceSecret", deviceSecret);
        body.put("resultStatus", success ? "success" : "failed");
        body.put("resultMessage", message == null ? JSONObject.NULL : message);
        JSONObject json = post("/api/app/commands/" + commandId + "/applied", body);
        return json.optBoolean("ok");
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

    private JSONObject get(String path) throws Exception {
        HttpURLConnection connection = open(path);
        connection.setRequestMethod("GET");
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

    private String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    public static final class BootstrapResult {
        public final boolean ok;
        public final boolean guardianConfigured;
        public final String dashboardUrl;
        public final String activationUrl;

        public BootstrapResult(boolean ok, boolean guardianConfigured, String dashboardUrl, String activationUrl) {
            this.ok = ok;
            this.guardianConfigured = guardianConfigured;
            this.dashboardUrl = dashboardUrl;
            this.activationUrl = activationUrl;
        }
    }

    public static final class CreateRequestResult {
        public final boolean ok;
        public final String requestId;
        public final boolean reused;

        public CreateRequestResult(boolean ok, String requestId, boolean reused) {
            this.ok = ok;
            this.requestId = requestId;
            this.reused = reused;
        }
    }

    public static final class RequestStatusResult {
        public final boolean ok;
        public final String status;
        public final int approvedMinutes;
        public final String approvedMode;
        public final String guardianNote;

        public RequestStatusResult(boolean ok, String status, int approvedMinutes, String approvedMode, String guardianNote) {
            this.ok = ok;
            this.status = status;
            this.approvedMinutes = approvedMinutes;
            this.approvedMode = approvedMode;
            this.guardianNote = guardianNote;
        }
    }

    public static final class CommandResult {
        public final boolean ok;
        public final boolean hasCommand;
        public final String commandId;
        public final String commandType;
        public final String targetPackage;
        public final String payloadText;

        public CommandResult(boolean ok, boolean hasCommand, String commandId, String commandType, String targetPackage, String payloadText) {
            this.ok = ok;
            this.hasCommand = hasCommand;
            this.commandId = commandId;
            this.commandType = commandType;
            this.targetPackage = targetPackage;
            this.payloadText = payloadText;
        }
    }
}
