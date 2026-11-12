package pl.kingdomcraft.captainhook.command.coins;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.captainhook.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.settings.Localization.Coins;

/**
 * /kc pay <player> <amount>
 */
final class PaySubCommand extends CoinsSubCommand {

	PaySubCommand(final SimpleCommandGroup parent) {
		super(parent, "pay|przelej", "<gracz> <ilość>", Coins.DESCRIPTION_PAY, null);

		setMinArguments(2);
	}

	@Override
	protected void onCommand() {
		checkConsole();

		final Player player = getPlayer();
		final long amount = findAmount(1, 1);

		findAccount(args[0], target -> {
			if (target.getUuid().equals(player.getUniqueId())) {
				Common.tell(player, Coins.PAY_SELF);

				return;
			}

			then(KingdomCoins.pay(player.getUniqueId(), target.getUuid(), amount), result -> {
				switch (result.getStatus()) {
					case SUCCESS:
						final Player receiver = Bukkit.getPlayer(target.getUuid());

						Common.tell(player, replace(Coins.PAY_SENT, "amount", amount, "player", target.getName(), "balance", result.getBalance()));

						if (receiver != null)
							Common.tell(receiver, replace(Coins.PAY_RECEIVED, "amount", amount, "player", player.getName()));
						break;

					case INSUFFICIENT_FUNDS:
						Common.tell(player, Coins.NOT_ENOUGH);
						break;

					case NOT_FOUND:
						Common.tell(player, replace(Coins.PLAYER_NOT_FOUND, "player", target.getName()));
						break;
				}
			});
		});
	}
}
