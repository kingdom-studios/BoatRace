package pl.kingdomcraft.captainhook.command;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.command.SimpleCommand;
import org.mineacademy.fo.model.Replacer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.Permissions;
import pl.kingdomcraft.captainhook.mission.MissionNotifications;
import pl.kingdomcraft.captainhook.pass.PassProgress;
import pl.kingdomcraft.captainhook.pass.SeasonPass;
import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.proxy.ProxyException;
import pl.kingdomcraft.captainhook.proxy.ProxyLink;
import pl.kingdomcraft.captainhook.settings.Localization.Mission;
import pl.kingdomcraft.captainhook.settings.Settings;

/**
 * /karnet (aliases /pass, /missions)  - the player's pass level and the missions of today, this week and the season
 * /karnet admin ...                   - XP and mission tools, permission captainhook.seasonpass.admin
 *
 * Everything lives on the proxy: this command asks it and prints the answer.
 */
@AutoRegister
public final class PassCommand extends SimpleCommand {

	public PassCommand() {
		super("karnet|pass|missions");

		setUsage("[admin ...]");
		setDescription(Mission.DESCRIPTION);
		setPermission(null);
		setCooldown(2, TimeUnit.SECONDS);
	}

	@Override
	protected void onCommand() {
		if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
			checkPerm(Permissions.SEASON_PASS_ADMIN);
			this.admin(this.sender, getLabel());

			return;
		}

		checkConsole();
		this.status(getPlayer());
	}

	/* ------------------------------------------------------------------------------- */
	/* Player */
	/* ------------------------------------------------------------------------------- */

	private void status(final Player player) {
		final JsonObject request = new JsonObject();

		request.addProperty("uuid", player.getUniqueId().toString());

		// Both answer on the main thread; the pass may be missing (no season) while the command still works
		SeasonPass.get(player.getUniqueId()).whenComplete((pass, passError) -> ProxyLink.request(Protocol.MISSION_LIST, request, player.getUniqueId(), false).whenComplete((missions, error) -> {
			if (!player.isOnline())
				return;

			if (passError != null && isCode(passError, Protocol.ERROR_NO_SEASON)) {
				Common.tell(player, Mission.NO_SEASON);

				return;
			}

			if (passError != null || error != null) {
				Common.tell(player, Mission.UNAVAILABLE);

				return;
			}

			this.print(player, pass, missions);
		}));
	}

	private void print(final Player player, final PassProgress pass, final JsonObject missions) {
		if (pass.getLevel() >= pass.getMaxLevel())
			Common.tellNoPrefix(player, Replacer.replaceArray(Mission.STATUS_MAX, "season", pass.getSeasonName(), "xp", pass.getXp()));
		else
			Common.tellNoPrefix(player, Replacer.replaceArray(Mission.STATUS, "season", pass.getSeasonName(), "level", pass.getLevel(), "max", pass.getMaxLevel(), "xp", pass.getXp(),
					"next", pass.getLevel() + 1, "togo", pass.getXpForNext() - pass.getXpIntoLevel()));

		this.group(player, missions, "daily", Mission.HEADER_DAILY);
		this.group(player, missions, "weekly", Mission.HEADER_WEEKLY);
		this.group(player, missions, "season", Mission.HEADER_SEASON);
		Common.tellNoPrefix(player, Replacer.replaceArray(Mission.CLAIM_HINT, "url", Settings.Missions.CLAIM_URL));
	}

	private void group(final Player player, final JsonObject missions, final String key, final String header) {
		final JsonObject group = Json.getObject(missions, key);
		final long resetsAt = Json.getLong(group, "resetsAt", 0);

		Common.tellNoPrefix(player, Replacer.replaceArray(header, "reset", resetsAt > 0 ? duration(resetsAt - System.currentTimeMillis() / 1000) : ""));

		if (Json.getArray(group, "items").size() == 0)
			Common.tellNoPrefix(player, Mission.EMPTY);

		for (final JsonElement element : Json.getArray(group, "items")) {
			final JsonObject item = element.getAsJsonObject();
			final String unit = MissionNotifications.title(player, item, "unitEn", "unitPl");

			Common.tellNoPrefix(player, Replacer.replaceArray(Json.getBoolean(item, "completed", false) ? Mission.LINE_DONE : Mission.LINE,
					"title", MissionNotifications.title(player, item, "titleEn", "titlePl"), "progress", Json.getLong(item, "progress", 0), "target", Json.getLong(item, "target", 1),
					"unit", unit.isEmpty() ? "" : " " + unit, "xp", Json.getLong(item, "xp", 0)));
		}
	}

	/**
	 * @return "2d 4h", "3h 20m" or "12m"
	 */
	private static String duration(final long seconds) {
		final long s = Math.max(0, seconds);
		final long days = s / 86_400, hours = s % 86_400 / 3600, minutes = s % 3600 / 60;

		return days > 0 ? days + "d " + hours + "h" : hours > 0 ? hours + "h " + minutes + "m" : Math.max(1, minutes) + "m";
	}

	private static boolean isCode(final Throwable error, final String code) {
		final Throwable cause = error.getCause() != null ? error.getCause() : error;

		return cause instanceof ProxyException && code.equals(((ProxyException) cause).getCode());
	}

	/* ------------------------------------------------------------------------------- */
	/* Admin */
	/* ------------------------------------------------------------------------------- */

	/**
	 * /karnet admin xp <add|set> <player> <amount> | mission complete <player> <id> | progress <player> <objective> <amount> | rotate <daily|weekly> | reload
	 */
	private void admin(final CommandSender sender, final String label) {
		final String[] a = args;
		final JsonObject data = new JsonObject();
		String action = null;

		try {
			if (a.length >= 5 && a[1].equalsIgnoreCase("xp") && (a[2].equalsIgnoreCase("add") || a[2].equalsIgnoreCase("set"))) {
				action = "xp_" + a[2].toLowerCase();
				data.addProperty("name", a[3]);
				data.addProperty("arg", String.valueOf(Long.parseLong(a[4])));

			} else if (a.length >= 5 && a[1].equalsIgnoreCase("mission") && a[2].equalsIgnoreCase("complete")) {
				action = "complete";
				data.addProperty("name", a[3]);
				data.addProperty("arg", a[4]);

			} else if (a.length >= 5 && a[1].equalsIgnoreCase("progress")) {
				action = "progress";
				data.addProperty("name", a[2]);
				data.addProperty("arg", a[3]);
				data.addProperty("amount", Long.parseLong(a[4]));

			} else if (a.length >= 3 && a[1].equalsIgnoreCase("rotate")) {
				action = "rotate";
				data.addProperty("arg", a[2]);

			} else if (a.length >= 2 && a[1].equalsIgnoreCase("reload"))
				action = "reload";

		} catch (final NumberFormatException ex) {
			action = null;
		}

		if (action == null) {
			Common.tell(sender, Replacer.replaceArray(Mission.ADMIN_USAGE, "label", label));

			return;
		}

		data.addProperty("action", action);

		// The command instance is reused by the next caller, so the callback only uses the captured sender
		ProxyLink.request(Protocol.MISSION_ADMIN, data).whenComplete((reply, error) -> {
			if (error == null) {
				Common.tell(sender, Replacer.replaceArray(Mission.ADMIN_DONE, "message", Json.getString(reply, "message", "OK")));

				return;
			}

			final Throwable cause = error.getCause() != null ? error.getCause() : error;

			Common.tell(sender, Replacer.replaceArray(Mission.ADMIN_FAILED, "error", String.valueOf(cause.getMessage())));
		});
	}

	@Override
	protected List<String> tabComplete() {
		if (!hasPerm(Permissions.SEASON_PASS_ADMIN))
			return NO_COMPLETE;

		if (args.length == 1)
			return completeLastWord("admin");

		if (!args[0].equalsIgnoreCase("admin"))
			return NO_COMPLETE;

		if (args.length == 2)
			return completeLastWord("xp", "mission", "progress", "rotate", "reload");

		if (args.length == 3)
			switch (args[1].toLowerCase()) {
				case "xp":
					return completeLastWord("add", "set");
				case "mission":
					return completeLastWord("complete");
				case "progress":
					return completeLastWordPlayerNames();
				case "rotate":
					return completeLastWord("daily", "weekly");
				default:
					return NO_COMPLETE;
			}

		if (args.length == 4 && (args[1].equalsIgnoreCase("xp") || args[1].equalsIgnoreCase("mission")))
			return completeLastWordPlayerNames();

		if (args.length == 4 && args[1].equalsIgnoreCase("progress"))
			return completeLastWord(Arrays.asList("PLAYTIME_MINUTES", "BLOCK_BREAK", "MOB_KILL", "ITEM_CRAFT", "ROLEPLAY_QUEST_COMPLETE"));

		return NO_COMPLETE;
	}
}
