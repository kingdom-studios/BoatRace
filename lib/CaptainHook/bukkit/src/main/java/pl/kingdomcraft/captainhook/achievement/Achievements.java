package pl.kingdomcraft.captainhook.achievement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.model.Replacer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.event.AchievementUnlockEvent;
import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.proxy.ProxyLink;
import pl.kingdomcraft.captainhook.settings.Localization;

/**
 * Network achievements as seen from this server. The proxy owns the logic and the data (achievements.yml, table
 * ch_achievements); this class forwards progress and announces unlocks to the players on this server.
 */
public final class Achievements {

	private Achievements() {
	}

	/**
	 * Listens for unlocks pushed by the proxy. Call on startup and after every reload.
	 */
	public static void start() {
		ProxyLink.on(Protocol.ACHIEVEMENT_UNLOCKED, data -> {
			final UUID uuid = Json.getUuid(data, "uuid");
			final Player player = uuid != null ? Bukkit.getPlayer(uuid) : null;

			if (player == null)
				return; // left this server in the meantime, the unlock itself is saved

			final String id = Json.getString(data, "id", "");
			final String name = Json.getString(data, "name", id);
			final String description = Json.getString(data, "description", "");

			Common.tell(player, Replacer.replaceArray(Localization.Achievement.UNLOCKED, "name", name, "description", description));
			Common.callEvent(new AchievementUnlockEvent(player, id, name, description));
		});
	}

	/**
	 * Adds progress to an achievement whose source is "manual". Fails with ProxyException "unknown_achievement"
	 * for an ID missing from achievements.yml and "bad_request" for achievements that follow a stat.
	 */
	public static CompletableFuture<AchievementProgress> addProgress(final UUID uuid, final String id, final long amount) {
		if (amount <= 0)
			throw new IllegalArgumentException("Progress must be positive, got " + amount);

		final JsonObject data = new JsonObject();

		data.addProperty("uuid", uuid.toString());
		data.addProperty("id", id);
		data.addProperty("amount", amount);

		// Progress reported while nobody can carry the message waits for the next player instead of being lost
		return ProxyLink.request(Protocol.ACHIEVEMENT_PROGRESS, data, uuid, true).thenApply(AchievementProgress::fromJson);
	}

	/**
	 * @return the standing of any player (online or not) in every achievement, in the order of achievements.yml
	 */
	public static CompletableFuture<List<AchievementProgress>> list(final UUID uuid) {
		final JsonObject data = new JsonObject();

		data.addProperty("uuid", uuid.toString());

		return ProxyLink.request(Protocol.ACHIEVEMENT_LIST, data, uuid, false).thenApply(reply -> {
			final List<AchievementProgress> list = new ArrayList<>();

			for (final JsonElement element : Json.getArray(reply, "achievements"))
				if (element.isJsonObject())
					list.add(AchievementProgress.fromJson(element.getAsJsonObject()));

			return list;
		});
	}
}
