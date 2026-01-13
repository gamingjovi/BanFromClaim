package no.vestlandetmc.BanFromClaim.commands;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import no.vestlandetmc.BanFromClaim.BfcPlugin;
import no.vestlandetmc.BanFromClaim.config.ClaimData;
import no.vestlandetmc.BanFromClaim.config.Config;
import no.vestlandetmc.BanFromClaim.config.Messages;
import no.vestlandetmc.BanFromClaim.handler.MessageHandler;
import no.vestlandetmc.BanFromClaim.handler.Permissions;
import no.vestlandetmc.BanFromClaim.hooks.RegionHook;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.util.Collection;

@NullMarked
@SuppressWarnings({"deprecation", "UnstableApiUsage"})
public class BfcCommand implements BasicCommand {

	@Override
	public void execute(CommandSourceStack commandSourceStack, String[] args) {
		if (!(commandSourceStack.getSender() instanceof Player player)) {
			MessageHandler.sendConsole("&cThis command can only be used in-game.");
			return;
		}

		final RegionHook region = BfcPlugin.getHookManager().getActiveRegionHook();
		if (region == null) {
			MessageHandler.sendMessage(player, "&cNo supported protection plugin is hooked.");
			return;
		}

		if (args.length == 0) {
			MessageHandler.sendMessage(player, Messages.NO_ARGUMENTS);
			return;
		}

		final String regionID = region.getRegionID(player);
		if (regionID == null) {
			MessageHandler.sendMessage(player, Messages.OUTSIDE_CLAIM);
			return;
		}

		final OfflinePlayer bannedPlayer = Bukkit.getOfflinePlayer(args[0]);

		if (bannedPlayer.getUniqueId().equals(player.getUniqueId())) {
			MessageHandler.sendMessage(player, Messages.BAN_SELF);
			return;
		}

		if (!bannedPlayer.hasPlayedBefore()) {
			MessageHandler.sendMessage(player, Messages.placeholders(
					Messages.UNVALID_PLAYERNAME, args[0], player.getDisplayName(), null
			));
			return;
		}

		if (region.isOwner(bannedPlayer, regionID)) {
			MessageHandler.sendMessage(player, Messages.BAN_OWNER);
			return;
		}

		// If target is online and has bypass, block ban
		if (bannedPlayer.isOnline()) {
			final Player target = bannedPlayer.getPlayer();
			if (target != null && target.hasPermission("bfc.bypass")) {
				MessageHandler.sendMessage(player, Messages.placeholders(
						Messages.PROTECTED, target.getDisplayName(), null, null
				));
				return;
			}
		}

		final boolean allowBan = player.hasPermission("bfc.admin")
				|| region.isOwner(player, regionID)
				|| region.isManager(player, regionID);

		if (!allowBan) {
			MessageHandler.sendMessage(player, Messages.NO_ACCESS);
			return;
		}

		final String claimOwner = region.getClaimOwnerName(regionID);

		// Save ban
		if (!setClaimData(regionID, bannedPlayer.getUniqueId().toString(), true)) {
			MessageHandler.sendMessage(player, Messages.ALREADY_BANNED);
			return;
		}

		// Notify banner
		MessageHandler.sendMessage(player, Messages.placeholders(Messages.BANNED, bannedPlayer.getName(), null, null));

		// If target is online and currently inside this region -> immediately send to safespot/spawn
		if (bannedPlayer.isOnline()) {
			final Player target = bannedPlayer.getPlayer();
			if (target != null && region.isInsideRegion(target, regionID)) {

				final Location dest = Config.getBannedTeleportTarget(target.getWorld());

				// Teleport MUST be on main thread
				Bukkit.getScheduler().runTask(BfcPlugin.getPlugin(), () -> {
					target.teleport(dest);
					MessageHandler.sendMessage(target, Messages.placeholders(
							Messages.BANNED_TARGET, bannedPlayer.getName(), player.getDisplayName(), claimOwner
					));
				});
			}
		}
	}

	@Override
	public Collection<String> suggest(CommandSourceStack commandSourceStack, String[] args) {
		String input = args.length > 0 ? args[args.length - 1].toLowerCase() : "";

		return Bukkit.getOnlinePlayers().stream()
				.map(Player::getName)
				.filter(name -> name.toLowerCase().startsWith(input))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.toList();
	}

	@Override
	public boolean canUse(CommandSender sender) {
		return BasicCommand.super.canUse(sender);
	}

	@Override
	public @Nullable String permission() {
		return Permissions.BAN.getName();
	}

	private boolean setClaimData(String claimID, String bannedUUID, boolean add) {
		final ClaimData claimData = new ClaimData();
		return claimData.setClaimData(claimID, bannedUUID, add);
	}
}