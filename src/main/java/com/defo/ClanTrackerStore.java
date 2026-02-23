package com.defo;

// Imports
import com.google.gson.Gson;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;

public class ClanTrackerStore {
	// ============================================================
	// Persistence
	// ============================================================

	private static final String STORAGE_KEY = "data_v1";

	/**
	 * Persisted payload container (stored as JSON in ConfigManager).
	 * Note: xpByDay uses Map (not EnumMap) to avoid Gson EnumMap issues.
	 */
	private static class Persisted {
		Map<String, Map<String, Integer>> kcByDay;
		Map<Skill, Map<String, Long>> xpByDay;
		SessionSummary lastSession;
	}

	// ============================================================
	// Rolling (daily) tracking - persisted
	// ============================================================

	/**
	 * skill -> (date -> xpGained)
	 */
	private final EnumMap<Skill, Map<String, Long>> xpByDay = new EnumMap<>(Skill.class);

	/**
	 * bossKey -> (date -> kcGained)
	 */
	private final Map<String, Map<String, Integer>> kcByDay = new HashMap<>();

	// ============================================================
	// Session tracking (unverified/live) - not persisted
	// ============================================================

	private boolean sessionActive = false;
	private String sessionId = null;
	private long sessionStartMillis = 0L;

	/**
	 * Totals for current session.
	 */
	private final EnumMap<Skill, Long> sessionXp = new EnumMap<>(Skill.class);
	private final Map<String, Integer> sessionKc = new HashMap<>();

	/**
	 * Pending deltas since last heartbeat (only cleared on successful upload).
	 */
	private final EnumMap<Skill, Long> pendingXp = new EnumMap<>(Skill.class);
	private final Map<String, Integer> pendingKc = new HashMap<>();

	/**
	 * Last completed session summary (persisted via Persisted.lastSession).
	 */
	private SessionSummary lastSession = null;

	// ============================================================
	// Public API - rolling (daily)
	// ============================================================

	public void addXp(LocalDate day, Skill skill, long delta) {
		String d = day.toString();
		Map<String, Long> dayMap = xpByDay.computeIfAbsent(skill, k -> new HashMap<>());
		dayMap.put(d, dayMap.getOrDefault(d, 0L) + delta);
	}

	public void addKc(LocalDate day, String bossKey, int delta) {
		String d = day.toString();
		Map<String, Integer> dayMap = kcByDay.computeIfAbsent(bossKey, k -> new HashMap<>());
		dayMap.put(d, dayMap.getOrDefault(d, 0) + delta);
	}

	public long getXpRollingDays(LocalDate today, Skill skill, int days) {
		Map<String, Long> dayMap = xpByDay.get(skill);
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

	public int getKcRollingDays(LocalDate today, String bossKey, int days) {
		Map<String, Integer> dayMap = kcByDay.get(bossKey);
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

	public Map<String, Map<String, Integer>> getAllKcByDay() {
		return kcByDay;
	}

	public EnumMap<Skill, Map<String, Long>> getAllXpByDay() {
		return xpByDay;
	}

	// ============================================================
	// Persistence helpers
	// ============================================================

	public void load(ConfigManager configManager, Gson gson) {
		try {
			String json = configManager.getConfiguration(ClanTrackerConfig.GROUP, STORAGE_KEY);
			if (json == null || json.isBlank()) {
				return;
			}

			Persisted p = gson.fromJson(json, Persisted.class);
			if (p == null) {
				return;
			}

			kcByDay.clear();
			xpByDay.clear();

			if (p.kcByDay != null) {
				kcByDay.putAll(p.kcByDay);
			}

			if (p.xpByDay != null) {
				// xpByDay is an EnumMap; putAll is fine
				xpByDay.putAll(p.xpByDay);
			}

			if (p.lastSession != null) {
				lastSession = p.lastSession;
			}
		} catch (Exception e) {
			// Never brick plugin enablement because stored data changed
			kcByDay.clear();
			xpByDay.clear();
			lastSession = null;
			configManager.unsetConfiguration(ClanTrackerConfig.GROUP, STORAGE_KEY);
		}
	}

	public void save(ConfigManager configManager, Gson gson) {
		Persisted p = new Persisted();
		p.kcByDay = kcByDay;
		p.xpByDay = xpByDay;
		p.lastSession = lastSession;

		configManager.setConfiguration(ClanTrackerConfig.GROUP, STORAGE_KEY, gson.toJson(p));
	}

	// ============================================================
	// Session models
	// ============================================================

	public static class SessionSummary {
		public String sessionId;
		public long startedAtMillis;
		public long endedAtMillis; // 0 while active
		public EnumMap<Skill, Long> xpGained;
		public Map<String, Integer> kcGained;
	}

	public static class PendingBatch {
		public String sessionId;
		public long startedAtMillis;
		public long clientTimeMillis;
		public EnumMap<Skill, Long> xpDelta;
		public Map<String, Integer> kcDelta;
	}

	// ============================================================
	// Session lifecycle + session totals
	// ============================================================

	public synchronized void startSession(long nowMillis) {
		sessionActive = true;
		sessionId = java.util.UUID.randomUUID().toString();
		sessionStartMillis = nowMillis;

		sessionXp.clear();
		sessionKc.clear();
		pendingXp.clear();
		pendingKc.clear();
	}

	public synchronized SessionSummary endSession(long nowMillis) {
		if (!sessionActive) {
			return null;
		}

		SessionSummary s = new SessionSummary();
		s.sessionId = sessionId;
		s.startedAtMillis = sessionStartMillis;
		s.endedAtMillis = nowMillis;
		s.xpGained = new EnumMap<>(sessionXp);
		s.kcGained = new HashMap<>(sessionKc);

		lastSession = s;

		// Reset active session state (but do NOT clear pending here)
		sessionActive = false;
		sessionId = null;
		sessionStartMillis = 0L;
		sessionXp.clear();
		sessionKc.clear();

		// We purposely do NOT clear pending here.
		// We'll flush a final heartbeat attempt on logout in the plugin.
		return s;
	}

	public synchronized boolean isSessionActive() {
		return sessionActive;
	}

	public synchronized String getsessionId() {
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

	// ============================================================
	// Session accumulation
	// ============================================================

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

	// ============================================================
	// Heartbeat batching
	// ============================================================

	public synchronized PendingBatch snapshotPending(long nowMillis) {
		if (!sessionActive) {
			return null;
		}

		PendingBatch b = new PendingBatch();
		b.sessionId = sessionId;
		b.startedAtMillis = sessionStartMillis;
		b.clientTimeMillis = nowMillis;
		b.xpDelta = new EnumMap<>(pendingXp);
		b.kcDelta = new HashMap<>(pendingKc);

		return b;
	}

	public synchronized boolean hasPending() {
		return !pendingXp.isEmpty() || !pendingKc.isEmpty();
	}

	public synchronized void clearPending() {
		pendingXp.clear();
		pendingKc.clear();
	}

	// ============================================================
	// Last session summary (export)
	// ============================================================

	public synchronized SessionSummary getLastSession() {
		return lastSession;
	}

	public synchronized void setLastSession(SessionSummary s) {
		lastSession = s;
	}
}