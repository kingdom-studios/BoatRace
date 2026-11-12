package pl.kingdomcraft.captainhook.reward;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.proxy.ProxyLink;
import pl.kingdomcraft.captainhook.settings.Localization;

/**
 * Runs command rewards sent by the proxy (reward.dispatch) and confirms them (reward.ack). The rewards come from
 * ch_reward_queue: achievements, season pass tiers and claims made on the website.
 *
 * Safety rules, because the commands run as the console:
 * - only for a player who is on this server right now, otherwise nothing runs and the proxy tries again later
 * - the only placeholders are {player} and {uuid}, and the name is only used if it is a plain Minecraft name
 * - at most 20 commands of at most 256 characters, single line, no leading slash needed
 * - a reward ID that already ran here is only acknowledged again, never run twice (the proxy resends rewards
 *   whose ack got lost)
 *
 * Main thread only: notifications from the proxy are handled there.
 */
public final class RewardExecutor {

	/**
	 * Names that are safe inside a command. Bedrock players (Floodgate) may start with a dot.
	 */
	private static final Pattern SAFE_NAME = Pattern.compile("\\.?[A-Za-z0-9_]{1,16}");

	private static final int REMEMBERED = 5000;

	/**
	 * Reward IDs run on this server since it started, oldest first.
	 */
	private static final Map<Long, Boolean> executed = new LinkedHashMap<Long, Boolean>(256, 0.75f, false) {
		private static final long serialVersionUID = 1L;

		@Override
		protected boolean removeEldestEntry(final Map.Entry<Long, Boolean> eldest) {
			return this.size() > REMEMBERED;
		}
	};

	private RewardExecutor() {
	}

	/**
	 * Listens for reward.dispatch. Call on startup and after every reload.
	 */
	public static void start() {
		ProxyLink.on(Protocol.REWARD_DISPATCH, RewardExecutor::onDispatch);
	}

	private static void onDispatch(final JsonObject data) {
		final long id = Json.getLong(data, "id", 0);
		final UUID uuid = Json.getUuid(data, "uuid");

		if (id <= 0 || uuid == null)
			return;

		if (executed.containsKey(id)) {
			acknowledge(id, uuid, true, 0, null);

			return;
		}

		final Player player = Bukkit.getPlayer(uuid);

		if (player == null || !player.isOnline()) {
			acknowledge(id, uuid, false, 0, "player_offline");

			return;
		}

		final List<String> commands = readCommands(data);

		if (commands == null || !SAFE_NAME.matcher(player.getName()).matches()) {
			Common.warning("Refusing reward #" + id + " for " + player.getName() + ": invalid commands or player name. Fix the row in ch_reward_queue.");
			acknowledge(id, uuid, false, 0, "refused");

			return;
		}

		executed.put(id, Boolean.TRUE);

		int failed = 0;

		for (final String command : commands) {
			final String resolved = command.replace("{player}", player.getName()).replace("{uuid}", uuid.toString());

			try {
				if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved))
					failed++;

			} catch (final RuntimeException ex) {
				failed++;
				Common.error(ex, "Reward #" + id + " command failed: " + resolved);
			}
		}

		final String message = Json.getString(data, "message", null);

		Common.tell(player, message != null && !message.isEmpty() ? Localization.Reward.RECEIVED_PREFIX + message : Localization.Reward.RECEIVED);

		if (failed > 0)
			Common.log("&cReward #" + id + " for " + player.getName() + ": " + failed + " of " + commands.size() + " command(s) failed.");

		acknowledge(id, uuid, true, failed, null);
	}

	/**
	 * @return the commands without a leading slash, or null if the list breaks the rules
	 */
	private static List<String> readCommands(final JsonObject data) {
		final List<String> commands = new ArrayList<>();

		for (final JsonElement element : Json.getArray(data, "commands")) {
			if (!element.isJsonPrimitive())
				return null;

			String command = element.getAsString().trim();

			if (command.startsWith("/"))
				command = command.substring(1);

			if (command.isEmpty() || command.length() > Protocol.MAX_REWARD_COMMAND_LENGTH || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0)
				return null;

			commands.add(command);
		}

		return commands.isEmpty() || commands.size() > Protocol.MAX_REWARD_COMMANDS ? null : commands;
	}

	/**
	 * If nobody can carry the ack, the proxy sends the reward again later and it is only acknowledged then.
	 */
	private static void acknowledge(final long id, final UUID uuid, final boolean executedHere, final int failed, final String reason) {
		final JsonObject ack = new JsonObject();

		ack.addProperty("id", id);
		ack.addProperty("uuid", uuid.toString());
		ack.addProperty("executed", executedHere);
		ack.addProperty("failed", failed);

		if (reason != null)
			ack.addProperty("reason", reason);

		ProxyLink.notify(Protocol.REWARD_ACK, ack, uuid);
	}
}
