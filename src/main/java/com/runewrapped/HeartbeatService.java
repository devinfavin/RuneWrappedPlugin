package com.runewrapped;

// Imports
import com.google.gson.Gson;
import java.io.IOException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

@Slf4j
public class HeartbeatService {
    // ============================================================
    // Constants
    // ============================================================

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // ============================================================
    // Dependencies
    // ============================================================

    private final ScheduledExecutorService executor;
    private final OkHttpClient http;
    private final Gson gson;
    private final ClanTrackerStore store;
    private final ClanTrackerConfig config;

    // ============================================================
    // Scheduler state
    // ============================================================

    private ScheduledFuture<?> task;

    // ============================================================
    // Status (for UI)
    // ============================================================

    private volatile long lastAttemptMillis = 0L;
    private volatile long lastSuccessMillis = 0L;
    private volatile String lastError = null;

    // ============================================================
    // Construction
    // ============================================================

    public HeartbeatService(
            ScheduledExecutorService executor,
            OkHttpClient http,
            Gson gson,
            ClanTrackerStore store,
            ClanTrackerConfig config) {
        this.executor = executor;
        this.http = http;
        this.gson = gson;
        this.store = store;
        this.config = config;
    }

    // ============================================================
    // Lifecycle
    // ============================================================

    public void start() {
        // Every 5 minutes; first run after 5 minutes (we’ll also flush on logout)
        task = executor.scheduleAtFixedRate(this::tick, 5, 5, TimeUnit.MINUTES);
    }

    public void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
    }

    /**
     * Fire a heartbeat attempt immediately (runs on the executor).
     */
    public void flushNow() {
        executor.execute(this::tick);
    }

    // ============================================================
    // Heartbeat logic
    // ============================================================

    private void tick() {
        // ---- Feature toggle ----
        if (!config.enableUploads()) {
            return;
        }

        // ---- Credentials ----
        String baseUrl = config.baseUrl().trim();
        String apiKey = config.apiKey().trim();
        if (baseUrl.isEmpty() || apiKey.isEmpty()) {
            return;
        }

        // ---- Only send if we have a session + pending deltas ----
        if (!store.isSessionActive() || !store.hasPending()) {
            return;
        }

        // Track attempt time for UI/diagnostics
        lastAttemptMillis = System.currentTimeMillis();
        lastError = null;

        ClanTrackerStore.PendingBatch batch = store.snapshotPending(lastAttemptMillis);
        if (batch == null) {
            return;
        }

        HeartbeatPayload payload = new HeartbeatPayload();
        payload.schemaVersion = ClanTrackerConstants.SCHEMA_VERSION;
        payload.pluginVersion = batch.pluginVersion;
        payload.rsn = batch.rsn;
        payload.sessionId = batch.sessionId;
        payload.startedAtMillis = batch.startedAtMillis;
        payload.clientTimeMillis = batch.clientTimeMillis;
        payload.xpDelta = batch.xpDelta;
        payload.kcDelta = batch.kcDelta;
        payload.locationSamples = batch.locationSamples;

        String json = gson.toJson(payload);

        // Construct the request (OkHttp version in RuneLite expects (MediaType,
        // String))
        Request request = new Request.Builder()
                .url(baseUrl + "/v1/heartbeat")
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(JSON, json))
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                lastError = "HTTP " + response.code();
                return; // DO NOT clear pending
            }

            // Success => clear pending
            store.clearPending();
            lastSuccessMillis = System.currentTimeMillis();
        } catch (IOException e) {
            lastError = e.getClass().getSimpleName();
            log.debug("Heartbeat failed", e);
            // DO NOT clear pending
        }
    }

    // ============================================================
    // Status getters (for UI)
    // ============================================================

    public long getLastAttemptMillis() {
        return lastAttemptMillis;
    }

    public long getLastSuccessMillis() {
        return lastSuccessMillis;
    }

    public String getLastError() {
        return lastError;
    }

    // ============================================================
    // Payload DTO
    // ============================================================

    private static class HeartbeatPayload {
        int schemaVersion;
        String pluginVersion;
        String rsn;
        String sessionId;
        long startedAtMillis;
        long clientTimeMillis;
        java.util.Map<String, Long> xpDelta;
        java.util.Map<String, Integer> kcDelta;
        /** skill name (uppercase) → [[x,y,plane], ...]. Null if no samples were collected. */
        java.util.Map<String, java.util.List<int[]>> locationSamples;
    }
}
