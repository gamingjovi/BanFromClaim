package no.vestlandetmc.BanFromClaim.listener;

import no.vestlandetmc.BanFromClaim.BfcPlugin;
import no.vestlandetmc.BanFromClaim.config.ClaimData;
import no.vestlandetmc.BanFromClaim.config.Config;
import no.vestlandetmc.BanFromClaim.config.Messages;
import no.vestlandetmc.BanFromClaim.handler.MessageHandler;
import no.vestlandetmc.BanFromClaim.handler.ParticleHandler;
import no.vestlandetmc.BanFromClaim.hooks.RegionHook;
import no.vestlandetmc.BanFromClaim.utils.LocationFinder;
import no.vestlandetmc.BanFromClaim.utils.PlayerRidePlayer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces bans when players attempt to enter OR remain inside claims.
 *
 * - SAFE_LOCATION mode: always teleport to a configured safelocation (or spawn).
 * - Still-check: banned players can't "stand still on one block" to avoid enforcement.
 * - Throttle + cleanup: optimized to avoid loops and memory growth.
 */
public class RegionListener implements Listener {

	private static final long TELEPORT_THROTTLE_MS = 750L;

	// Static so we can clean them on quit (avoids memory growth from "every player ever to join").
	private static final Map<UUID, Long> LAST_TELEPORT_MS = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_STILL_CHECK_MS = new ConcurrentHashMap<>();

	private final ClaimData claimData = new ClaimData();

	public static void cleanup(UUID playerId) {
		if (playerId == null) return;
		LAST_TELEPORT_MS.remove(playerId);
		LAST_STILL_CHECK_MS.remove(playerId);
	}

	@EventHandler(ignoreCancelled = true)
	public void onPlayerEnterClaim(PlayerMoveEvent event) {
		final Player player = event.getPlayer();
		final Location from = event.getFrom();
		final Location to = event.getTo();
		if (to == null) return;

		final UUID playerId = player.getUniqueId();

		final boolean movedBlock = movedToNewBlock(from, to);
		if (!movedBlock && !shouldRunStillCheck(playerId)) {
			return;
		}

		final RegionHook regionHook = BfcPlugin.getHookManager().getActiveRegionHook();
		if (regionHook == null) return;

		final String regionId = regionHook.getRegionID(to);
		if (regionId == null) return;

		final UUID ownerId = regionHook.getOwnerID(regionId);
		if (ownerId == null) return;

		// If the player is carrying someone, and either is banned: prevent carry-ins.
		final Player passenger = PlayerRidePlayer.getPassenger(player);
		if (passenger != null
				&& !regionHook.hasTrust(passenger, regionId)
				&& !canBypass(passenger)
				&& (claimData.isAllBanned(regionId) || isBanned(passenger.getUniqueId(), regionId) || isBanned(playerId, regionId))) {
			passenger.teleport(player.getLocation().add(0D, 4D, 0D));
		}

		if (canBypass(player)) return;

		// Combat exception: if attacker == claim owner, allow entry (legacy behaviour).
		final boolean hasAttacked =
				CombatMode.attackerContains(playerId) && ownerId.equals(CombatMode.getAttacker(playerId));
		if (hasAttacked) return;

		if (!shouldDeny(player, regionHook, regionId)) return;

		if (isTeleportThrottled(playerId)) return;

		teleportBannedPlayer(player, regionHook, regionId, from, to);

		sendDeniedFeedback(player, to, !movedBlock);
	}

	private boolean movedToNewBlock(Location from, Location to) {
		return from.getBlockX() != to.getBlockX()
				|| from.getBlockY() != to.getBlockY()
				|| from.getBlockZ() != to.getBlockZ();
	}

	private boolean shouldRunStillCheck(UUID playerId) {
		final long now = System.currentTimeMillis();
		final long intervalMs = Math.max(1, Config.ENFORCE_INTERVAL_TICKS) * 50L;

		final Long last = LAST_STILL_CHECK_MS.put(playerId, now);
		return last == null || (now - last) >= intervalMs;
	}

	private boolean isTeleportThrottled(UUID playerId) {
		final long now = System.currentTimeMillis();
		final Long last = LAST_TELEPORT_MS.get(playerId);
		if (last != null && (now - last) < TELEPORT_THROTTLE_MS) return true;

		LAST_TELEPORT_MS.put(playerId, now);
		return false;
	}

	private void teleportBannedPlayer(Player player, RegionHook hook, String regionId, Location from, Location to) {
		switch (Config.TELEPORT_MODE) {
			case NEAREST_SAFE -> teleportNearestSafe(player, hook, regionId);
			case PUSH_BACK -> teleportPushBack(player, hook, regionId, from, to);
			case SAFE_LOCATION -> teleportToSafeLocation(player, hook, regionId);
		}
	}

	private void teleportToSafeLocation(Player player, RegionHook hook, String regionId) {
		Location dest = Config.getBannedTeleportTarget(player.getWorld());

		// Safety: don't teleport to a safespot that is inside the same region (misconfig).
		final String destRegion = hook.getRegionID(dest);
		if (destRegion != null && destRegion.equals(regionId)) {
			dest = player.getWorld().getSpawnLocation();
		}

		player.teleport(dest);
	}

	private void teleportNearestSafe(Player player, RegionHook hook, String regionId) {
		final int sizeRadius = hook.sizeRadius(regionId);
		final Location greaterCorner = hook.getGreaterBoundaryCorner(regionId);
		final Location lesserCorner = hook.getLesserBoundaryCorner(regionId);

		final LocationFinder finder = new LocationFinder(greaterCorner, lesserCorner, player.getWorld().getUID(), sizeRadius);

		Bukkit.getScheduler().runTaskAsynchronously(BfcPlugin.getPlugin(), () ->
				finder.IterateCircumferences(randomLoc -> {
					if (!player.isOnline()) return;

					if (randomLoc == null) {
						player.teleport(Config.getBannedTeleportTarget(player.getWorld()));
					} else {
						player.teleport(randomLoc);
					}
				})
		);
	}

	private void teleportPushBack(Player player, RegionHook hook, String regionId, Location from, Location to) {
		final Vector delta = from.toVector().subtract(to.toVector());

		// If there's no actual movement vector (e.g., only head rotation), fall back safely.
		if (delta.lengthSquared() < 1.0E-6) {
			teleportToSafeLocation(player, hook, regionId);
			return;
		}

		Location dest = player.getLocation().add(delta.normalize().multiply(3));

		// If we pushed them into a block, place them on the surface.
		if (!dest.getBlock().getType().equals(Material.AIR)) {
			dest = dest.getWorld().getHighestBlockAt(dest).getLocation().add(0D, 1D, 0D);
		}

		player.teleport(dest);
	}

	private void sendDeniedFeedback(Player player, Location at, boolean sameBlock) {
		if (!MessageHandler.spamMessageClaim.contains(player.getUniqueId().toString())) {
			MessageHandler.sendTitle(player, Messages.TITLE_MESSAGE, Messages.SUBTITLE_MESSAGE);
			MessageHandler.spamMessageClaim.add(player.getUniqueId().toString());

			Bukkit.getScheduler().runTaskLater(
					BfcPlugin.getPlugin(),
					() -> MessageHandler.spamMessageClaim.remove(player.getUniqueId().toString()),
					5L * 20L
			);
		}

		// Particle feedback at the attempted location.
		try {
			new ParticleHandler(at).drawCircle(1, sameBlock);
		} catch (Throwable ignored) {
			// Best-effort only.
		}
	}

	private boolean canBypass(Player player) {
		return player.hasPermission("bfc.bypass") || player.getGameMode() == GameMode.SPECTATOR;
	}

	private boolean shouldDeny(Player player, RegionHook hook, String claimId) {
		if (player == null) return false;
		if (hook.hasTrust(player, claimId)) return false;

		if (claimData.isAllBanned(claimId)) return true;

		return isBanned(player.getUniqueId(), claimId);
	}

	private boolean isBanned(UUID uuid, String claimId) {
		final List<String> banned = claimData.bannedPlayers(claimId);
		return banned != null && banned.contains(uuid.toString());
	}
}