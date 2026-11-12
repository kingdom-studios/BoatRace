package pl.kingdomcraft.captainhook.mission;

import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.model.Replacer;
import org.mineacademy.fo.remain.Remain;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.proxy.ProxyLink;
import pl.kingdomcraft.captainhook.settings.Localization;

/**
 * Tells players when they finish a mission (mission.completed from the proxy).
 */
public final class MissionNotifications {

	private MissionNotifications() {
	}

	/**
	 * Call on startup and after every reload.
	 */
	public static void start() {
		ProxyLink.on(Protocol.MISSION_COMPLETED, MissionNotifications::onCompleted);
	}

	/**
	 * @return the mission title in the player's language: Polish for Polish clients, English otherwise
	 */
	public static String title(final Player player, final JsonObject mission, final String keyEn, final String keyPl) {
		final String locale = player.getLocale();

		return locale != null && locale.toLowerCase().startsWith("pl") ? Json.getString(mission, keyPl, Json.getString(mission, keyEn, "")) : Json.getString(mission, keyEn, "");
	}

	private static void onCompleted(final JsonObject data) {
		final UUID uuid = Json.getUuid(data, "uuid");
		final Player player = uuid != null ? Bukkit.getPlayer(uuid) : null;

		if (player == null)
			return;

		final long xp = Json.getLong(data, "xp", 0);
		final String message = Replacer.replaceArray(xp > 0 ? Localization.Mission.COMPLETED : Localization.Mission.COMPLETED_NO_XP,
				"title", title(player, data, "titleEn", "titlePl"), "xp", xp);

		Common.tell(player, message);
		Remain.sendActionBar(player, message);
	}
}
