package pl.kingdomcraft.captainhook.command.coins;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.captainhook.Permissions;
import pl.kingdomcraft.captainhook.coins.CoinResult;
import pl.kingdomcraft.captainhook.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.coins.TransactionType;
import pl.kingdomcraft.captainhook.settings.Localization.Coins;

/**
 * /kc give|take|set <player> <amount> [reason] - one class, three admin subcommands.
 */
final class ModifySubCommand extends CoinsSubCommand {

	private final TransactionType type;

	ModifySubCommand(final SimpleCommandGroup parent, final TransactionType type) {
		super(parent, type.name().toLowerCase(), "<gracz> <ilość> [powód]",
				type == TransactionType.GIVE ? Coins.DESCRIPTION_GIVE : type == TransactionType.TAKE ? Coins.DESCRIPTION_TAKE : Coins.DESCRIPTION_SET,
				Permissions.COINS_ADMIN);

		this.type = type;
		setMinArguments(2);
	}

	@Override
	protected void onCommand() {
		final CommandSender sender = this.sender;
		final long amount = findAmount(1, this.type == TransactionType.SET ? 0 : 1);
		final String reason = args.length > 2 ? joinArgs(2) : null;
		final String actor = getActor();

		findAccount(args[0], target -> then(this.modify(target.getUuid(), amount, actor, reason), result -> {
			if (result.getStatus() == CoinResult.Status.INSUFFICIENT_FUNDS) {
				Common.tell(sender, replace(Coins.ADMIN_NOT_ENOUGH, "player", target.getName()));

				return;
			}

			if (!result.isSuccess()) {
				Common.tell(sender, replace(Coins.PLAYER_NOT_FOUND, "player", target.getName()));

				return;
			}

			final String message = this.type == TransactionType.GIVE ? Coins.ADMIN_GIVE : this.type == TransactionType.TAKE ? Coins.ADMIN_TAKE : Coins.ADMIN_SET;
			final Player online = Bukkit.getPlayer(target.getUuid());

			Common.tell(sender, replace(message, "amount", amount, "player", target.getName(), "balance", result.getBalance()));

			if (online != null && online != sender)
				Common.tell(online, replace(Coins.BALANCE_CHANGED, "balance", result.getBalance()));
		}));
	}

	private CompletableFuture<CoinResult> modify(final UUID uuid, final long amount, final String actor, final String reason) {
		switch (this.type) {
			case GIVE:
				return KingdomCoins.give(uuid, amount, TransactionType.GIVE, actor, reason);
			case TAKE:
				return KingdomCoins.take(uuid, amount, TransactionType.TAKE, actor, reason);
			default:
				return KingdomCoins.set(uuid, amount, actor, reason);
		}
	}
}
