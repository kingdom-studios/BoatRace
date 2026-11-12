package pl.kingdomcraft.captainhook.velocity.mission;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.velocity.ServiceException;
import pl.kingdomcraft.captainhook.velocity.coins.KingdomCoins;

/**
 * The admin actions on season pass XP and missions, shared by /captainhookproxy and by /karnet admin on the Paper
 * servers (mission.admin). Players are given by name and resolved through their KingdomCoins account, so they can be offline.
 */
public final class MissionAdmin {

	private final Missions missions;
	private final KingdomCoins coins;
	private final Reloader reloader;

	/**
	 * Re-reads the configuration files; its result is shown to whoever asked.
	 */
	@FunctionalInterface
	public interface Reloader {
		String reload() throws Exception;
	}

	public MissionAdmin(final Missions missions, final KingdomCoins coins, final Reloader reloader) {
		this.missions = missions;
		this.coins = coins;
		this.reloader = reloader;
	}

	/**
	 * @param action xp_add, xp_set, complete, progress, rotate or reload
	 * @param name   the player (not for rotate)
	 * @param arg    xp_*: the amount; complete: the mission key; progress: "OBJECTIVE" ; rotate: daily | weekly
	 * @param amount progress: how much
	 * @return {"message": "..."} for whoever ran the command
	 */
	public CompletableFuture<JsonObject> run(final String action, final String name, final String arg, final long amount) {
		switch (action) {
			case "reload":
				try {
					return CompletableFuture.completedFuture(message("Reloaded: " + this.reloader.reload()));

				} catch (final Exception ex) {
					throw new ServiceException(Protocol.ERROR_BAD_REQUEST, "Nothing was changed: " + ex.getMessage());
				}

			case "rotate": {
				final MissionPeriodType type = MissionPeriodType.find(arg == null ? "" : arg);

				if (type == null)
					throw new IllegalArgumentException("Choose daily or weekly");

				return this.missions.reroll(type).thenApply(ignored -> message("New " + type.key() + " missions drawn: " + this.missions.getActive().stream()
						.filter(assignment -> assignment.type() == type).map(Assignment::missionKey).toList()));
			}

			case "xp_add":
			case "xp_set":
				return this.resolve(name).thenCompose(uuid -> this.missions.adminXp(uuid, Long.parseLong(arg), action.equals("xp_set")))
						.thenApply(change -> message(name + " now has " + change.state().xp() + " XP (level " + change.level() + ")"));

			case "complete":
				return this.resolve(name).thenCompose(uuid -> this.missions.complete(uuid, arg)).thenApply(assignment -> message("Completed \"" + assignment.titleEn() + "\" for " + name));

			case "progress": {
				final Objective objective = Objective.find(arg == null ? "" : arg);

				if (objective == null)
					throw new IllegalArgumentException("Unknown objective " + arg);

				return this.resolve(name).thenCompose(uuid -> {
					this.missions.record(uuid, objective, new MissionFilter.Event(null, null, null, null, null, null), amount);

					return this.missions.flush();

				}).thenApply(ignored -> message("Counted " + amount + " x " + objective.name().toLowerCase(Locale.ROOT) + " for " + name + " (only missions without server, world, material or entity conditions count)"));
			}

			default:
				throw new IllegalArgumentException("Unknown action " + action);
		}
	}

	private CompletableFuture<UUID> resolve(final String name) {
		if (name == null || name.isBlank() || name.length() > 16)
			throw new IllegalArgumentException("A player name is required");

		return this.coins.find(name).thenApply(account -> {
			if (account == null)
				throw new ServiceException(Protocol.ERROR_BAD_REQUEST, "No player named " + name + " has joined the network");

			return account.uuid();
		});
	}

	private static JsonObject message(final String text) {
		final JsonObject json = new JsonObject();

		json.addProperty("message", text);
		return json;
	}
}
