package pl.kingdomcraft.captainhook.command.coins;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.bukkit.entity.Player;
import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.command.SimpleCommandGroup;
import org.mineacademy.fo.model.SimpleComponent;

import pl.kingdomcraft.captainhook.coins.TransactionType;
import pl.kingdomcraft.captainhook.settings.Settings;

/**
 * /kingdomcoins (aliases from kingdomcoins.command-aliases). Without arguments players see their balance.
 */
@AutoRegister
public final class KingdomCoinsCommandGroup extends SimpleCommandGroup {

	public KingdomCoinsCommandGroup() {
		super(Settings.KingdomCoins.COMMAND_ALIASES.get(0),
				new ArrayList<>(Settings.KingdomCoins.COMMAND_ALIASES.subList(1, Settings.KingdomCoins.COMMAND_ALIASES.size())));
	}

	@Override
	protected void registerSubcommands() {
		registerSubcommand(new BalanceSubCommand(this));
		registerSubcommand(new PaySubCommand(this));
		registerSubcommand(new TopSubCommand(this));
		registerSubcommand(new ModifySubCommand(this, TransactionType.GIVE));
		registerSubcommand(new ModifySubCommand(this, TransactionType.TAKE));
		registerSubcommand(new ModifySubCommand(this, TransactionType.SET));
		registerSubcommand(new HistorySubCommand(this));
	}

	@Override
	protected boolean sendHelpIfNoArgs() {
		return !(sender instanceof Player);
	}

	@Override
	protected List<SimpleComponent> getNoParamsHeader() {
		return Collections.singletonList(SimpleComponent.of(BalanceSubCommand.getOwnBalanceMessage((Player) sender)));
	}
}
