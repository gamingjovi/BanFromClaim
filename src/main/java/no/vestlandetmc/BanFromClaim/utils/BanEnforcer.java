package no.vestlandetmc.BanFromClaim.utils;

import no.vestlandetmc.BanFromClaim.BfcPlugin;
import no.vestlandetmc.BanFromClaim.config.ClaimData;
import no.vestlandetmc.BanFromClaim.config.Config;
import no.vestlandetmc.BanFromClaim.hooks.RegionHook;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

public final class BanEnforcer {

    private BanEnforcer() {}

    public static void enforceAt(Player p, Location loc) {
        RegionHook hook = BfcPlugin.getHookManager().getActiveRegionHook();
        if (hook == null || loc == null) return;

        String regionId = hook.getRegionID(loc);
        if (regionId == null) return;

        if (canBypass(p)) return;
        if (hook.hasTrust(p, regionId)) return;

        ClaimData claimData = new ClaimData();
        boolean banned = claimData.isAllBanned(regionId) || isPlayerBanned(claimData, p.getUniqueId(), regionId);
        if (!banned) return;

        // Send them to your configured area
        teleportToBanArea(p);
    }

    private static boolean canBypass(Player p) {
        return p.hasPermission("bfc.bypass") || p.getGameMode() == GameMode.SPECTATOR;
    }

    private static boolean isPlayerBanned(ClaimData claimData, UUID uuid, String regionId) {
        if (!claimData.checkClaim(regionId)) return false;
        List<String> list = claimData.bannedPlayers(regionId);
        if (list == null) return false;
        String u = uuid.toString();
        for (String s : list) {
            if (u.equals(s)) return true;
        }
        return false;
    }

    public static void teleportToBanArea(Player p) {
        Location dest;

        if (Config.SEND_BANNED_TO_SAFESPOT && Config.SAFE_LOCATION != null) {
            dest = Config.SAFE_LOCATION;
        } else {
            dest = p.getWorld().getSpawnLocation();
        }

        // Run on main thread (safe)
        Bukkit.getScheduler().runTask(BfcPlugin.getPlugin(), () -> p.teleport(dest));
    }
}