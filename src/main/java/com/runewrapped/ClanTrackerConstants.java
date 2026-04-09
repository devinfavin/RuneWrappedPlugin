package com.runewrapped;

/**
 * Shared constants for payload schema + plugin metadata.
 *
 * Keep SCHEMA_VERSION stable for backend contract.
 */
public final class ClanTrackerConstants
{
	private ClanTrackerConstants() {}

	/** Heartbeat/session payload schema version. */
	public static final int SCHEMA_VERSION = 2;

	/**
	 * Plugin version string included in uploads/exports.
	 * For now hard-coded; later we can wire to Gradle.
	 */
	public static final String PLUGIN_VERSION = "0.1.0";
}