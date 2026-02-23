package com.defo;

// Imports
import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.SkillIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(name = "ClanTracker", description = "Tracks boss KC and skill XP gains locally (alpha).", tags = {
		"kc", "xp", "tracker", "wrapped" })
public class ClanTrackerPlugin extends Plugin {
	// ============================================================
	// Constants
	// ============================================================

	/**
	 * Matches game messages like:
	 * "Your Vorkath kill count is: 1."
	 */
	private static final Pattern KC_PATTERN = Pattern.compile("Your (.+?) kill count is: ([0-9,]+)\\.?");

	// ============================================================
	// Injected services
	// ============================================================

	@Inject
	private Client client;
	@Inject
	private ClientToolbar clientToolbar;
	@Inject
	private ConfigManager configManager;
	@Inject
	private Gson gson;
	@Inject
	private ClanTrackerConfig config;

	@Inject
	private SkillIconManager skillIconManager;

	@Inject
	private java.util.concurrent.ScheduledExecutorService executor;
	@Inject
	private okhttp3.OkHttpClient okHttpClient;

	// ============================================================
	// Runtime state
	// ============================================================

	private BufferedImage icon;

	private HeartbeatService heartbeat;

	private ClanTrackerStore store;
	private ClanTrackerPanel panel;
	private NavigationButton navButton;

	/**
	 * Tracks last known XP per skill to compute deltas from StatChanged events.
	 */
	private final EnumMap<Skill, Integer> lastXp = new EnumMap<>(Skill.class);

	/**
	 * Tracks last known KC per boss key to compute deltas from chat messages.
	 */
	private final Map<String, Integer> lastBossKc = new HashMap<>();

	/**
	 * Used to detect transitions into/out of LOGGED_IN for session lifecycle.
	 */
	private GameState lastGameState = null;

	// ============================================================
	// Config wiring
	// ============================================================

	@Provides
	ClanTrackerConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(ClanTrackerConfig.class);
	}

	// ============================================================
	// Plugin lifecycle
	// ============================================================

	@Override
	protected void startUp() {
		// ---- Load persisted data first ----
		store = new ClanTrackerStore();
		store.load(configManager, gson);

		// ---- Background services ----
		heartbeat = new HeartbeatService(executor, okHttpClient, gson, store, config);
		heartbeat.start();

		// ---- UI wiring ----
		icon = loadIcon();
		panel = new ClanTrackerPanel(store, skillIconManager);

		// Status supplier for heartbeat visibility in the panel.
		panel.setHeartbeatStatusSupplier(() -> {
			ClanTrackerPanel.HeartbeatStatus s = new ClanTrackerPanel.HeartbeatStatus();
			s.uploadsEnabled = config.enableUploads();
			s.sessionActive = store.isSessionActive();
			s.hasPending = store.hasPending();

			if (heartbeat != null) {
				s.lastAttemptMillis = heartbeat.getLastAttemptMillis();
				s.lastSuccessMillis = heartbeat.getLastSuccessMillis();
				s.lastError = heartbeat.getLastError();
			}

			return s;
		});

		// Export handler for last session JSON (clipboard).
		panel.setExportLastSessionHandler(() -> {
			ClanTrackerStore.SessionSummary s = store.getLastSession();
			if (s == null) {
				return;
			}

			String json = gson.toJson(s);
			java.awt.Toolkit.getDefaultToolkit()
					.getSystemClipboard()
					.setContents(new java.awt.datatransfer.StringSelection(json), null);
		});

		// Add the panel to RuneLite sidebar navigation.
		navButton = NavigationButton.builder()
				.tooltip("ClanTracker")
				.icon(icon)
				.panel(panel)
				.build();

		clientToolbar.addNavigation(navButton);

		// Force an initial repaint/update.
		SwingUtilities.invokeLater(panel::refresh);

		if (config.debug()) {
			log.debug("ClanTracker started");
		}
	}

	@Override
	protected void shutDown() {
		// Persist local data.
		if (store != null) {
			store.save(configManager, gson);
		}

		// Remove navigation button.
		if (navButton != null) {
			clientToolbar.removeNavigation(navButton);
		}

		// Stop background services.
		if (heartbeat != null) {
			heartbeat.stop();
		}

		if (config.debug()) {
			log.debug("ClanTracker stopped");
		}
	}

	// ============================================================
	// Event subscriptions
	// ============================================================

	/**
	 * Tracks skill XP gains by reading the client's current XP for the skill,
	 * then diffing against the last observed value.
	 */
	@Subscribe
	public void onStatChanged(StatChanged event) {
		if (!config.trackXP()) {
			return;
		}

		Skill skill = event.getSkill();
		int xp = client.getSkillExperience(skill);

		int prev = lastXp.getOrDefault(skill, xp);
		int delta = xp - prev;

		// First event for a skill will delta=0 (prev=xp). That’s fine.
		if (delta > 0) {
			store.addXp(LocalDate.now(), skill, delta);
			store.save(configManager, gson); // v0.1: eager save; we can batch later

			store.addSessionXp(skill, delta);

			SwingUtilities.invokeLater(panel::refresh);

			if (config.debug()) {
				log.debug("XP +{} {}", delta, skill);
			}
		}

		lastXp.put(skill, xp);
	}

	/**
	 * Parses KC updates from chat messages and records the delta.
	 */
	@Subscribe
	public void onChatMessage(ChatMessage event) {
		if (!config.trackBossKC()) {
			return;
		}

		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE && type != ChatMessageType.SPAM) {
			return;
		}

		String msg = Text.removeTags(event.getMessage());
		Matcher m = KC_PATTERN.matcher(msg);
		if (!m.find()) {
			return;
		}

		String bossRaw = m.group(1);
		String bossKey = normalizeBossKey(bossRaw);

		int kc = Integer.parseInt(m.group(2).replace(",", ""));

		Integer prev = lastBossKc.get(bossKey);
		if (prev != null && kc > prev) {
			int delta = kc - prev;

			// Session (unverified/live) tracking
			store.addSessionKc(bossKey, delta);

			// Daily (persisted) tracking
			store.addKc(LocalDate.now(), bossKey, delta);
			store.save(configManager, gson);

			// UI updates
			panel.setActiveBossKey(bossKey); // show the most recent boss automatically
			SwingUtilities.invokeLater(panel::refresh);

			if (config.debug()) {
				log.debug("KC +{} {}", delta, bossKey);
			}
		}

		lastBossKc.put(bossKey, kc);
	}

	/**
	 * Starts a session on login, ends it on logout.
	 * Also attempts a last heartbeat flush before ending session.
	 */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		GameState gs = event.getGameState();

		// Transition into LOGGED_IN => start session
		if (gs == GameState.LOGGED_IN && lastGameState != GameState.LOGGED_IN) {
			store.startSession(System.currentTimeMillis());
			SwingUtilities.invokeLater(panel::refresh);
		}

		// Transition out of LOGGED_IN => flush heartbeat + end session
		if (lastGameState == GameState.LOGGED_IN && gs != GameState.LOGGED_IN) {
			// attempt one last flush, then end session
			if (heartbeat != null) {
				heartbeat.flushNow();
			}

			store.endSession(System.currentTimeMillis());

			// persist last session
			store.save(configManager, gson);

			SwingUtilities.invokeLater(panel::refresh);
		}

		lastGameState = gs;
	}

	/**
	 * Refresh panel immediately when config values change (uploads toggle, URL,
	 * etc.).
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged e) {
		if (!e.getGroup().equals(ClanTrackerConfig.GROUP)) {
			return;
		}

		if (panel != null) {
			SwingUtilities.invokeLater(panel::refresh);
		}
	}

	// ============================================================
	// Helpers
	// ============================================================

	private static String normalizeBossKey(String bossName) {
		// Keep it simple for v0.1; we’ll harden this later.
		return bossName.trim().toLowerCase().replace(' ', '_');
	}

	private BufferedImage loadIcon() {
		try {
			BufferedImage img = ImageUtil.loadImageResource(ClanTrackerPlugin.class, "panel_icon.png");
			if (img != null) {
				return img;
			}
		} catch (Exception e) {
			log.warn("Failed to load panel_icon.png, using fallback icon", e);
		}

		return new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
	}
}