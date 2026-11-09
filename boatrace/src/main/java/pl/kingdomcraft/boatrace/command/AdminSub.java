package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.storage.RecordStorage;
import pl.kingdomcraft.boatrace.util.TimeFmt;

/**
 * /br admin time add|remove <player> <amount> [arena]
 * /br admin time reset <player> [arena]
 * /br admin blacklist <player>      (toggle)
 *
 * Amount: "12.5" / "12.5s" = seconds, "1500ms" = milliseconds. Without [arena] all arenas are affected.
 */
final class AdminSub extends RaceSubCommand {

	AdminSub(final SimpleCommandGroup group) {
		super(group, "admin", "Administracja czasami i wykluczeniami graczy.", null, 1, true);
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] {
				"/{label} admin time add <gracz> <sekundy|ms> [arena]",
				"/{label} admin time remove <gracz> <sekundy|ms> [arena]",
				"/{label} admin time reset <gracz> [arena]",
				"/{label} admin blacklist <gracz>",
				"&7Liczba bez przyrostka = sekundy, np. 12.5 lub 1500ms."
		};
	}

	@Override
	protected void onCommand() {
		final RecordStorage storage = this.plugin().getStorage();
		final String group = this.args[0].toLowerCase();

		if ("blacklist".equals(group)) {
			this.checkArgs(2, "&cUżycie: /br admin blacklist <gracz>");

			final OfflinePlayer target = this.findTarget(this.args[1]);
			final boolean now = storage.toggleBlacklist(target.getUniqueId(), this.nameOf(target));

			this.info(now
					? "&aGracz &f" + this.nameOf(target) + " &azostał wykluczony z wyścigów i rankingów."
					: "&aGracz &f" + this.nameOf(target) + " &anie jest już wykluczony.");

			if (now && target.getPlayer() != null && this.plugin().getRaces().isRacing(target.getPlayer()))
				this.plugin().getRaces().leave(target.getPlayer());

			return;
		}

		if (!"time".equals(group))
			this.returnInvalidArgs();

		this.checkArgs(3, "&cUżycie: /br admin time <add|remove|reset> <gracz> ...");

		final String action = this.args[1].toLowerCase();
		final OfflinePlayer target = this.findTarget(this.args[2]);
		final String name = this.nameOf(target);

		switch (action) {
			case "add", "remove" -> {
				this.checkArgs(4, "&cUżycie: /br admin time " + action + " <gracz> <sekundy|ms> [arena]");

				final long amount;

				try {
					amount = TimeFmt.parseMillis(this.args[3]);

				} catch (final NumberFormatException ex) {
					this.returnTell("&cNieprawidłowy czas: &f" + this.args[3] + " &7(przykłady: 5, 12.5s, 1500ms)");
					return;
				}

				final String arena = this.optionalArena(4);
				final long delta = "add".equals(action) ? amount : -amount;
				final int changed = storage.adjustTime(target.getUniqueId(), arena, delta);

				if (changed == 0)
					this.returnTell("&c" + name + " nie ma zapisanych czasów" + (arena == null ? "." : " na arenie " + arena + "."));

				this.info("&aZmieniono czas gracza &f" + name + " &ao &f" + TimeFmt.signedDelta(delta) + " &a(rekordów: " + changed + ").");
			}
			case "reset" -> {
				final String arena = this.optionalArena(3);
				final int removed = storage.resetTimes(target.getUniqueId(), arena);

				if (removed == 0)
					this.returnTell("&c" + name + " nie ma zapisanych czasów" + (arena == null ? "." : " na arenie " + arena + "."));

				this.info("&aZresetowano czasy gracza &f" + name + " &a(rekordów: " + removed + ").");
			}
			default -> this.returnInvalidArgs();
		}
	}

	/** Optional arena name at {@code index}; stops the command when it is given but does not exist. */
	private String optionalArena(final int index) {
		if (this.args.length <= index)
			return null;

		if (!this.plugin().getArenas().exists(this.args[index]))
			this.returnTell("&cNie ma areny o nazwie &f" + this.args[index] + "&c.");

		return this.plugin().getArenas().get(this.args[index]).getName();
	}

	private OfflinePlayer findTarget(final String name) {
		OfflinePlayer target = Bukkit.getPlayerExact(name);

		if (target == null)
			target = Bukkit.getOfflinePlayerIfCached(name);

		if (target == null)
			this.returnTell("&cNie znaleziono gracza &f" + name + "&c (musi kiedyś wejść na serwer).");

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
