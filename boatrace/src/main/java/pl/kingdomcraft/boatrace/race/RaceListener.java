package pl.kingdomcraft.boatrace.race;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.EquipmentSlot;

/** Bukkit events of the race. Plain {@link Listener}, registered by BoatRacePlugin. */
public final class RaceListener implements Listener {

	private final RaceManager races;

	public RaceListener(final RaceManager races) {
		this.races = races;
	}

	// ------------------------------------------------------------------------------------------
	// Boat
	// ------------------------------------------------------------------------------------------

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onVehicleMove(final VehicleMoveEvent event) {
		this.races.handleVehicleMove(event.getVehicle(), event.getFrom(), event.getTo());
	}

	/** Leaving the boat = boat is removed and the player is reset to the start. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onVehicleExit(final VehicleExitEvent event) {
		this.races.handleVehicleExit(event.getVehicle(), event.getExited());
	}

	/** Nobody but the owner may sit in a race boat. */
	@EventHandler(priority = EventPriority.HIGH)
	public void onVehicleEnter(final VehicleEnterEvent event) {
		if (this.races.isRaceBoat(event.getVehicle()) && !this.races.isBoatOwner(event.getVehicle(), event.getEntered()))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onVehicleDamage(final VehicleDamageEvent event) {
		if (this.races.isRaceBoat(event.getVehicle()))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onVehicleDestroy(final VehicleDestroyEvent event) {
		if (this.races.isRaceBoat(event.getVehicle()))
			event.setCancelled(true);
	}

	// ------------------------------------------------------------------------------------------
	// Racer safety: cannot die, hunger, inventory
	// ------------------------------------------------------------------------------------------

	@EventHandler(priority = EventPriority.HIGHEST)
	public void onDamage(final EntityDamageEvent event) {
		if (event.getEntity() instanceof Player player && this.races.isRacing(player)) {
			event.setCancelled(true);
			player.setFireTicks(0);
		}
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onFood(final FoodLevelChangeEvent event) {
		if (event.getEntity() instanceof Player player && this.races.isRacing(player))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onInteract(final PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND || event.getAction() == Action.PHYSICAL)
			return;

		if (this.races.handleButton(event.getPlayer(), event.getItem()))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onInventoryClick(final InventoryClickEvent event) {
		if (event.getWhoClicked() instanceof Player player && this.races.isRacing(player))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onInventoryDrag(final InventoryDragEvent event) {
		if (event.getWhoClicked() instanceof Player player && this.races.isRacing(player))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onDrop(final PlayerDropItemEvent event) {
		if (this.races.isRacing(event.getPlayer()))
			event.setCancelled(true);
	}

	@EventHandler(priority = EventPriority.HIGH)
	public void onSwapHands(final PlayerSwapHandItemsEvent event) {
		if (this.races.isRacing(event.getPlayer()))
			event.setCancelled(true);
	}

	// ------------------------------------------------------------------------------------------
	// Player lifecycle
	// ------------------------------------------------------------------------------------------

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(final PlayerQuitEvent event) {
		this.races.handleQuit(event.getPlayer());
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(final PlayerJoinEvent event) {
		this.races.handleJoin(event.getPlayer());
	}

	/** /spawn, /home etc. from another plugin while racing = the player has left the race. */
	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onTeleport(final PlayerTeleportEvent event) {
		final PlayerTeleportEvent.TeleportCause cause = event.getCause();

		if (cause != PlayerTeleportEvent.TeleportCause.COMMAND && cause != PlayerTeleportEvent.TeleportCause.PLUGIN)
			return;

		if (!this.races.isRacing(event.getPlayer()) || this.races.isInternalTeleport(event.getPlayer()))
			return;

		this.races.handleForeignTeleport(event.getPlayer());
	}
}
