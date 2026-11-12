package pl.kingdomcraft.captainhook.command.coins;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.command.SimpleCommandGroup;
import org.mineacademy.fo.command.SimpleSubCommand;
import org.mineacademy.fo.model.Replacer;

import pl.kingdomcraft.captainhook.coins.CoinAccount;
import pl.kingdomcraft.captainhook.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.proxy.ProxyException;
import pl.kingdomcraft.captainhook.settings.Localization.Coins;

/**
 * Shared helpers of /kc subcommands. The coins are with the proxy, so every call is async: callbacks must use
 * the captured sender and Common.tell instead of tell(), because the command instance is reused by the next caller.
 */
abstract class CoinsSubCommand extends SimpleSubCommand {

	protected CoinsSubCommand(final SimpleCommandGroup parent, final String sublabels, final String usage, final String description, final String permission) {
		super(parent, sublabels);

		setUsage(usage);
		setDescription(description);
		setPermission(permission);
	}

	/**
	 * Runs the callback once the proxy answered (on the main thread, where its futures complete), or tells the
	 * sender that KingdomCoins are unavailable right now.
	 */
	protected final <T> void then(final CompletableFuture<T> future, final Consumer<T> callback) {
		final CommandSender sender = this.sender;
		final String command = "/" + getLabel() + " " + getSublabel();

		future.whenComplete((result, error) -> {
			if (error == null) {
				callback.accept(result);

				return;
			}

			final Throwable cause = error.getCause() != null ? error.getCause() : error;

			// The proxy being unreachable or its database being down is logged by the proxy, one line is enough here
			if (cause instanceof ProxyException)
				Common.log("&cKingdomCoins command " + command + " of " + sender.getName() + " failed: " + cause.getMessage());
			else
				Common.error(cause, "KingdomCoins command " + command + " failed for " + sender.getName());

			Common.tell(sender, Coins.DATABASE_ERROR);
		});
	}

	/**
	 * Resolves an account by name (online or offline) or tells the sender the player was not found.
	 */
	protected final void findAccount(final String name, final Consumer<CoinAccount> callback) {
		final CommandSender sender = this.sender;

		then(KingdomCoins.find(name), account -> {
			if (account == null)
				Common.tell(sender, replace(Coins.PLAYER_NOT_FOUND, "player", name));
			else
				callback.accept(account);
		});
	}

	protected final long findAmount(final int index, final long min) {
		try {
			final long amount = Long.parseLong(args[index]);

			if (amount >= min && amount <= KingdomCoins.MAX_AMOUNT)
				return amount;

		} catch (final NumberFormatException ignored) {
		}

		returnTell(replace(Coins.INVALID_AMOUNT, "min", min, "max", KingdomCoins.MAX_AMOUNT));
		return 0;
	}

	/**
	 * @return UUID of the player running the command, or "CONSOLE", stored as the transaction actor
	 */
	protected final String getActor() {
		return sender instanceof Player ? ((Player) sender).getUniqueId().toString() : "CONSOLE";
	}

	/**
	 * Replaces {key} placeholders, formatting numbers with thousands separators.
	 */
	static String replace(final String message, final Object... pairs) {
		for (int i = 1; i < pairs.length; i += 2)
			if (pairs[i] instanceof Long || pairs[i] instanceof Integer)
				pairs[i] = KingdomCoins.format(((Number) pairs[i]).longValue());

		return Replacer.replaceArray(message, pairs);
	}

	@Override
	protected List<String> tabComplete() {
		return args.length == 1 ? completeLastWordPlayerNames() : NO_COMPLETE;
	}
}
