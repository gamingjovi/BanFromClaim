package no.vestlandetmc.BanFromClaim.config;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Central config values (loaded from config.yml).
 *
 * BanFromClaim's default config.yml is tiny (combat + kickmode), so teleport settings are read with
 * safe defaults to avoid breaking existing installs.
 */
public class Config extends ConfigHandler {

	private static final String CONFIG_FILE = "config.yml";

	private Config(String fileName) {
		super(fileName);
	}

	public enum TeleportMode {
		/**
		 * Always send banned players to teleport.safelocation.* (or world spawn if not set).
		 */
		SAFE_LOCATION,

		/**
		 * Find a safe spot outside the claim (legacy behaviour) and fall back to safelocation/spawn.
		 */
		NEAREST_SAFE,

		/**
		 * Push the player back a few blocks (legacy behaviour). Falls back to safelocation/spawn if needed.
		 */
		PUSH_BACK;

		public static TeleportMode fromString(String raw) {
			if (raw == null) return SAFE_LOCATION;
			try {
				return TeleportMode.valueOf(raw.trim().toUpperCase());
			} catch (IllegalArgumentException ex) {
				return SAFE_LOCATION;
			}
		}
	}

	// Keep original field names (other classes depend on these)
	public static long COMBAT_TIME;

	public static boolean
			COMBAT_ENABLED,
			TIMER_ENABLED,
			KICKMODE;

	/** How we teleport banned players. Default = SAFE_LOCATION (matches your request). */
	public static TeleportMode TELEPORT_MODE;

	/**
	 * How often we re-check a player who hasn't moved blocks (prevents "standing still inside claim").
	 * Default: 20 ticks = 1 second.
	 */
	public static int ENFORCE_INTERVAL_TICKS;

	/** Set via /bfcsafespot (or by editing config.yml). */
	public static Location SAFE_LOCATION;

	private void onLoad() {

		// Existing settings
		COMBAT_TIME = Math.max(1L, getLong("combatmode.time", 15L));
		COMBAT_ENABLED = getBoolean("combatmode.enabled", false);
		TIMER_ENABLED = getBoolean("combatmode.timer-enabled", true);
		KICKMODE = getBoolean("kickmode", true);

		// New settings (safe defaults if not present in config.yml)
		TELEPORT_MODE = TeleportMode.fromString(getString("teleport.mode", TeleportMode.SAFE_LOCATION.name()));
		ENFORCE_INTERVAL_TICKS = Math.max(1, getInt("teleport.enforce-interval-ticks", 20));

		loadSafeLocation();
	}

	private void loadSafeLocation() {
		if (!contains("teleport.safelocation")) {
			SAFE_LOCATION = null;
			return;
		}

		final String worldName = getString("teleport.safelocation.world");
		final World world = (worldName == null) ? null : Bukkit.getWorld(worldName);

		if (world == null) {
			Bukkit.getLogger().warning("[BanFromClaim] teleport.safelocation.world is not loaded/found: " + worldName);
			SAFE_LOCATION = null;
			return;
		}

		final double x = getDouble("teleport.safelocation.x");
		final double y = getDouble("teleport.safelocation.y");
		final double z = getDouble("teleport.safelocation.z");

		final float yaw = (float) getDouble("teleport.safelocation.yaw", 0D);
		final float pitch = (float) getDouble("teleport.safelocation.pitch", 0D);

		SAFE_LOCATION = new Location(world, x, y, z, yaw, pitch);
	}

	public static void initialize() {
		new Config(CONFIG_FILE).onLoad();
	}

	/**
	 * Returns the configured safelocation if set, otherwise world spawn.
	 * Always returns a clone to avoid accidental mutation.
	 */
	public static Location getBannedTeleportTarget(World fallbackWorld) {
		final World world = (fallbackWorld != null) ? fallbackWorld : Bukkit.getWorlds().get(0);

		if (SAFE_LOCATION == null) {
			return world.getSpawnLocation().clone();
		}

		return SAFE_LOCATION.clone();
	}

	public static void setSafespot(Location loc) {
		if (loc == null || loc.getWorld() == null) return;

		final ConfigHandler cfg = new ConfigHandler(CONFIG_FILE);

		cfg.set("teleport.safelocation.x", loc.getX());
		cfg.set("teleport.safelocation.y", loc.getY());
		cfg.set("teleport.safelocation.z", loc.getZ());
		cfg.set("teleport.safelocation.yaw", loc.getYaw());
		cfg.set("teleport.safelocation.pitch", loc.getPitch());
		cfg.set("teleport.safelocation.world", loc.getWorld().getName());
		cfg.saveConfig();

		SAFE_LOCATION = loc.clone();
	}
}