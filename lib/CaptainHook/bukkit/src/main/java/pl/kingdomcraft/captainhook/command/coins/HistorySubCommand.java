package pl.kingdomcraft.captainhook.command.coins;

import java.text.SimpleDateFormat;
import java.util.Date;

import org.bukkit.command.CommandSender;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.captainhook.Permissions;
import pl.kingdomcraft.captainhook.coins.CoinTransaction;
import pl.kingdomcraft.captainhook.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.coins.TransactionType;
import pl.kingdomcraft.captainhook.settings.Localization.Coins;

/**
 * /kc history <player> [page]
 */
final class HistorySubCommand extends CoinsSubCommand {

	private static final int PAGE_SIZE = 10;

	HistorySubCommand(final SimpleCommandGroup parent) {
		super(parent, "history|historia", "<gracz> [strona]", Coins.DESCRIPTION_HISTORY, Permissions.COINS_ADMIN);

		setMinArguments(1);
	}

	@Override
	protected void onCommand() {
		final CommandSender sender = this.sender;
		final int page = args.length > 1 ? findNumber(1, 1, Integer.MAX_VALUE, Coins.INVALID_PAGE) : 1;

		findAccount(args[0], account -> then(KingdomCoins.getHistory(account.getUuid(), PAGE_SIZE, (page - 1) * PAGE_SIZE), history -> {
			if (history.isEmpty()) {
				Common.tell(sender, Coins.HISTORY_EMPTY);

				return;
			}

			final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm");

			Common.tellNoPrefix(sender, replace(Coins.HISTORY_HEADER, "player", account.getName(), "page", String.valueOf(page)));

			for (final CoinTransaction transaction : history) {
				final boolean signed = !transaction.getType().equals(TransactionType.SET.name()) && transaction.getAmount() > 0;

				Common.tellNoPrefix(sender, replace(Coins.HISTORY_LINE,
						"date", dateFormat.format(new Date(transaction.getCreatedAt() * 1000)),
						"type", transaction.getType(),
						"amount", (signed ? "+" : "") + KingdomCoins.format(transaction.getAmount()),
						"balance", transaction.getBalanceAfter(),
						"server", transaction.getServer(),
						"reason", transaction.getReason() != null ? transaction.getReason() : "-"));
			}
		}));
	}
}
