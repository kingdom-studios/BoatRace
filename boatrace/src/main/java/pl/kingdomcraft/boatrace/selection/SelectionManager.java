package pl.kingdomcraft.boatrace.selection;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import net.kyori.adventure.text.format.TextDecoration;
import pl.kingdomcraft.boatrace.arena.Region;
import pl.kingdomcraft.boatrace.util.Msg;

/**
 * WorldEdit-style cuboid selection with a dedicated wand (golden axe, so it never collides with WorldEdit's wooden axe).
 * Left click a block = pos1, right click a block = pos2. /bre pos1 and /bre pos2 use the admin's own position.
 */
public final class SelectionManager implements Listener {

	public static final String PERMISSION = "boatrace.admin";

	private final NamespacedKey wandKey;
	private final Map<UUID, Location[]> selections = new HashMap<>();

	public SelectionManager(final JavaPlugin plugin) {
		this.wandKey = new NamespacedKey(plugin, "selection_wand");
	}

	public ItemStack createWand() {
		final ItemStack item = new ItemStack(Material.GOLDEN_AXE);
		final ItemMeta meta = item.getItemMeta();

		meta.displayName(Msg.c("&6&lBoatRace Wand").decoration(TextDecoration.ITALIC, false));
		meta.lore(List.of(
				Msg.c("&eLeft click &7a block: set &fpos1").decoration(TextDecoration.ITALIC, false),
				Msg.c("&eRight click &7a block: set &fpos2").decoration(TextDecoration.ITALIC, false),
				Msg.c("&7Then: &f/bre setstart|setfinish|addcheckpoint|lights").decoration(TextDecoration.ITALIC, false)));
		meta.getPersistentDataContainer().set(this.wandKey, PersistentDataType.BYTE, (byte) 1);
		item.setItemMeta(meta);

		return item;
	}

	public boolean isWand(final ItemStack item) {
		return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(this.wandKey, PersistentDataType.BYTE);
	}

	/** @param index 0 = pos1, 1 = pos2 */
	public void setPosition(final Player player, final int index, final Location location) {
		final Location[] selection = this.selections.computeIfAbsent(player.getUniqueId(), k -> new Location[2]);

		selection[index] = location.getBlock().getLocation();

		final Location block = selection[index];
		String text = "&aPos" + (index + 1) + " set to &f(" + block.getBlockX() + ", " + block.getBlockY() + ", " + block.getBlockZ() + ")";

		final Region region = this.getRegion(player);

		if (region != null)
			text += " &7| selection " + region.sizeX() + "x" + region.sizeY() + "x" + region.sizeZ() + " (" + region.volume() + " blocks)";

		Msg.send(player, text);
	}

	public Location getPosition(final Player player, final int index) {
		final Location[] selection = this.selections.get(player.getUniqueId());

		return selection == null ? null : selection[index];
	}

	/** @return the cuboid between pos1 and pos2, or null when incomplete / in different worlds */
	public Region getRegion(final Player player) {
		final Location[] selection = this.selections.get(player.getUniqueId());

		if (selection == null || selection[0] == null || selection[1] == null)
			return null;

		if (selection[0].getWorld() != selection[1].getWorld())
			return null;

		return Region.of(selection[0], selection[1]);
	}

	public void clear(final Player player) {
		this.selections.remove(player.getUniqueId());
	}

	// ------------------------------------------------------------------------------------------
	// Wand events
	// ------------------------------------------------------------------------------------------

	@EventHandler(priority = EventPriority.LOWEST)
	public void onInteract(final PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND || !this.isWand(event.getItem()))
			return;

		final Player player = event.getPlayer();
		final Block block = event.getClickedBlock();

		if (!player.hasPermission(PERMISSION) || block == null)
			return;

		if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
			event.setCancelled(true);
			this.setPosition(player, 0, block.getLocation());

		} else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
			event.setCancelled(true);
			this.setPosition(player, 1, block.getLocation());
		}
	}

	/** Creative mode left click would break the block - never with the wand. */
	@EventHandler(priority = EventPriority.LOWEST)
	public void onBreak(final BlockBreakEvent event) {
		if (this.isWand(event.getPlayer().getInventory().getItemInMainHand()))
			event.setCancelled(true);
	}

	@EventHandler
	public void onQuit(final PlayerQuitEvent event) {
		this.clear(event.getPlayer());
	}
}
