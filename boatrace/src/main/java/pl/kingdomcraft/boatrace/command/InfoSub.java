package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.arena.Arena;
import pl.kingdomcraft.boatrace.arena.Region;
import pl.kingdomcraft.boatrace.arena.Zone;
import pl.kingdomcraft.boatrace.util.Msg;

final class InfoSub extends RaceSubCommand {

	InfoSub(final SimpleCommandGroup group) {
		super(group, "info", "Show the setup of an arena.", "<arena>", 1, true);
	}

	@Override
	protected void onCommand() {
		final Arena arena = this.requireArena(0);

		this.info("arena.info",
				"arena", arena.getName(),
				"status", !arena.isReady() ? Msg.text("arena.info-not-ready", "missing", arena.missing())
						: arena.isMaintenance() ? Msg.text("arena.info-maintenance") : Msg.text("arena.info-ready"),
				"spawn", DelSpawnSub.spawnList(arena),
				"start", describe(arena.getStartLine()),
				"finish", arena.getFinishLineRaw() == null ? Msg.text("arena.info-same-as-start") : describe(arena.getFinishLineRaw()),
				"checkpoints", arena.getCheckpoints().size(),
				"lamps", arena.getLights().size(),
				"laps", arena.getLaps(),
				"reset_zones", arena.getResetZones().size(),
				"track_blocks", TrackBlocksSub.describe(arena, this.plugin().getSettings().defaultTrackBlocks),
				"fallback", this.plugin().getSettings().fallbackWorldsOf(arena.getName()).isEmpty() ? "-"
						: String.join(", ", this.plugin().getSettings().fallbackWorldsOf(arena.getName())),
				"reward_mode", arena.getRewardMode().name(),
				"reward_count", arena.getRewardCommands().size());

		this.warnSpawnPlacement(arena);
	}

	private static String describe(final Region region) {
		return region == null ? "-" : region.describe();
	}

	private static String describe(final Zone zone) {
		return zone == null ? "-" : zone.describe();
	}

	@Override
	protected List<String> tabComplete() {
		return this.args.length == 1 ? this.completeLastWord(this.arenaNames()) : new ArrayList<>();
	}
}
