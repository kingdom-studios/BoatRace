package pl.kingdomcraft.captainhook.proxy;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.remain.Remain;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.minigame.Minigames;
import pl.kingdomcraft.captainhook.pass.SeasonPass;
import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Protocol;

/**
 * Notices when a player can carry messages to the proxy, and then asks the proxy for everything this server
 * caches about that player (player.ready).
 *
 * A player cannot carry right when they join: Bukkit only sends plugin messages on channels the other side
 * registered, and the proxy registers captainhook:main a moment after the join. PlayerRegisterChannelEvent
 * is that moment.
 */
@AutoRegister
public final class ProxyListener implements Listener {

	private static final int WARN_AFTER_TICKS = 5 * 20;

	private static boolean missingProxyWarned;

	@EventHandler(priority = EventPriority.MONITOR)
	public void onRegisterChannel(final PlayerRegisterChannelEvent event) {
		if (Protocol.CHANNEL.equals(event.getChannel()))
			ready(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(final PlayerJoinEvent event) {
		final UUID uuid = event.getPlayer().getUniqueId();

		Common.runLater(WARN_AFTER_TICKS, () -> {
			final Player player = Bukkit.getPlayer(uuid);

			if (player != null && !ProxyLink.canCarry(player) && !missingProxyWarned) {
				missingProxyWarned = true;

				Common.warning("The proxy did not register the " + Protocol.CHANNEL + " channel for " + player.getName() + ". Is CaptainHook installed on Velocity, "
						+ "and do players join through the proxy? Until then stats, KingdomCoins and the API do not work on this server.");
			}
		});
	}

	/**
	 * Reports everyone who can already carry, for a plugin reload with players online.
	 */
	public static void readyAll() {
		for (final Player player : Remain.getOnlinePlayers())
			if (ProxyLink.canCarry(player))
				ready(player);
	}

	private static void ready(final Player player) {
		final UUID uuid = player.getUniqueId();
		final JsonObject data = new JsonObject();

		data.addProperty("uuid", uuid.toString());
		data.addProperty("name", player.getName());

		ProxyLink.request(Protocol.PLAYER_READY, data, uuid, false).whenComplete((reply, error) -> {
			if (error != null) {
				Common.warning("The proxy did not answer " + Protocol.PLAYER_READY + " for " + player.getName() + " (" + error.getMessage() + ")");

				return;
			}

			if (!player.isOnline())
				return;

			KingdomCoins.onPlayerReady(uuid, reply);
			SeasonPass.onPlayerReady(uuid, reply);

			if (Json.hasObject(reply, "join"))
				Minigames.deliverJoin(player, Json.getObject(reply, "join"));
		});

		// Whatever waited for a carrier can go now
		ProxyLink.flushQueue();
	}
}
