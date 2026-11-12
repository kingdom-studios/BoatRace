package pl.kingdomcraft.captainhook.stats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.mineacademy.fo.annotation.AutoRegister;

import pl.kingdomcraft.captainhook.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.pass.SeasonPass;

/**
 * Marks players whose stats changed ("dirty") so that {@link StatsSync} only sends those, and counts placed blocks.
 *
 * Kills, mob kills, deaths and playtime are read from vanilla statistics in O(1). Vanilla has no single "blocks
 * placed" statistic, so the sum of USE_ITEM over all block items is read once on join and incremented in memory.
 */
@AutoRegister
public final class StatsTracker implements Listener {

	private static final Set<UUID> dirty = ConcurrentHashMap.newKeySet();

	/**
	 * Main thread only.
	 */
	private static final Map<UUID, Long> blocksPlaced = new HashMap<>();

	private static Material[] placeableBlocks;

	public static void markDirty(final UUID uuid) {
		dirty.add(uuid);
	}

	/**
	 * @return players changed since the last call
	 */
	static List<UUID> drainDirty() {
		final List<UUID> drained = new ArrayList<>(dirty);

		dirty.removeAll(drained);
		return drained;
	}

	static void clearDirty() {
		dirty.clear();
	}

	static long getBlocksPlaced(final Player player) {
		return blocksPlaced.computeIfAbsent(player.getUniqueId(), uuid -> countPlacedBlocks(player));
	}

	private static long countPlacedBlocks(final Player player) {
		if (placeableBlocks == null)
			placeableBlocks = findPlaceableBlocks();

		long total = 0;

		for (final Material material : placeableBlocks)
			total += player.getStatistic(Statistic.USE_ITEM, material);

		return total;
	}

	@SuppressWarnings("deprecation")
	private static Material[] findPlaceableBlocks() {
		final List<Material> materials = new ArrayList<>();

		for (final Material material : Material.values())
			if (!material.isLegacy() && material.isBlock() && material.isItem() && !material.isAir())
				materials.add(material);

		return materials.toArray(new Material[0]);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onJoin(final PlayerJoinEvent event) {
		final Player player = event.getPlayer();

		getBlocksPlaced(player); // spread the one-off counting cost over joins instead of the first full sync
		markDirty(player.getUniqueId()); // sent to the proxy within a few seconds
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(final PlayerQuitEvent event) {
		final Player player = event.getPlayer();
		final UUID uuid = player.getUniqueId();

		StatsSync.queue(player, false);

		blocksPlaced.remove(uuid);
		dirty.remove(uuid);
		KingdomCoins.unload(uuid);
		SeasonPass.unload(uuid);
	}

	@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
	public void onBlockPlace(final BlockPlaceEvent event) {
		final UUID uuid = event.getPlayer().getUniqueId();

		if (blocksPlaced.computeIfPresent(uuid, (key, count) -> count + 1) != null)
			dirty.add(uuid);
	}

	/**
	 * Covers player deaths (victim), player kills and mob kills (killer).
	 */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onEntityDeath(final EntityDeathEvent event) {
		final Player killer = event.getEntity().getKiller();

		if (event.getEntity() instanceof Player)
			dirty.add(event.getEntity().getUniqueId());

		if (killer != null)
			dirty.add(killer.getUniqueId());
	}
}
