package pl.kingdomcraft.captainhook.velocity;

import java.lang.reflect.Method;
import java.util.UUID;

import org.slf4j.Logger;

import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.proxy.ProxyServer;

import pl.kingdomcraft.captainhook.velocity.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.velocity.mission.MissionFilter;
import pl.kingdomcraft.captainhook.velocity.mission.Missions;
import pl.kingdomcraft.captainhook.velocity.mission.Objective;

/**
 * Counts NuVotifier votes for the VOTE mission objective. NuVotifier is an optional plugin, so its event is looked up
 * by name when it is installed instead of being compiled against: no NuVotifier, no listener, no error.
 */
public final class VoteListener {

	public static final String EVENT_CLASS = "com.vexsoftware.votifier.velocity.event.VotifierEvent";

	private final ProxyServer proxy;
	private final Missions missions;
	private final KingdomCoins coins;
	private final Logger logger;

	public VoteListener(final ProxyServer proxy, final Missions missions, final KingdomCoins coins, final Logger logger) {
		this.proxy = proxy;
		this.missions = missions;
		this.coins = coins;
		this.logger = logger;
	}

	/**
	 * @return true if NuVotifier's event was found and the listener registered
	 */
	@SuppressWarnings({ "unchecked", "rawtypes" })
	public boolean register(final Object plugin) {
		return this.register(plugin, EVENT_CLASS);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	boolean register(final Object plugin, final String eventClassName) {
		final Class<?> eventClass;

		try {
			eventClass = Class.forName(eventClassName, false, this.getClass().getClassLoader());

		} catch (final ClassNotFoundException | LinkageError ex) {
			return false;
		}

		this.proxy.getEventManager().register(plugin, (Class) eventClass, (EventHandler) event -> this.onVote(event));
		return true;
	}

	/**
	 * @param event a VotifierEvent: getVote().getUsername() is the voter
	 */
	void onVote(final Object event) {
		final String username;

		try {
			final Object vote = event.getClass().getMethod("getVote").invoke(event);
			final Method getUsername = vote.getClass().getMethod("getUsername");

			username = String.valueOf(getUsername.invoke(vote));

		} catch (final ReflectiveOperationException | RuntimeException ex) {
			this.logger.warn("Could not read a NuVotifier vote: {}", ex.toString());
			return;
		}

		if (username.isBlank() || username.length() > 16)
			return;

		// Online players first (no database), otherwise the KingdomCoins account of the name
		final UUID online = this.proxy.getPlayer(username).map(player -> player.getUniqueId()).orElse(null);

		if (online != null) {
			this.missions.record(online, Objective.VOTE, MissionFilter.Event.ofServer(null), 1);
			return;
		}

		this.coins.find(username).whenComplete((account, error) -> {
			if (error == null && account != null)
				this.missions.record(account.uuid(), Objective.VOTE, MissionFilter.Event.ofServer(null), 1);
		});
	}
}
