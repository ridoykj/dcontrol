package com.dcontrol.controller.support;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Minimal JSON HTTP client against the running test server. */
public class Api {

    public record Response(int status, JsonNode body, java.net.http.HttpHeaders headers) {

        public Optional<String> cookie(String name) {
            return headers.allValues("Set-Cookie").stream()
                    .filter(value -> value.startsWith(name + "="))
                    .map(value -> value.substring(name.length() + 1, value.indexOf(';')))
                    .findFirst();
        }

        public String code() {
            return body.path("code").asString();
        }
    }

    public record RawResponse(int status, byte[] body, java.net.http.HttpHeaders headers) {

        public Optional<String> header(String name) {
            return headers.firstValue(name);
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private final String baseUrl;

    public Api(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    public Response get(String path, String bearer) {
        return send("GET", path, null, headers(bearer));
    }

    public Response post(String path, Object body) {
        return send("POST", path, body, Map.of());
    }

    public Response post(String path, Object body, String bearer) {
        return send("POST", path, body, headers(bearer));
    }

    public Response patch(String path, Object body, String bearer) {
        return send("PATCH", path, body, headers(bearer));
    }

    public Response delete(String path, String bearer) {
        return send("DELETE", path, null, headers(bearer));
    }

    public Response send(String method, String path, Object body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode parsed = response.body() == null || response.body().isBlank()
                    ? json.missingNode()
                    : json.readTree(response.body());
            return new Response(response.statusCode(), parsed, response.headers());
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " failed", e);
        }
    }

    /** Raw request for binary bodies (chunks, icons) and non-JSON responses. */
    public RawResponse raw(String method, String path, byte[] body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(request::header);
        try {
            HttpResponse<byte[]> response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new RawResponse(response.statusCode(), response.body(), response.headers());
        } catch (Exception e) {
            throw new IllegalStateException(method + " " + path + " failed", e);
        }
    }

    public JsonNode json(byte[] body) {
        return body == null || body.length == 0 ? json.missingNode() : json.readTree(body);
    }

    public static Map<String, String> bearer(String token) {
        return headers(token);
    }

    private static Map<String, String> headers(String bearer) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (bearer != null) {
            headers.put("Authorization", "Bearer " + bearer);
        }
        return headers;
    }
}
