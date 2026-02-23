package com.defo;

// Imports
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ClanTrackerConfig.GROUP)
public interface ClanTrackerConfig extends Config {
	// ============================================================
	// Constants
	// ============================================================

	String GROUP = "clantracker";

	// ============================================================
	// Local tracking toggles
	// ============================================================

	@ConfigItem(keyName = "trackXP", name = "Track XP Gains", description = "Track skill XP gains locally.")
	default boolean trackXP() {
		return true;
	}

	@ConfigItem(keyName = "trackBossKC", name = "Track Boss KC", description = "Track boss killcount updates from chat messages locally.")
	default boolean trackBossKC() {
		return true;
	}

	// ============================================================
	// Development / diagnostics
	// ============================================================

	@ConfigItem(keyName = "debug", name = "Debug Logging", description = "Enable extra debug logging.")
	default boolean debug() {
		return false;
	}

	// ============================================================
	// Uploads (heartbeat)
	// ============================================================

	@ConfigItem(keyName = "enableUploads", name = "Enable uploads", description = "Send unverified heartbeat updates (every 5 minutes) while logged in.")
	default boolean enableUploads() {
		return false;
	}

	@ConfigItem(keyName = "baseUrl", name = "Backend Base URL", description = "Example: https://yourdomain.com (no trailing slash).")
	default String baseUrl() {
		return "";
	}

	@ConfigItem(keyName = "apiKey", name = "API Key", description = "Stored locally. Used for authenticated uploads."
	// later we can mark this as a secret if you want
	)
	default String apiKey() {
		return "";
	}
}