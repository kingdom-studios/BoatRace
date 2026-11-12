package pl.kingdomcraft.boatrace.gui;

import java.util.List;
import java.util.Map;

import org.bukkit.Material;

/**
 * An item of a menu as written in guis.yml.
 *
 * @param arena               the map this item stands for (placeholders, permission, default target of its actions), "random" =
 *                            the competitive lobby of any map, null = no map
 * @param permission          needed to use the item (optional)
 * @param requiresCompetitive only shown while competitive.enabled is true
 * @param actions             click type (LEFT, RIGHT, SHIFT_LEFT, MIDDLE, ... or {@link #ANY}) -> actions
 */
public record GuiItem(String id, Material material, String name, List<String> lore, List<Integer> slots, String arena, String permission,
		boolean requiresCompetitive, Map<String, List<GuiAction>> actions) {

	/** Actions for every click that has no entry of its own. */
	public static final String ANY = "*";

	public List<GuiAction> actionsFor(final String click) {
		final List<GuiAction> specific = this.actions.get(click);

		if (specific != null)
			return specific;

		return this.actions.getOrDefault(ANY, List.of());
	}

	/** Shows / acts on a competitive lobby (decides which placeholders the lore gets). */
	public boolean isRanked() {
		for (final List<GuiAction> list : this.actions.values())
			for (final GuiAction action : list)
				if (action.isRanked())
					return true;

		return false;
	}

	public GuiItem withMaterial(final Material other) {
		return new GuiItem(this.id, other, this.name, this.lore, this.slots, this.arena, this.permission, this.requiresCompetitive, this.actions);
	}
}
