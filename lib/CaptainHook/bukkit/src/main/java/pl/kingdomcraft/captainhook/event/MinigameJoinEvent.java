package pl.kingdomcraft.captainhook.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A player is on this server to join a minigame: another server queued the join with
 * CaptainHookAPI.queueMinigameJoin and the proxy sent the player here. Called on the main thread, a moment after
 * the player joined (or right away if they were here already).
 *
 * The minigame plugin that runs {@link #getGame()} should put the player into the game. Each join is delivered once.
 */
public final class MinigameJoinEvent extends PlayerEvent {

	private static final HandlerList handlers = new HandlerList();

	private final String game;
	private final String arena;
	private final String data;

	public MinigameJoinEvent(final Player player, final String game, final String arena, final String data) {
		super(player);

		this.game = game;
		this.arena = arena;
		this.data = data;
	}

	/**
	 * @return the minigame the join was queued for, e.g. "house_of_terror"
	 */
	public String getGame() {
		return this.game;
	}

	/**
	 * @return the arena to join, or null if the minigame should pick one
	 */
	public String getArena() {
		return this.arena;
	}

	/**
	 * @return whatever the queueing plugin attached (e.g. JSON with the party), or null
	 */
	public String getData() {
		return this.data;
	}

	@Override
	public HandlerList getHandlers() {
		return handlers;
	}

	public static HandlerList getHandlerList() {
		return handlers;
	}
}
