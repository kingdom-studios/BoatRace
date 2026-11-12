package pl.kingdomcraft.captainhook.velocity;

import java.util.Collection;
import java.util.UUID;

import com.google.gson.JsonObject;

/**
 * What the services need from the proxy: who is where, and a way to talk to the Paper servers. Kept behind an
 * interface so the services can be tested without a running proxy.
 */
public interface Network {

	/**
	 * @return UUIDs of everyone connected to the proxy
	 */
	Collection<UUID> onlinePlayers();

	/**
	 * @return the Velocity name of the server the player is on, or null when offline or between servers
	 */
	String currentServer(UUID player);

	boolean serverExists(String server);

	/**
	 * Starts moving the player to the server.
	 *
	 * @return false if the player is offline or the server does not exist
	 */
	boolean connect(UUID player, String server);

	/**
	 * Sends a notification to the Paper server the player is on.
	 *
	 * @return false if the player is not on a server, so nothing was sent
	 */
	boolean notify(UUID player, String type, JsonObject data);

	/**
	 * Sends a notification to every Paper server that has at least one player.
	 */
	void broadcast(String type, JsonObject data);
}
