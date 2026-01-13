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
	public void execute(CommandSourceStack stack, String[] args) {
		final CommandSender sender = stack.getSender();

		// /bfc reload (works for console + players)
		if (args.length >= 1 && isReloadArg(args[0])) {
			handleReload(sender);
			return;
		}

		// Everything else requires a player (claim context)
		if (!(sender instanceof Player player)) {
			MessageHandler.sendConsole("&cThis command can only be used in-game (except: /bfc reload).");
			return;
		}

		final RegionHook region = BfcPlugin.getHookManager().getActiveRegionHook();
		if (region == null) {
			MessageHandler.sendMessage(player, "&cNo supported protection hook is active.");
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
		final boolean allowBan =
				player.hasPermission("bfc.admin")
						|| region.isOwner(player, regionID)
						|| region.isManager(player, regionID);

		if (bannedPlayer.getUniqueId().equals(player.getUniqueId())) {
			MessageHandler.sendMessage(player, Messages.BAN_SELF);
			return;
		} else if (!bannedPlayer.hasPlayedBefore()) {
			MessageHandler.sendMessage(player,
					Messages.placeholders(Messages.UNVALID_PLAYERNAME, args[0], player.getDisplayName(), null));
			return;
		} else if (region.isOwner(bannedPlayer, regionID)) {
			MessageHandler.sendMessage(player, Messages.BAN_OWNER);
			return;
		}

		if (bannedPlayer.isOnline() && bannedPlayer.getPlayer() != null && bannedPlayer.getPlayer().hasPermission("bfc.bypass")) {
			MessageHandler.sendMessage(player,
					Messages.placeholders(Messages.PROTECTED, bannedPlayer.getPlayer().getDisplayName(), null, null));
			return;
		}

		if (!allowBan) {
			MessageHandler.sendMessage(player, Messages.NO_ACCESS);
			return;
		}

		final String claimOwner = region.getClaimOwnerName(regionID);

		if (setClaimData(regionID, bannedPlayer.getUniqueId().toString(), true)) {

			// If target is online and currently inside the claim, immediately move them to safestop/spawn (SYNC)
			if (bannedPlayer.isOnline() && bannedPlayer.getPlayer() != null) {
				final Player target = bannedPlayer.getPlayer();

				if (region.isInsideRegion(target, regionID)) {
					final Location dest = (Config.SAFE_LOCATION != null)
							? Config.SAFE_LOCATION
							: target.getWorld().getSpawnLocation();

					Bukkit.getScheduler().runTask(BfcPlugin.getPlugin(), () -> {
						target.teleport(dest);
						MessageHandler.sendMessage(target,
								Messages.placeholders(Messages.BANNED_TARGET,
										bannedPlayer.getName(),
										player.getDisplayName(),
										claimOwner));
					});
				}
			}

			MessageHandler.sendMessage(player, Messages.placeholders(Messages.BANNED, bannedPlayer.getName(), null, null));
		} else {
			MessageHandler.sendMessage(player, Messages.ALREADY_BANNED);
		}
	}

	private static boolean isReloadArg(String arg) {
		return arg.equalsIgnoreCase("reload") || arg.equalsIgnoreCase("rl");
	}

	private static void handleReload(CommandSender sender) {
		if (!sender.hasPermission("bfc.admin") && !sender.hasPermission("bfc.reload")) {
			// If you have a message constant for this, swap it in
			if (sender instanceof Player p) {
				MessageHandler.sendMessage(p, "&cYou do not have permission to do that.");
			} else {
				MessageHandler.sendConsole("&cYou do not have permission to do that.");
			}
			return;
		}

		Bukkit.getScheduler().runTask(BfcPlugin.getPlugin(), () -> {
			// Reload plugin config.yml (Paper)
			BfcPlugin.getPlugin().reloadConfig();

			// Reload your static config + messages (these must exist)
			Config.initialize();
			Messages.reload();

			if (sender instanceof Player p) {
				MessageHandler.sendMessage(p, "&aBanFromClaim reloaded (config + messages).");
			} else {
				MessageHandler.sendConsole("&aBanFromClaim reloaded (config + messages).");
			}
		});
	}

	@Override
	public Collection<String> suggest(CommandSourceStack commandSourceStack, String[] args) {
		String input = args.length > 0 ? args[args.length - 1].toLowerCase() : "";

		// Suggest reload if they started typing it
		if (args.length == 1 && "reload".startsWith(input)) {
			return java.util.List.of("reload");
		}

		return Bukkit.getOnlinePlayers().stream()
				.map(Player::getName)
				.filter(name -> name.toLowerCase().startsWith(input))
				.sorted(String.CASE_INSENSITIVE_ORDER)
				.toList();
	}

	@Override
	public @Nullable String permission() {
		// Keep your existing base command perm. Reload is checked manually inside handleReload().
		return Permissions.BAN.getName();
	}

	private boolean setClaimData(String claimID, String bannedUUID, boolean add) {
		final ClaimData claimData = new ClaimData();
		return claimData.setClaimData(claimID, bannedUUID, add);
	}
}