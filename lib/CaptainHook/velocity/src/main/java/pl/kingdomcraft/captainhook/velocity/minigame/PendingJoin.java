package pl.kingdomcraft.captainhook.velocity.minigame;

import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * A player on their way to a minigame server, one row of ch_minigame_pending_joins: the server that sends them
 * queues it, the server that receives them consumes it and puts the player into the game.
 *
 * @param server    Velocity name of the target server
 * @param arena     which arena to join, null if the minigame decides
 * @param data      free text for the minigame plugin (e.g. JSON with the party), may be null
 * @param expiresAt Unix seconds after which the join is forgotten
 */
public record PendingJoin(UUID uuid, String game, String server, String arena, String data, long createdAt, long expiresAt) {

	public JsonObject toJson() {
		final JsonObject json = new JsonObject();

		json.addProperty("uuid", this.uuid.toString());
		json.addProperty("game", this.game);
		json.addProperty("server", this.server);

		if (this.arena != null)
			json.addProperty("arena", this.arena);

		if (this.data != null)
			json.addProperty("data", this.data);

		return json;
	}
}
