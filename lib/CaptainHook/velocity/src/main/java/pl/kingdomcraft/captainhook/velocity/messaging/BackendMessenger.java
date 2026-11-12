package pl.kingdomcraft.captainhook.velocity.messaging;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.google.gson.JsonObject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import pl.kingdomcraft.captainhook.protocol.Message;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.protocol.ProtocolException;
import pl.kingdomcraft.captainhook.velocity.Network;

/**
 * The proxy end of the captainhook:main plugin messaging channel, see docs/NETWORK_PROTOCOL.md.
 *
 * Plugin messages travel inside a player's connection, so a Paper server can only be reached while at least one
 * player is on it. That is enough here: everything sent to a server concerns the players on it.
 *
 * Security: the channel is never forwarded. A message on it that comes from a player (a modified client) is
 * dropped, and messages from the servers never reach the clients, so only the proxy and the Paper servers
 * behind it can speak this protocol.
 */
public final class BackendMessenger implements Network {

	public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create(Protocol.CHANNEL_NAMESPACE, Protocol.CHANNEL_NAME);

	private final ProxyServer proxy;
	private final Logger logger;

	/**
	 * Velocity name of the server each player is on. Tracked here instead of asking Velocity, because the
	 * server a player just left must still be known in the disconnect event.
	 */
	private final Map<UUID, String> locations = new ConcurrentHashMap<>();
	private final Set<UUID> online = ConcurrentHashMap.newKeySet();

	/**
	 * Velocity server name to the ID the Paper server calls itself (api.server-id), learned from its messages.
	 */
	private final Map<String, String> serverIds = new ConcurrentHashMap<>();
	private final Set<String> warnedVersions = ConcurrentHashMap.newKeySet();

	private volatile RequestRouter router;

	public BackendMessenger(final ProxyServer proxy, final Logger logger) {
		this.proxy = proxy;
		this.logger = logger;
	}

	public void start(final RequestRouter router) {
		this.router = router;
		this.proxy.getChannelRegistrar().register(CHANNEL);
	}

	public void stop() {
		this.proxy.getChannelRegistrar().unregister(CHANNEL);
		this.router = null;
	}

	/* ------------------------------------------------------------------------------- */
	/* Who is where */
	/* ------------------------------------------------------------------------------- */

	public void playerJoined(final UUID uuid) {
		this.online.add(uuid);
	}

	/**
	 * @return the Velocity name of the server the player was on before, or null
	 */
	public String playerConnected(final UUID uuid, final String server) {
		return this.locations.put(uuid, server);
	}

	/**
	 * @return the Velocity name of the server the player was on, or null
	 */
	public String playerQuit(final UUID uuid) {
		this.online.remove(uuid);

		return this.locations.remove(uuid);
	}

	/**
	 * @return the ID the Paper server uses for itself in the stats tables. Until the server said so in a
	 *         message, the Velocity name is assumed (keep the two equal to be safe).
	 */
	public String getServerId(final String velocityServer) {
		return this.serverIds.getOrDefault(velocityServer, velocityServer);
	}

	public Map<String, String> getServerIds() {
		return Collections.unmodifiableMap(this.serverIds);
	}

	@Override
	public Collection<UUID> onlinePlayers() {
		return Collections.unmodifiableSet(this.online);
	}

	@Override
	public String currentServer(final UUID player) {
		return this.locations.get(player);
	}

	@Override
	public boolean serverExists(final String server) {
		return this.proxy.getServer(server).isPresent();
	}

	@Override
	public boolean connect(final UUID player, final String server) {
		final Optional<Player> target = this.proxy.getPlayer(player);
		final Optional<RegisteredServer> destination = this.proxy.getServer(server);

		if (target.isEmpty() || destination.isEmpty())
			return false;

		target.get().createConnectionRequest(destination.get()).fireAndForget();
		return true;
	}

	/* ------------------------------------------------------------------------------- */
	/* Sending */
	/* ------------------------------------------------------------------------------- */

	@Override
	public boolean notify(final UUID player, final String type, final JsonObject data) {
		final Optional<ServerConnection> connection = this.proxy.getPlayer(player).flatMap(Player::getCurrentServer);

		return connection.isPresent() && this.send(connection.get(), Message.notification(type, null, data));
	}

	@Override
	public void broadcast(final String type, final JsonObject data) {
		final byte[] bytes = Message.notification(type, null, data).encode();

		if (bytes.length > Protocol.MAX_PAYLOAD_BYTES) {
			this.logger.warn("Not broadcasting {}: {} bytes is over the plugin message limit.", type, bytes.length);

			return;
		}

		// Carried by any player on the server, does nothing for empty servers
		for (final RegisteredServer server : this.proxy.getAllServers())
			server.sendPluginMessage(CHANNEL, bytes);
	}

	/**
	 * @return false if the message is too large or the connection is gone
	 */
	boolean send(final ServerConnection connection, final Message message) {
		byte[] bytes = message.encode();

		if (bytes.length > Protocol.MAX_PAYLOAD_BYTES) {
			this.logger.warn("Not sending {} to {}: {} bytes is over the plugin message limit.", message, connection.getServerInfo().getName(), bytes.length);

			if (!message.isReply())
				return false;

			// The other side is waiting for this reply, tell it why none is coming
			bytes = Message.error(message.getReplyTo(), Protocol.ERROR_TOO_LARGE, "The reply was " + bytes.length + " bytes").encode();
		}

		// The player carrying the request may be gone by the time the reply is ready, anyone on that server will do
		return connection.sendPluginMessage(CHANNEL, bytes) || connection.getServer().sendPluginMessage(CHANNEL, bytes);
	}

	/* ------------------------------------------------------------------------------- */
	/* Receiving */
	/* ------------------------------------------------------------------------------- */

	@Subscribe
	public void onPluginMessage(final PluginMessageEvent event) {
		if (!CHANNEL.getId().equals(event.getIdentifier().getId()))
			return;

		// Ours alone: never forwarded to the client or, coming from a client, to the server
		event.setResult(PluginMessageEvent.ForwardResult.handled());

		if (!(event.getSource() instanceof ServerConnection))
			return;

		final ServerConnection connection = (ServerConnection) event.getSource();
		final String velocityServer = connection.getServerInfo().getName();
		final Message message;

		try {
			message = Message.decode(event.getData());

		} catch (final ProtocolException ex) {
			this.logger.warn("Ignoring a malformed message from {}: {}", velocityServer, ex.getMessage());

			return;
		}

		if (message.getVersion() != Protocol.VERSION) {
			if (this.warnedVersions.add(velocityServer + ":" + message.getVersion()))
				this.logger.warn("Server {} speaks protocol version {}, the proxy speaks {}. Install the same CaptainHook version on both.",
						velocityServer, message.getVersion(), Protocol.VERSION);

			if (message.isRequest())
				this.send(connection, Message.error(message.getId(), Protocol.ERROR_UNSUPPORTED_VERSION, "The proxy speaks protocol version " + Protocol.VERSION));

			return;
		}

		if (message.getServer() != null && !message.getServer().isBlank() && message.getServer().length() <= 64)
			this.serverIds.put(velocityServer, message.getServer());

		final RequestRouter router = this.router;

		// The proxy sends no requests yet, so there is no reply to expect either
		if (router != null && !message.isReply())
			router.handle(connection, velocityServer, this.getServerId(velocityServer), message);
	}
}
