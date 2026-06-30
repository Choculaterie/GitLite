package com.choculaterie.gitlite.network;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.CompletableFuture;

/**
 * Handles the server-side OAuth "flow" API used to link a Choculaterie account to
 * the Minecraft client.
 *
 * <p>The flow is a three-step handshake:
 * <ol>
 *   <li>Call {@link #initiateOAuthFlow} to create a flow on the server and receive
 *       a {@code flowId} and expiry.</li>
 *   <li>Redirect the user to {@link #getOAuthAuthorizeUrl} in a browser so they can
 *       sign in and approve the link.</li>
 *   <li>Poll {@link #getOAuthFlowStatus} until the status is {@code "completed"} or
 *       the flow expires. On completion, the response contains the API key.</li>
 * </ol>
 *
 * <p>All network calls are made asynchronously via Java's built-in {@link HttpClient}
 * and return {@link CompletableFuture} wrappers around the parsed JSON response. HTTP
 * 4xx/5xx responses are converted to {@link RuntimeException}s so callers can handle
 * them in the {@code exceptionally} stage.
 */
public class GitLiteFlowNetworkManager {

    private static final String BASE_URL  = "https://api.choculaterie.com";
    private static final String FLOW_PATH = "/api/SaveManagerAPI/flow";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Initiates a new OAuth flow on the server.
     *
     * @param clientName human-readable name sent to the server to identify this client;
     *                   defaults to {@code "GitLite Mod"} if {@code null}
     * @return a future resolving to a JSON object containing {@code flowId} and
     *         {@code expiresInSeconds}
     */
    public CompletableFuture<JsonObject> initiateOAuthFlow(String clientName) {
        String body = "\"" + (clientName != null ? clientName : "GitLite Mod") + "\"";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + FLOW_PATH + "/initiate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP_CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    /**
     * Polls the server for the current status of a flow.
     *
     * @param flowId the flow identifier returned by {@link #initiateOAuthFlow}
     * @return a future resolving to a JSON object containing a {@code status} field
     *         ({@code "pending"}, {@code "completed"}, {@code "cancelled"}, or {@code "expired"})
     */
    public CompletableFuture<JsonObject> getOAuthFlowStatus(String flowId) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + FLOW_PATH + "/status/" + flowId))
                .GET()
                .build();
        return HTTP_CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    /**
     * Returns the browser URL the user must visit to approve this flow.
     *
     * @param flowId the flow identifier returned by {@link #initiateOAuthFlow}
     * @return the authorization URL as a plain string
     */
    public String getOAuthAuthorizeUrl(String flowId) {
        return "https://choculaterie.com/gitlite/authorize/" + flowId;
    }

    /**
     * Cancels an in-progress flow, releasing server-side resources.
     *
     * @param flowId the flow identifier to cancel
     * @return a future resolving to the server's cancellation confirmation JSON
     */
    public CompletableFuture<JsonObject> cancelOAuthFlow(String flowId) {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + FLOW_PATH + "/cancel/" + flowId))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        return HTTP_CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApply(this::handleJsonResponse);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Validates the HTTP status code and parses the response body as JSON.
     *
     * @param response the raw HTTP response
     * @return the parsed JSON object
     * @throws RuntimeException if the status code indicates an error (4xx/5xx)
     */
    private JsonObject handleJsonResponse(HttpResponse<String> response) {
        if (response.statusCode() >= 400) {
            throw new RuntimeException("Request failed: " + response.statusCode() + " - " + response.body());
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }
}
