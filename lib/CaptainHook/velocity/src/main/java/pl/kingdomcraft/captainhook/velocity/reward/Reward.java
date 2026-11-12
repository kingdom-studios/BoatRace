package pl.kingdomcraft.captainhook.velocity.reward;

import java.util.List;
import java.util.regex.Pattern;

import pl.kingdomcraft.captainhook.velocity.config.ConfigException;
import pl.kingdomcraft.captainhook.velocity.config.YamlFile;

/**
 * What a player gets for an achievement or a season pass tier, as configured under a "rewards" key:
 *
 * <pre>
 * rewards:
 *   kingdomcoins: 50
 *   pass-xp: 100
 *   commands: ["give {player} diamond 3"]
 *   message: "+3 diamonds"
 *   require-server: "survival"
 * </pre>
 *
 * Coins and pass XP are granted by the proxy in the transaction that unlocks or claims. Commands have to run on
 * a Paper server, so they are written to the reward queue (see {@link Rewards}).
 *
 * @param commands console commands, {player} is the player's name and {uuid} their UUID
 * @param message       shown to the player when the commands run, null for none
 * @param requireServer Velocity name of the only server the commands may run on (e.g. an item of one game
 *                      mode), null for any server. Coins and pass XP are network-wide and not affected.
 */
public record Reward(long kingdomCoins, long passXp, List<String> commands, String message, String requireServer) {

	public static final Reward NONE = new Reward(0, 0, List.of(), null, null);

	/**
	 * Velocity server names (velocity.toml [servers]).
	 */
	public static final Pattern SERVER_PATTERN = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

	private static final long MAX_AMOUNT = 1_000_000_000_000L;

	public Reward {
		commands = List.copyOf(commands);
	}

	public boolean isEmpty() {
		return this.kingdomCoins == 0 && this.passXp == 0 && this.commands.isEmpty();
	}

	public static Reward parse(final YamlFile section) throws ConfigException {
		final long coins = section.getLong("kingdomcoins", 0);
		final long xp = section.getLong("pass-xp", 0);

		if (coins < 0 || coins > MAX_AMOUNT)
			throw section.invalid("kingdomcoins", "must be between 0 and " + MAX_AMOUNT);

		if (xp < 0 || xp > MAX_AMOUNT)
			throw section.invalid("pass-xp", "must be between 0 and " + MAX_AMOUNT);

		final List<String> commands = section.getStringList("commands").stream()
				.map(command -> command.startsWith("/") ? command.substring(1) : command)
				.filter(command -> !command.isBlank())
				.toList();

		final String message = section.getString("message", null);

		if (message != null && message.length() > 255)
			throw section.invalid("message", "must be at most 255 characters");

		final String requireServer = section.getString("require-server", null);

		if (requireServer != null && !SERVER_PATTERN.matcher(requireServer).matches())
			throw section.invalid("require-server", "must be a server name from velocity.toml (letters, digits, _ . -)");

		if (requireServer != null && commands.isEmpty())
			throw section.invalid("require-server", "only applies to commands, but this reward has none");

		final Reward reward = new Reward(coins, xp, commands, message, requireServer);

		return reward.isEmpty() ? NONE : reward;
	}
}
