package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.storage.RecordStorage;
import pl.kingdomcraft.boatrace.util.Msg;
import pl.kingdomcraft.boatrace.util.TimeFmt;

/**
 * /bre admin time add|remove <player> <amount> [arena]
 * /bre admin time reset <player> [arena]
 * /bre admin blacklist <player>      (toggle)
 *
 * Amount: "12.5" / "12.5s" = seconds, "1500ms" = milliseconds. Without [arena] all arenas are affected.
 */
final class AdminSub extends RaceSubCommand {

	AdminSub(final SimpleCommandGroup group) {
		super(group, "admin", "Manage player times and bans.", null, 1, true);
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] {
				"/{label} admin time add <player> <seconds|ms> [arena]",
				"/{label} admin time remove <player> <seconds|ms> [arena]",
				"/{label} admin time reset <player> [arena]",
				"/{label} admin blacklist <player>",
				"&7A number without a suffix = seconds, e.g. 12.5 or 1500ms."
		};
	}

	@Override
	protected void onCommand() {
		final RecordStorage storage = this.plugin().getStorage();
		final String group = this.args[0].toLowerCase();

		if ("blacklist".equals(group)) {
			this.checkArgs(2, Msg.text("admin.usage-blacklist"));

			final OfflinePlayer target = this.findTarget(this.args[1]);
			final boolean now = storage.toggleBlacklist(target.getUniqueId(), this.nameOf(target));

			this.info(now ? "admin.banned" : "admin.unbanned", "player", this.nameOf(target));

			if (now && target.getPlayer() != null && this.plugin().getRaces().isRacing(target.getPlayer()))
				this.plugin().getRaces().leave(target.getPlayer());

			return;
		}

		if (!"time".equals(group))
			this.returnInvalidArgs();

		this.checkArgs(3, Msg.text("admin.usage-time"));

		final String action = this.args[1].toLowerCase();
		final OfflinePlayer target = this.findTarget(this.args[2]);
		final String name = this.nameOf(target);

		switch (action) {
			case "add", "remove" -> {
				this.checkArgs(4, Msg.text("admin.usage-time-amount", "action", action));

				final long amount;

				try {
					amount = TimeFmt.parseMillis(this.args[3]);

				} catch (final NumberFormatException ex) {
					this.fail("admin.invalid-time", "value", this.args[3]);
					return;
				}

				final String arena = this.optionalArena(4);
				final long delta = "add".equals(action) ? amount : -amount;
				final int changed = storage.adjustTime(target.getUniqueId(), arena, delta);

				if (changed == 0)
					this.fail(arena == null ? "admin.no-times" : "admin.no-times-arena", "player", name, "arena", String.valueOf(arena));

				this.info("admin.time-changed", "player", name, "delta", TimeFmt.signedDelta(delta), "count", changed);
			}
			case "reset" -> {
				final String arena = this.optionalArena(3);
				final int removed = storage.resetTimes(target.getUniqueId(), arena);

				if (removed == 0)
					this.fail(arena == null ? "admin.no-times" : "admin.no-times-arena", "player", name, "arena", String.valueOf(arena));

				this.info("admin.time-reset", "player", name, "count", removed);
			}
			default -> this.returnInvalidArgs();
		}
	}

	/** Optional arena name at {@code index}; stops the command when it is given but does not exist. */
	private String optionalArena(final int index) {
		if (this.args.length <= index)
			return null;

		if (!this.plugin().getArenas().exists(this.args[index]))
			this.fail("command.arena-not-found", "arena", this.args[index]);

		return this.plugin().getArenas().get(this.args[index]).getName();
	}

	private OfflinePlayer findTarget(final String name) {
		OfflinePlayer target = Bukkit.getPlayerExact(name);

		if (target == null)
			target = Bukkit.getOfflinePlayerIfCached(name);

		if (target == null)
			this.fail("command.player-not-found-offline", "player", name);

		return target;
	}

	private String nameOf(final OfflinePlayer player) {
		return player.getName() != null ? player.getName() : player.getUniqueId().toString();
	}

	@Override
	protected List<String> tabComplete() {
		switch (this.args.length) {
			case 1:
				return this.completeLastWord("time", "blacklist");
			case 2:
				return "time".equalsIgnoreCase(this.args[0]) ? this.completeLastWord("add", "remove", "reset") : this.completeLastWordPlayerNames();
			case 3:
				return "time".equalsIgnoreCase(this.args[0]) ? this.completeLastWordPlayerNames() : new ArrayList<>();
			case 4:
				return "time".equalsIgnoreCase(this.args[0]) && "reset".equalsIgnoreCase(this.args[1]) ? this.completeLastWord(this.arenaNames()) : new ArrayList<>();
			case 5:
				return this.completeLastWord(this.arenaNames());
			default:
				return new ArrayList<>();
		}
	}
}
