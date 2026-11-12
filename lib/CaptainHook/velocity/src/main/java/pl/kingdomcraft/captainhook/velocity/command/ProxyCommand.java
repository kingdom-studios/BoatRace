package pl.kingdomcraft.captainhook.velocity.command;

import java.nio.file.Files;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.velocity.CaptainHookVelocity;
import pl.kingdomcraft.captainhook.velocity.config.ConfigException;
import pl.kingdomcraft.captainhook.velocity.database.Database;
import pl.kingdomcraft.captainhook.velocity.database.SqliteMerge;
import pl.kingdomcraft.captainhook.velocity.pass.SeasonPassSettings;

/**
 * /captainhookproxy status|reload|merge [confirm] - administration of the proxy side. The Paper servers keep
 * their own /captainhook; everything about the database lives here because the database does.
 */
public final class ProxyCommand implements SimpleCommand {

	public static final String LABEL = "captainhookproxy";
	public static final String ALIAS = "chproxy";

	private static final List<String> SUBCOMMANDS = List.of("status", "reload", "merge", "missions", "mission");

	private final CaptainHookVelocity plugin;
	private final AtomicBoolean merging = new AtomicBoolean();

	public ProxyCommand(final CaptainHookVelocity plugin) {
		this.plugin = plugin;
	}

	@Override
	public boolean hasPermission(final Invocation invocation) {
		return invocation.source().hasPermission(CaptainHookVelocity.ADMIN_PERMISSION);
	}

	@Override
	public List<String> suggest(final Invocation invocation) {
		final String[] args = invocation.arguments();

		if (args.length <= 1)
			return SUBCOMMANDS.stream().filter(sub -> args.length == 0 || sub.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();

		return args.length == 2 && args[0].equalsIgnoreCase("merge") ? Stream.of("confirm").filter(word -> word.startsWith(args[1].toLowerCase(Locale.ROOT))).toList() : List.of();
	}

	@Override
	public void execute(final Invocation invocation) {
		final CommandSource source = invocation.source();
		final String[] args = invocation.arguments();

		if (!this.plugin.isEnabled()) {
			tell(source, NamedTextColor.RED, "CaptainHook did not start, see the proxy log.");

			return;
		}

		switch (args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT)) {
			case "status" -> this.status(source);
			case "reload" -> this.reload(source);
			case "merge" -> this.merge(source, args.length > 1 && args[1].equalsIgnoreCase("confirm"));
			case "missions" -> this.missions(source);
			case "mission" -> this.mission(source, invocation.alias(), args);
			default -> tell(source, NamedTextColor.GRAY, "Usage: /" + invocation.alias() + " <status|reload|merge [confirm]|missions|mission ...>");
		}
	}

	private void status(final CommandSource source) {
		final Database database = this.plugin.getDatabase();
		final SeasonPassSettings season = this.plugin.getSeasonPass().getSettings();
		final Map<String, String> serverIds = this.plugin.getMessenger().getServerIds();
		final boolean unmerged = database.isMySql() && Files.exists(SqliteMerge.getFile(this.plugin.getDataDirectory()));

		tell(source, NamedTextColor.GOLD, "CaptainHook " + this.plugin.getVersion() + " (protocol " + Protocol.VERSION + ")");
		tell(source, NamedTextColor.GRAY, "Database: " + (database.isMySql() ? "MySQL" : "SQLite (the website cannot read it!)"));
		tell(source, NamedTextColor.GRAY, "Unmerged SQLite file: " + (unmerged ? "yes, use /" + LABEL + " merge" : "-"));
		tell(source, NamedTextColor.GRAY, "Players online: " + this.plugin.getMessenger().onlinePlayers().size());
		tell(source, NamedTextColor.GRAY, "Achievements: " + this.plugin.getAchievements().getDefinitions().size());
		tell(source, NamedTextColor.GRAY, "Rewards sent, waiting for confirmation: " + this.plugin.getRewardDispatcher().getInFlightCount());
		tell(source, NamedTextColor.GRAY, "Season pass: " + (!season.enabled() ? "disabled"
				: season.id() + (this.plugin.getSeasonPass().getActiveSeason() != null ? " (active)" : " (not active today)")));
		tell(source, NamedTextColor.GRAY, "Servers heard from: " + (serverIds.isEmpty() ? "none yet" : ""));

		// A server appears once it has sent its first message, which needs a player on it
		serverIds.forEach((velocityName, serverId) -> tell(source, NamedTextColor.GRAY, "  " + velocityName + (velocityName.equals(serverId) ? "" : " (stats as \"" + serverId + "\")")));
	}

	private void missions(final CommandSource source) {
		final var missions = this.plugin.getMissions();

		tell(source, NamedTextColor.GOLD, "Active missions " + missions.summary() + " (" + (missions.getSettings().enabled() ? "enabled" : "disabled") + ", " + missions.getSettings().zone() + ")");
		missions.getActive().forEach(assignment -> tell(source, NamedTextColor.GRAY, "  " + assignment.type().key() + " " + assignment.periodKey() + "  " + assignment.missionKey()
				+ "  " + assignment.objective() + " x" + assignment.target() + " = " + assignment.xp() + " XP"));
	}

	/**
	 * /captainhookproxy mission rotate <daily|weekly> | xp <add|set> <player> <amount> | complete <player> <key> | progress <player> <objective> <amount>
	 */
	private void mission(final CommandSource source, final String alias, final String[] args) {
		final String usage = "Usage: /" + alias + " mission rotate <daily|weekly> | xp <add|set> <player> <amount> | complete <player> <key> | progress <player> <objective> <amount>";
		final String action;
		final String name;
		final String arg;
		long amount = 0;

		try {
			switch (args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "") {
				case "rotate" -> { action = "rotate"; name = null; arg = args[2]; }
				case "xp" -> { action = "xp_" + args[2].toLowerCase(Locale.ROOT); name = args[3]; arg = args[4]; amount = 0; }
				case "complete" -> { action = "complete"; name = args[2]; arg = args[3]; }
				case "progress" -> { action = "progress"; name = args[2]; arg = args[3]; amount = Long.parseLong(args[4]); }
				default -> { tell(source, NamedTextColor.GRAY, usage); return; }
			}

		} catch (final ArrayIndexOutOfBoundsException | NumberFormatException ex) {
			tell(source, NamedTextColor.GRAY, usage);
			return;
		}

		try {
			this.plugin.getMissionAdmin().run(action, name, arg, amount).whenComplete((result, error) -> {
				if (error != null)
					tell(source, NamedTextColor.RED, rootMessage(error));
				else
					tell(source, NamedTextColor.GREEN, result.get("message").getAsString());
			});

		} catch (final RuntimeException ex) {
			tell(source, NamedTextColor.RED, ex.getMessage());
		}
	}

	private void reload(final CommandSource source) {
		try {
			tell(source, NamedTextColor.GREEN, "Reloaded: " + this.plugin.reload() + ". Database settings need a restart.");

		} catch (final ConfigException ex) {
			tell(source, NamedTextColor.RED, "Nothing was changed: " + ex.getMessage());
		}
	}

	private void merge(final CommandSource source, final boolean confirm) {
		final Database database = this.plugin.getDatabase();

		if (!database.isMySql()) {
			tell(source, NamedTextColor.RED, "The proxy runs on SQLite. Merging is possible once it is connected to MySQL again.");

			return;
		}

		if (!Files.exists(SqliteMerge.getFile(this.plugin.getDataDirectory()))) {
			tell(source, NamedTextColor.RED, "There is no " + Database.SQLITE_FILE + " in the plugin folder, nothing to merge.");

			return;
		}

		if (this.merging.get()) {
			tell(source, NamedTextColor.RED, "A merge is already running.");

			return;
		}

		if (!confirm) {
			SqliteMerge.preview(database, this.plugin.getDataDirectory()).whenComplete((summary, error) -> {
				if (error != null)
					tell(source, NamedTextColor.RED, "Could not read " + Database.SQLITE_FILE + ": " + rootMessage(error));
				else
					tell(source, NamedTextColor.GRAY, Database.SQLITE_FILE + " holds " + summary[0] + " account(s) and " + format(summary[1]) + " KC. Balances will be ADDED to "
							+ "those in MySQL and newer stats replace older ones. Run /" + LABEL + " merge confirm to continue.");
			});

			return;
		}

		if (!this.merging.compareAndSet(false, true))
			return;

		tell(source, NamedTextColor.GRAY, "Merging...");

		SqliteMerge.merge(database, this.plugin.getDataDirectory()).whenComplete((merged, error) -> {
			this.merging.set(false);

			if (error != null) {
				this.plugin.getLogger().error("Merging {} into MySQL failed, nothing was changed", Database.SQLITE_FILE, error);
				tell(source, NamedTextColor.RED, "The merge failed, nothing was changed: " + rootMessage(error));

				return;
			}

			final String summary = "Merged " + merged.accounts() + " account(s) with " + format(merged.coins()) + " KC, " + merged.statsRows() + " stats row(s), "
					+ merged.unlocks() + " achievement unlock(s) and the season pass of " + merged.passPlayers() + " player(s).";

			this.plugin.getLogger().info(summary);
			this.plugin.getKingdomCoins().refresh();

			tell(source, NamedTextColor.GREEN, summary + " The SQLite file was renamed and will not be merged again.");
		});
	}

	private static void tell(final CommandSource source, final NamedTextColor color, final String message) {
		source.sendMessage(Component.text(message, color));
	}

	private static String rootMessage(final Throwable error) {
		Throwable cause = error;

		while (cause.getCause() != null && cause != cause.getCause())
			cause = cause.getCause();

		return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
	}

	/**
	 * @return the amount with space-separated thousands, e.g. "1 250 000"
	 */
	private static String format(final long amount) {
		final DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);

		symbols.setGroupingSeparator(' ');
		return new DecimalFormat("#,##0", symbols).format(amount);
	}
}
