package pl.kingdomcraft.captainhook.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

import pl.kingdomcraft.captainhook.pass.PassProgress;

/**
 * A player on this server reached a new level of the season pass. Called on the main thread. The XP may have
 * been earned on another server a moment ago: the event fires where the player is now.
 */
public final class PassLevelUpEvent extends PlayerEvent {

	private static final HandlerList handlers = new HandlerList();

	private final PassProgress progress;
	private final int previousLevel;

	public PassLevelUpEvent(final Player player, final PassProgress progress, final int previousLevel) {
		super(player);

		this.progress = progress;
		this.previousLevel = previousLevel;
	}

	/**
	 * @return the progress after the level-up, {@link PassProgress#getLevel()} is the new level
	 */
	public PassProgress getProgress() {
		return this.progress;
	}

	/**
	 * @return the level before; more than one level can be gained at once
	 */
	public int getPreviousLevel() {
		return this.previousLevel;
	}

	@Override
	public HandlerList getHandlers() {
		return handlers;
	}

	public static HandlerList getHandlerList() {
		return handlers;
	}
}
