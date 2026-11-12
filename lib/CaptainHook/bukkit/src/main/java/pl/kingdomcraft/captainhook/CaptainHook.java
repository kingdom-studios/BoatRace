package pl.kingdomcraft.captainhook;

import org.mineacademy.fo.Common;
import org.mineacademy.fo.model.HookManager;
import org.mineacademy.fo.plugin.SimplePlugin;

import pl.kingdomcraft.captainhook.achievement.Achievements;
import pl.kingdomcraft.captainhook.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.hook.VanishHook;
import pl.kingdomcraft.captainhook.minigame.Minigames;
import pl.kingdomcraft.captainhook.mission.MissionNotifications;
import pl.kingdomcraft.captainhook.mission.MissionTracker;
import pl.kingdomcraft.captainhook.pass.SeasonPass;
import pl.kingdomcraft.captainhook.proxy.ProxyLink;
import pl.kingdomcraft.captainhook.proxy.ProxyListener;
import pl.kingdomcraft.captainhook.reward.RewardExecutor;
import pl.kingdomcraft.captainhook.settings.Settings;
import pl.kingdomcraft.captainhook.stats.StatsSync;

/**
 * Captain Hook on a Paper server - the client of the CaptainHook plugin on the Velocity proxy.
 *
 * This side collects player stats, runs the commands (/link, /kc) and placeholders and offers
 * {@link CaptainHookAPI} to other plugins. It has no database: everything is stored by the proxy, reached over
 * the captainhook:main plugin messaging channel (see {@link ProxyLink}). Only account linking still talks to
 * the website directly (web API).
 *
 * Commands, listeners, placeholders, settings and localization are registered automatically by Foundation.
 */
public final class CaptainHook extends SimplePlugin {

	@Override
	protected void onPluginStart() {
	}

	/**
	 * Foundation calls this before onPluginStart and again on every reload.
	 */
	@Override
	protected void onReloadablesStart() {
		if ("CHANGE-ME".equals(Settings.Api.SECRET))
			Common.warning("api.secret in config.yml is not set, all web API requests will be rejected!");

		if (!HookManager.isVaultLoaded())
			Common.warning("Vault (or an economy plugin) is missing, player balances will be saved as 0.");

		// Stopping forgets the notification handlers, so the link starts first and the features register after it
		ProxyLink.start();
		KingdomCoins.start();
		SeasonPass.start();
		Achievements.start();
		Minigames.start();
		MissionNotifications.start();
		MissionTracker.start();
		RewardExecutor.start();
		StatsSync.start();
		VanishHook.start();

		// After a reload with players online, refill the caches
		Common.runLater(ProxyListener::readyAll);
	}

	@Override
	protected void onPluginPreReload() {
		MissionTracker.flush();
		MissionTracker.stop();
		StatsSync.stop();
		ProxyLink.stop();
	}

	@Override
	protected void onPluginStop() {
		MissionTracker.stop();
		StatsSync.shutdown();
		ProxyLink.stop();
	}

	public static CaptainHook getInstance() {
		return (CaptainHook) SimplePlugin.getInstance();
	}
}
