package pl.kingdomcraft.captainhook.velocity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.google.gson.JsonObject;

/**
 * A proxy without a network: players are put on servers by hand and everything sent is recorded.
 */
final class FakeNetwork implements Network {

	record Sent(UUID player, String type, JsonObject data) {
	}

	final Map<UUID, String> locations = new HashMap<>();
	final Set<String> servers = new HashSet<>();
	final List<Sent> notifications = new ArrayList<>();
	final List<Sent> broadcasts = new ArrayList<>();
	final List<String> connections = new ArrayList<>();

	UUID join(final String server) {
		final UUID uuid = UUID.randomUUID();

		this.servers.add(server);
		this.locations.put(uuid, server);
		return uuid;
	}

	List<JsonObject> sent(final UUID player, final String type) {
		synchronized (this.notifications) {
			return this.notifications.stream().filter(sent -> sent.player().equals(player) && sent.type().equals(type)).map(Sent::data).collect(Collectors.toList());
		}
	}

	@Override
	public Collection<UUID> onlinePlayers() {
		return this.locations.keySet();
	}

	@Override
	public String currentServer(final UUID player) {
		return this.locations.get(player);
	}

	@Override
	public boolean serverExists(final String server) {
		return this.servers.contains(server);
	}

	@Override
	public boolean connect(final UUID player, final String server) {
		if (!this.locations.containsKey(player) || !this.servers.contains(server))
			return false;

		this.connections.add(player + ">" + server);
		return true;
	}

	@Override
	public boolean notify(final UUID player, final String type, final JsonObject data) {
		if (!this.locations.containsKey(player))
			return false;

		synchronized (this.notifications) {
			this.notifications.add(new Sent(player, type, data));
		}

		return true;
	}

	@Override
	public void broadcast(final String type, final JsonObject data) {
		this.broadcasts.add(new Sent(null, type, data));
	}
}
