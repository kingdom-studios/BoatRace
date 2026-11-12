package pl.kingdomcraft.captainhook.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * A player on this server unlocked a network achievement. Called on the main thread, after the unlock and its
 * rewards were saved by the proxy.
 */
public final class AchievementUnlockEvent extends PlayerEvent {

	private static final HandlerList handlers = new HandlerList();

	private final String id;
	private final String name;
	private final String description;

	public AchievementUnlockEvent(final Player player, final String id, final String name, final String description) {
		super(player);

		this.id = id;
		this.name = name;
		this.description = description;
	}

	/**
	 * @return the ID of the achievement in achievements.yml
	 */
	public String getId() {
		return this.id;
	}

	/**
	 * @return the name shown to players
	 */
	public String getName() {
		return this.name;
	}

	public String getDescription() {
		return this.description;
	}

	@Override
	public HandlerList getHandlers() {
		return handlers;
	}

	public static HandlerList getHandlerList() {
		return handlers;
	}
}
