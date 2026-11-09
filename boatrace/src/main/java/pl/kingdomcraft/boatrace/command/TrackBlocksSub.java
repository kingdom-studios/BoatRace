package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;

/**
 * /bre trackblocks <arena> add|remove [material]   (no material = the block you look at)
 * /bre trackblocks <arena> list|clear
 *
 * Blocks the boat must stay on while racing (off-track detection).
 */
final class TrackBlocksSub extends RaceSubCommand {

	TrackBlocksSub(final SimpleCommandGroup group) {
		super(group, "trackblocks", "Blocks the boat must stay on (off-track detection).", "<arena> <add|remove|list|clear> [material]", 2, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);

		switch (this.args[1].toLowerCase(Locale.ROOT)) {
			case "list" -> {
				if (arena.getTrackBlocks().isEmpty())
					this.info("arena.track-blocks-default", "arena", arena.getName(), "blocks", names(this.plugin().getSettings().defaultTrackBlocks));
				else
					this.info("arena.track-blocks", "arena", arena.getName(), "blocks", names(arena.getTrackBlocks()));
			}
			case "clear" -> {
				arena.getTrackBlocks().clear();
				this.plugin().getArenas().save();

				this.info("arena.track-blocks-cleared", "arena", arena.getName());
			}
			case "add", "remove" -> {
				final Material material = this.material();
				final boolean add = "add".equalsIgnoreCase(this.args[1]);

				if (add)
					arena.getTrackBlocks().add(material);
				else
					arena.getTrackBlocks().remove(material);

				this.plugin().getArenas().save();
				this.info(add ? "arena.track-block-added" : "arena.track-block-removed", "arena", arena.getName(), "block", material.name());
			}
			default -> this.returnInvalidArgs();
		}
	}

	private Material material() {
		if (this.args.length > 2) {
			final Material material = Material.matchMaterial(this.args[2]);

			if (material == null || !material.isBlock())
				this.fail("arena.unknown-block", "value", this.args[2]);

			return material;
		}

		final Player player = this.requirePlayer();
		final Block target = player.getTargetBlockExact(30);

		if (target == null)
			this.fail("arena.no-block-target");

		return target.getType();
	}

	private static String names(final Collection<Material> materials) {
		if (materials.isEmpty())
			return "-";

		final List<String> names = new ArrayList<>();

		for (final Material material : materials)
			names.add(material.name());

		return String.join(", ", names);
	}

	static String describe(final Arena arena, final Collection<Material> defaults) {
		return arena.getTrackBlocks().isEmpty() ? "default (" + names(defaults) + ")" : names(arena.getTrackBlocks());
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length == 1)
			return this.completeLastWord(this.arenaNames());

		if (this.args.length == 2)
			return this.completeLastWord("add", "remove", "list", "clear");

		if (this.args.length == 3) {
			final List<String> blocks = new ArrayList<>();
			final String typed = this.args[2].toUpperCase(Locale.ROOT);

			for (final Material material : Material.values())
				if (material.isBlock() && !material.isLegacy() && material.name().startsWith(typed))
					blocks.add(material.name());

			return blocks.size() > 50 ? blocks.subList(0, 50) : blocks;
		}

		return new ArrayList<>();
	}
}
