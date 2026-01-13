package no.vestlandetmc.BanFromClaim.utils;

import no.vestlandetmc.BanFromClaim.BfcPlugin;
import no.vestlandetmc.BanFromClaim.config.ClaimData;
import no.vestlandetmc.BanFromClaim.config.Config;
import no.vestlandetmc.BanFromClaim.hooks.RegionHook;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public final class BanEnforcer {

    private BanEnforcer() {}

    public static void enforceAt(Player p, Location loc) {
        if (p == null || loc == null) return;

        RegionHook hook = BfcPlugin.getHookManager().getActiveRegionHook();
        if (hook == null) return;

        String regionId = hook.getRegionID(loc);
        if (regionId == null) return;

        if (canBypass(p)) return;
        if (hook.hasTrust(p, regionId)) return;

        ClaimData claimData = new ClaimData();
        boolean banned = claimData.isAllBanned(regionId) || isPlayerBanned(claimData, p.getUniqueId(), regionId);
        if (!banned) return;

        teleportToBanArea(p, hook, regionId);
    }

    private static boolean canBypass(Player p) {
        return p.hasPermission("bfc.bypass") || p.getGameMode() == GameMode.SPECTATOR;
    }

    private static boolean isPlayerBanned(ClaimData claimData, UUID uuid, String regionId) {
        List<String> list = claimData.bannedPlayers(regionId);
        return list != null && list.contains(uuid.toString());
    }

    /**
     * Teleports a banned player according to Config.TELEPORT_MODE.
     * SAFE_LOCATION -> Config.getBannedTeleportTarget()
     * NEAREST_SAFE / PUSH_BACK are handled in RegionListener (movement enforcement).
     * For "instant enforcement" (join/teleport/respawn), we always send to safe target.
     */
    public static void teleportToBanArea(Player p, RegionHook hook, String regionId) {
        if (p == null) return;

        final World world = p.getWorld();
        Location dest = Config.getBannedTeleportTarget(world);

        // Safety: if misconfigured and safespot is inside the same region, fallback to world spawn.
        try {
            String destRegion = hook.getRegionID(dest);
            if (destRegion != null && destRegion.equals(regionId)) {
                dest = world.getSpawnLocation();
            }
        } catch (Throwable ignored) {
            // If hook throws for any reason, just teleport to the target.
        }

        Location finalDest = dest.clone();
        Bukkit.getScheduler().runTask(BfcPlugin.getPlugin(), () -> p.teleport(finalDest));
    }
}