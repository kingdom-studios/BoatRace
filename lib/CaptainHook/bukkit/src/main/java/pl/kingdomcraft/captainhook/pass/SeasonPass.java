package pl.kingdomcraft.captainhook.pass;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.model.Replacer;
import org.mineacademy.fo.model.SimpleComponent;
import org.mineacademy.fo.remain.Remain;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.event.PassLevelUpEvent;
import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.proxy.ProxyLink;
import pl.kingdomcraft.captainhook.settings.Localization;
import pl.kingdomcraft.captainhook.settings.Settings;

/**
 * The season pass as seen from this server. The proxy owns the logic and the data (seasonpass.yml, tables
 * ch_seasonpass*); this class forwards XP, caches the progress of the players on this server for placeholders
 * and announces level-ups.
 */
public final class SeasonPass {

	private static final Map<UUID, PassProgress> progress = new ConcurrentHashMap<>();

	private SeasonPass() {
	}

	/**
	 * Listens for progress pushed by the proxy. Call on startup and after every reload.
	 */
	public static void start() {
		ProxyLink.on(Protocol.PASS_UPDATE, data -> {
			final UUID uuid = Json.getUuid(data, "uuid");
			final Player player = uuid != null ? Bukkit.getPlayer(uuid) : null;

			if (player == null)
				return; // left this server in the meantime

			final PassProgress updated = PassProgress.fromJson(data);
			final int levelBefore = Json.getInt(data, "levelBefore", updated.getLevel());

			progress.put(uuid, updated);

			if (updated.getLevel() > levelBefore) {
				final Object[] vars = { "season", updated.getSeasonName(), "level", updated.getLevel() };

				Common.tell(player, Replacer.replaceArray(Localization.Pass.LEVEL_UP, vars));
				Remain.sendTitle(player, Replacer.replaceArray(Localization.Pass.LEVEL_UP_TITLE, vars), Replacer.replaceArray(Localization.Pass.LEVEL_UP_SUBTITLE, vars));
				player.playSound(player.getLocation(), "entity.player.levelup", 1F, 1F);
				SimpleComponent.of(Replacer.replaceArray(Localization.Pass.LEVEL_UP_LINK, vars)).onHover(Localization.Pass.LEVEL_UP_HOVER).onClickOpenUrl(Settings.Missions.CLAIM_URL).send(player);
				Common.callEvent(new PassLevelUpEvent(player, updated, levelBefore));
			}
		});
	}

	/**
	 * Caches what the proxy sent about a player who just arrived (the reply to player.ready).
	 */
	public static void onPlayerReady(final UUID uuid, final JsonObject reply) {
		if (Json.hasObject(reply, "pass"))
			progress.put(uuid, PassProgress.fromJson(Json.getObject(reply, "pass")));
		else
			progress.remove(uuid); // no season is active
	}

	public static void unload(final UUID uuid) {
		progress.remove(uuid);
	}

	/**
	 * @return the cached progress of a player on this server, or null if not loaded or no season is active
	 */
	public static PassProgress getCached(final UUID uuid) {
		return progress.get(uuid);
	}

	/**
	 * Adds season pass XP. Fails with ProxyException "no_season" when no season is active.
	 */
	public static CompletableFuture<PassProgress> addXp(final UUID uuid, final long amount) {
		if (amount <= 0)
			throw new IllegalArgumentException("XP must be positive, got " + amount);

		final JsonObject data = new JsonObject();

		data.addProperty("uuid", uuid.toString());
		data.addProperty("amount", amount);

		// XP reported while nobody can carry the message waits for the next player instead of being lost
		return ProxyLink.request(Protocol.PASS_ADD_XP, data, uuid, true).thenApply(PassProgress::fromJson);
	}

	/**
	 * Asks the proxy for the progress of any player, online or not. Fails with ProxyException "no_season"
	 * when no season is active.
	 */
	public static CompletableFuture<PassProgress> get(final UUID uuid) {
		final JsonObject data = new JsonObject();

		data.addProperty("uuid", uuid.toString());

		return ProxyLink.request(Protocol.PASS_GET, data, uuid, false).thenApply(PassProgress::fromJson);
	}
}
