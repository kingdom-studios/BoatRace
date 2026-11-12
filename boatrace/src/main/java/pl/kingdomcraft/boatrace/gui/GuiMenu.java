package pl.kingdomcraft.boatrace.gui;

import java.util.List;

/**
 * A menu of guis.yml ({@code menus.<id>}).
 *
 * @param permission          needed to open it yourself (optional)
 * @param requiresCompetitive can only be opened while competitive.enabled is true
 * @param items               in file order: when two items share a slot the later one wins
 * @param autoMaps            how ready arenas without an item of their own are added, null = not at all
 * @param filler              fills the empty slots, null = leave them empty
 */
public record GuiMenu(String id, String title, int rows, int lapsMin, int lapsMax, String permission, boolean requiresCompetitive, List<GuiItem> items,
		AutoMaps autoMaps, GuiItem filler) {

	/**
	 * @param filter   "all" = every ready arena, "competitive" = only arenas with competitive matches
	 * @param template item used for every added arena (its actions apply to that arena)
	 */
	public record AutoMaps(int startSlot, String filter, GuiItem template) {
	}

	public int size() {
		return this.rows * 9;
	}
}
