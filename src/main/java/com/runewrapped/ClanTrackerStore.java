package com.runewrapped;

import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;

@Slf4j
public class ClanTrackerStore {
	// ============================================================
	// Persistence
	// ============================================================

	private static final String STORAGE_KEY = "data_v1";

	private static class Persisted {
		String lastRsnKey;
		Map<String, PlayerData> players;
	}

	private static class PlayerData {
		Map<String, Map<String, Integer>> kcByDay = new HashMap<>();
		Map<String, Map<String, Long>> xpByDay = new HashMap<>(); // skillName -> (date -> xp)
		SessionSummary lastSession = null;
	}

	// ============================================================
	// Runtime persisted state (per-RSN)
	// ============================================================

	private final Map<String, PlayerData> players = new HashMap<>();
	private String currentRsnKey = "unknown";
	private String lastActiveRsnKey = "unknown";

	// ============================================================
	// Initializer
	// ============================================================

	private ConfigManager configManager;
	private Gson gson;

	public synchronized void init(ConfigManager configManager, Gson gson) {
		this.configManager = configManager;
		this.gson = gson;
		load(configManager, gson);
	}

	private void persist() {
		if (configManager == null || gson == null) {
			return;
		}
		save(configManager, gson);
	}

	// ============================================================
	// RSN routing helpers
	// ============================================================

	private static String normalizeRsnKey(String rsn) {
		if (rsn == null) {
			return "unknown";
		}
		String t = rsn.trim();
		if (t.isEmpty()) {
			return "unknown";
		}
		return t.toLowerCase();
	}

	private PlayerData currentPlayer() {
		return players.computeIfAbsent(currentRsnKey, k -> new PlayerData());
	}

	public synchronized void setCurrentRsn(String rsn) {
		String newKey = normalizeRsnKey(rsn);
		if (newKey.equals(currentRsnKey)) {
			return;
		}

		// If we had data under "unknown", merge it into the real rsn on first resolve
		if ("unknown".equals(currentRsnKey) && !"unknown".equals(newKey)) {
			PlayerData unknown = players.get("unknown");
			if (unknown != null) {
				PlayerData dest = players.computeIfAbsent(newKey, k -> new PlayerData());

				// merge xp
				for (Map.Entry<String, Map<String, Long>> e : unknown.xpByDay.entrySet()) {
					Map<String, Long> destDay = dest.xpByDay.computeIfAbsent(e.getKey(), k -> new HashMap<>());
					for (Map.Entry<String, Long> d : e.getValue().entrySet()) {
						destDay.put(d.getKey(), destDay.getOrDefault(d.getKey(), 0L) + d.getValue());
					}
				}

				// merge kc
				for (Map.Entry<String, Map<String, Integer>> e : unknown.kcByDay.entrySet()) {
					Map<String, Integer> destDay = dest.kcByDay.computeIfAbsent(e.getKey(), k -> new HashMap<>());
					for (Map.Entry<String, Integer> d : e.getValue().entrySet()) {
						destDay.put(d.getKey(), destDay.getOrDefault(d.getKey(), 0) + d.getValue());
					}
				}

				// keep unknown lastSession only if dest doesn't have one
				if (dest.lastSession == null) {
					dest.lastSession = unknown.lastSession;
				}

				players.remove("unknown");
			}
		}

		currentRsnKey = newKey;
		if (!"unknown".equals(newKey)) {
			lastActiveRsnKey = newKey;
		}
		persist();
	}

	// ============================================================
	// Public API - rolling (daily), per current RSN
	// ============================================================

	public synchronized void addXp(LocalDate day, Skill skill, long delta) {
		String d = day.toString();
		PlayerData p = currentPlayer();
		Map<String, Long> dayMap = p.xpByDay.computeIfAbsent(skill.name(), k -> new HashMap<>());
		dayMap.put(d, dayMap.getOrDefault(d, 0L) + delta);
		persist();
	}

	public synchronized void addKc(LocalDate day, String bossKey, int delta) {
		String d = day.toString();
		PlayerData p = currentPlayer();
		Map<String, Integer> dayMap = p.kcByDay.computeIfAbsent(bossKey, k -> new HashMap<>());
		dayMap.put(d, dayMap.getOrDefault(d, 0) + delta);
		persist();
	}

	public synchronized long getXpRollingDays(LocalDate today, Skill skill, int days) {
		PlayerData p = currentPlayer();
		Map<String, Long> dayMap = p.xpByDay.get(skill.name());
		if (dayMap == null) {
			return 0;
		}

		long total = 0;
		for (int i = 0; i < days; i++) {
			String d = today.minusDays(i).toString();
			total += dayMap.getOrDefault(d, 0L);
		}
		return total;
	}

	public synchronized int getKcRollingDays(LocalDate today, String bossKey, int days) {
		PlayerData p = currentPlayer();
		Map<String, Integer> dayMap = p.kcByDay.get(bossKey);
		if (dayMap == null) {
			return 0;
		}

		int total = 0;
		for (int i = 0; i < days; i++) {
			String d = today.minusDays(i).toString();
			total += dayMap.getOrDefault(d, 0);
		}
		return total;
	}

	public synchronized Map<String, Map<String, Integer>> getAllKcByDay() {
		return currentPlayer().kcByDay;
	}

	// Compatibility: UI expects EnumMap<Skill,...>
	public synchronized EnumMap<Skill, Map<String, Long>> getAllXpByDay() {
		EnumMap<Skill, Map<String, Long>> out = new EnumMap<>(Skill.class);
		Map<String, Map<String, Long>> raw = currentPlayer().xpByDay;

		for (Map.Entry<String, Map<String, Long>> e : raw.entrySet()) {
			try {
				Skill s = Skill.valueOf(e.getKey());
				out.put(s, e.getValue());
			} catch (Exception ignored) {
			}
		}
		return out;
	}

	// ============================================================
	// Persistence helpers
	// ============================================================

	public synchronized void load(ConfigManager configManager, Gson gson) {
		try {
			String json = configManager.getConfiguration(ClanTrackerConfig.GROUP, STORAGE_KEY);
			if (json == null || json.isBlank()) {
				if (log.isDebugEnabled()) {
					log.debug("ClanTracker load: no persisted JSON found for {}.{}", ClanTrackerConfig.GROUP, STORAGE_KEY);
				}
				return;
			}

			Persisted p = gson.fromJson(json, Persisted.class);
			if (p == null || p.players == null) {
				log.warn("ClanTracker load: parsed payload missing players map; keeping in-memory state");
				return;
			}

			Map<String, PlayerData> loadedPlayers = sanitizePlayersMap(p.players);
			if (loadedPlayers.isEmpty()) {
				log.warn("ClanTracker load: parsed players map was empty/invalid; keeping in-memory state");
				return;
			}

			players.clear();
			players.putAll(loadedPlayers);

			lastActiveRsnKey = normalizeRsnKey(p.lastRsnKey);
			if ("unknown".equals(lastActiveRsnKey)) {
				String candidate = firstNonUnknownPlayerKey(loadedPlayers);
				if (candidate != null) {
					lastActiveRsnKey = candidate;
				}
			}

			// Default to last active RSN bucket on startup (prevents "looks empty" after
			// restart)
			if (players.containsKey(lastActiveRsnKey)) {
				currentRsnKey = lastActiveRsnKey;
			} else if (players.containsKey("unknown")) {
				currentRsnKey = "unknown";
			} else {
				currentRsnKey = loadedPlayers.keySet().iterator().next();
			}

			if (log.isDebugEnabled()) {
				log.debug(
						"ClanTracker load: restored {} player buckets, currentRsnKey='{}', lastActiveRsnKey='{}', jsonLen={}",
						players.size(),
						currentRsnKey,
						lastActiveRsnKey,
						json.length());
			}
		} catch (Exception e) {
			log.error("ClanTracker load failed; keeping existing in-memory state", e);
		}
	}

	public synchronized void save(ConfigManager configManager, Gson gson) {
		Persisted p = new Persisted();
		p.players = players;
		p.lastRsnKey = "unknown".equals(lastActiveRsnKey) ? currentRsnKey : lastActiveRsnKey;

		String payload = gson.toJson(p);
		configManager.setConfiguration(ClanTrackerConfig.GROUP, STORAGE_KEY, payload);

		if (log.isDebugEnabled()) {
			String stored = configManager.getConfiguration(ClanTrackerConfig.GROUP, STORAGE_KEY);
			log.debug(
					"ClanTracker save: wrote {}.{}, jsonLen={}, storedLen={}, currentRsnKey='{}', lastActiveRsnKey='{}'",
					ClanTrackerConfig.GROUP,
					STORAGE_KEY,
					payload.length(),
					stored == null ? 0 : stored.length(),
					currentRsnKey,
					lastActiveRsnKey);
		}
	}

	private static Map<String, PlayerData> sanitizePlayersMap(Map<String, PlayerData> raw) {
		Map<String, PlayerData> out = new LinkedHashMap<>();
		for (Map.Entry<String, PlayerData> e : raw.entrySet()) {
			String key = normalizeRsnKey(e.getKey());
			PlayerData value = e.getValue();
			if (value == null) {
				value = new PlayerData();
			}
			if (value.kcByDay == null) {
				value.kcByDay = new HashMap<>();
			}
			if (value.xpByDay == null) {
				value.xpByDay = new HashMap<>();
			}
			out.put(key, value);
		}
		return out;
	}

	private static String firstNonUnknownPlayerKey(Map<String, PlayerData> map) {
		for (String key : map.keySet()) {
			if (!"unknown".equals(key)) {
				return key;
			}
		}
		return null;
	}

	private static Map<String, Long> toSkillNameMap(EnumMap<Skill, Long> input) {
		Map<String, Long> out = new HashMap<>();
		for (Map.Entry<Skill, Long> e : input.entrySet()) {
			out.put(e.getKey().name(), e.getValue());
		}
		return out;
	}

	// ============================================================
	// Session models
	// ============================================================

	public static class SessionSummary {
		public String sessionId;
		public long startedAtMillis;
		public long endedAtMillis;
		public Map<String, Long> xpGained;
		public Map<String, Integer> kcGained;
		public int schemaVersion;
		public String pluginVersion;
		public String rsn;
	}

	public static class PendingBatch {
		public String sessionId;
		public long startedAtMillis;
		public long clientTimeMillis;
		public Map<String, Long> xpDelta;
		public Map<String, Integer> kcDelta;
		/** skill name (uppercase) → list of [x, y, plane] samples. Null if none recorded. */
		public Map<String, List<int[]>> locationSamples;
		public int schemaVersion;
		public String pluginVersion;
		public String rsn;
	}

	// ============================================================
	// Session tracking (unverified/live) - not persisted
	// ============================================================

	private boolean sessionActive = false;
	private String sessionId = null;
	private long sessionStartMillis = 0L;
	private String sessionRsn = null;
	private String sessionPluginVersion = null;

	private final EnumMap<Skill, Long> sessionXp = new EnumMap<>(Skill.class);
	private final Map<String, Integer> sessionKc = new HashMap<>();

	private final EnumMap<Skill, Long> pendingXp = new EnumMap<>(Skill.class);
	private final Map<String, Integer> pendingKc = new HashMap<>();
	/** Skill.name() → list of [x, y, plane] samples captured during this pending window. */
	private final Map<String, List<int[]>> pendingLocationSamples = new HashMap<>();

	private static final int MAX_SAMPLES_PER_SKILL = 20;

	public synchronized void startSession(long nowMillis, String rsn, String pluginVersion) {
		sessionActive = true;
		sessionId = java.util.UUID.randomUUID().toString();
		sessionStartMillis = nowMillis;
		sessionRsn = rsn;
		sessionPluginVersion = pluginVersion;

		// Route future rolling data into the correct RSN bucket as soon as we can
		setCurrentRsn(rsn);

		sessionXp.clear();
		sessionKc.clear();
		pendingXp.clear();
		pendingKc.clear();
		pendingLocationSamples.clear();
	}

	public synchronized SessionSummary endSession(long nowMillis) {
		if (!sessionActive) {
			return null;
		}

		SessionSummary s = new SessionSummary();
		s.schemaVersion = ClanTrackerConstants.SCHEMA_VERSION;
		s.pluginVersion = sessionPluginVersion;
		s.rsn = (sessionRsn == null || sessionRsn.isBlank()) ? "unknown" : sessionRsn;
		s.sessionId = sessionId;
		s.startedAtMillis = sessionStartMillis;
		s.endedAtMillis = nowMillis;
		s.xpGained = toSkillNameMap(sessionXp);
		s.kcGained = new HashMap<>(sessionKc);

		currentPlayer().lastSession = s;
		persist();

		sessionActive = false;
		sessionId = null;
		sessionStartMillis = 0L;
		sessionXp.clear();
		sessionKc.clear();
		sessionRsn = null;
		sessionPluginVersion = null;

		return s;
	}

	public synchronized boolean isSessionActive() {
		return sessionActive;
	}

	public synchronized String getSessionId() {
		return sessionId;
	}

	public synchronized long getSessionTotalXp() {
		long sum = 0;
		for (long v : sessionXp.values()) {
			sum += v;
		}
		return sum;
	}

	public synchronized long getSessionXp(Skill skill) {
		return sessionXp.getOrDefault(skill, 0L);
	}

	public synchronized void setSessionRsnIfMissing(String rsn) {
		if (rsn == null || rsn.isBlank()) {
			return;
		}

		if (sessionRsn == null || sessionRsn.isBlank() || "unknown".equalsIgnoreCase(sessionRsn)) {
			sessionRsn = rsn;
		}
	}

	/**
	 * Records a world location sample for the given skill.
	 * Capped at MAX_SAMPLES_PER_SKILL to keep payload size bounded.
	 * Safe to call from the client thread.
	 */
	public synchronized void recordLocationSample(Skill skill, int x, int y, int plane) {
		if (!sessionActive) {
			return;
		}
		List<int[]> samples = pendingLocationSamples.computeIfAbsent(skill.name(), k -> new ArrayList<>());
		if (samples.size() < MAX_SAMPLES_PER_SKILL) {
			samples.add(new int[]{x, y, plane});
		}
	}

	public synchronized void addSessionXp(Skill skill, long delta) {
		if (!sessionActive || delta <= 0) {
			return;
		}

		sessionXp.put(skill, sessionXp.getOrDefault(skill, 0L) + delta);
		pendingXp.put(skill, pendingXp.getOrDefault(skill, 0L) + delta);
	}

	public synchronized void addSessionKc(String bossKey, int delta) {
		if (!sessionActive || delta <= 0) {
			return;
		}

		sessionKc.put(bossKey, sessionKc.getOrDefault(bossKey, 0) + delta);
		pendingKc.put(bossKey, pendingKc.getOrDefault(bossKey, 0) + delta);
	}

	public synchronized PendingBatch snapshotPending(long nowMillis) {
		if (!sessionActive) {
			return null;
		}

		PendingBatch b = new PendingBatch();
		b.schemaVersion = ClanTrackerConstants.SCHEMA_VERSION;
		b.pluginVersion = sessionPluginVersion;
		b.rsn = sessionRsn;
		b.sessionId = sessionId;
		b.startedAtMillis = sessionStartMillis;
		b.clientTimeMillis = nowMillis;
		b.xpDelta = toSkillNameMap(pendingXp);
		b.kcDelta = new HashMap<>(pendingKc);
		b.locationSamples = pendingLocationSamples.isEmpty() ? null : deepCopyLocationSamples();

		return b;
	}

	public synchronized boolean hasPending() {
		return !pendingXp.isEmpty() || !pendingKc.isEmpty();
	}

	public synchronized void clearPending() {
		pendingXp.clear();
		pendingKc.clear();
		pendingLocationSamples.clear();
	}

	private Map<String, List<int[]>> deepCopyLocationSamples() {
		Map<String, List<int[]>> copy = new HashMap<>();
		for (Map.Entry<String, List<int[]>> e : pendingLocationSamples.entrySet()) {
			copy.put(e.getKey(), new ArrayList<>(e.getValue()));
		}
		return copy;
	}

	public synchronized SessionSummary getLastSession() {
		return currentPlayer().lastSession;
	}

	public synchronized void setLastSession(SessionSummary s) {
		currentPlayer().lastSession = s;
		persist();
	}
}
