package pl.kingdomcraft.captainhook.placeholder;

import java.util.List;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.model.SimpleExpansion;

import pl.kingdomcraft.captainhook.coins.CoinAccount;
import pl.kingdomcraft.captainhook.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.pass.PassProgress;
import pl.kingdomcraft.captainhook.pass.SeasonPass;

/**
 * PlaceholderAPI placeholders, served from memory only (safe for scoreboards refreshing every tick):
 *
 * %captainhook_kingdomcoins%                  balance of the viewer, e.g. 1250000
 * %captainhook_kingdomcoins_formatted%        balance of the viewer, e.g. 1 250 000
 * %captainhook_kingdomcoins_top_<1-10>_name%  name at the given top position
 * %captainhook_kingdomcoins_top_<1-10>%       formatted balance at the given top position
 * %captainhook_pass_level%                    season pass level of the viewer
 * %captainhook_pass_xp%                       season pass XP of the viewer towards the next level
 * %captainhook_pass_season%                   name of the current season
 */
@AutoRegister
public final class CaptainHookPlaceholders extends SimpleExpansion {

	/**
	 * @AutoRegister registers expansions as singletons: it needs this field plus a single private no-args
	 * constructor, and registers the instance itself (identifier = plugin name, "captainhook").
	 */
	private static final CaptainHookPlaceholders instance = new CaptainHookPlaceholders();

	private CaptainHookPlaceholders() {
	}

	@Override
	protected String onReplace(final CommandSender sender, final String identifier) {
		if (identifier.startsWith("pass_"))
			return this.replacePass(sender, identifier);

		if (!identifier.startsWith("kingdomcoins"))
			return NO_REPLACE;

		if (identifier.equals("kingdomcoins") || identifier.equals("kingdomcoins_formatted")) {
			final long balance = sender instanceof Player ? KingdomCoins.getCachedBalance(((Player) sender).getUniqueId()) : -1;

			if (balance < 0)
				return "...";

			return identifier.endsWith("formatted") ? KingdomCoins.format(balance) : String.valueOf(balance);
		}

		// kingdomcoins_top_<position>[_name]
		if (args.length >= 3 && args[1].equals("top")) {
			final List<CoinAccount> top = KingdomCoins.getTop();
			final int position;

			try {
				position = Integer.parseInt(args[2]);

			} catch (final NumberFormatException ex) {
				return NO_REPLACE;
			}

			if (position < 1 || position > top.size())
				return "-";

			final CoinAccount account = top.get(position - 1);

			return args.length > 3 && args[3].equals("name") ? account.getName() : KingdomCoins.format(account.getBalance());
		}

		return NO_REPLACE;
	}

	private String replacePass(final CommandSender sender, final String identifier) {
		final PassProgress progress = sender instanceof Player ? SeasonPass.getCached(((Player) sender).getUniqueId()) : null;

		// Not loaded yet, or no season is active
		if (progress == null)
			return identifier.equals("pass_season") ? "-" : "0";

		switch (identifier) {
			case "pass_level":
				return String.valueOf(progress.getLevel());
			case "pass_xp":
				return String.valueOf(progress.getXpIntoLevel());
			case "pass_season":
				return progress.getSeasonName();
			default:
				return NO_REPLACE;
		}
	}
}
