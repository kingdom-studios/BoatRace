package pl.kingdomcraft.boatrace.command;

import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.captainhook.CaptainHookHook;
import pl.kingdomcraft.boatrace.storage.RecordStorage;
import pl.kingdomcraft.boatrace.util.TimeFmt;

/**
 * /bre sitecheck - what this server knows about the data the website shows: is CaptainHook linked, how many race stat
 * reports were acknowledged by the proxy / failed / skipped since the start, and when the track table
 * (boatrace_tracks, the map names) was last written. The first thing to run when the website shows nothing new.
 */
final class SiteCheckSub extends RaceSubCommand {

	SiteCheckSub(final SimpleCommandGroup group) {
		super(group, "sitecheck|website", "Check the data this server sends to the website (race stats, map names).", null, 0, true);
	}

	@Override
	protected void onCommand() {
		final CaptainHookHook hook = this.plugin().getCaptainHook();
		final RecordStorage.TracksSync tracks = this.plugin().getStorage().tracksSync();
		final long now = System.currentTimeMillis();

		this.info("sitecheck.header", "server", this.plugin().getSettings().serverName);

		if (hook == null || !hook.status().enabled())
			this.info("sitecheck.off");

		else {
			final CaptainHookHook.Status status = hook.status();

			if (!status.connected())
				this.info("sitecheck.not-found", "game", status.game());

			else {
				this.info("sitecheck.link", "game", status.game(), "state", status.proxyAvailable() ? "&aavailable" : "&cNOT available (nobody online to carry it)");
				this.info("sitecheck.stats", "sent", status.sent(), "acknowledged", status.acknowledged(), "failed", status.failed(), "skipped", status.skipped());
				this.info("sitecheck.last-ack", "when", status.lastAcknowledgedAt() > 0L ? TimeFmt.ago(now - status.lastAcknowledgedAt()) + " ago" : "never");

				if (status.failed() > 0)
					this.info("sitecheck.last-error", "error", status.lastError(), "when", TimeFmt.ago(now - status.lastErrorAt()));

				if (status.skipped() > 0)
					this.info("sitecheck.last-skip", "reason", status.lastSkipReason(), "when", TimeFmt.ago(now - status.lastSkipAt()));
			}
		}

		if (!tracks.error().isEmpty())
			this.info("sitecheck.tracks-error", "error", tracks.error(), "when", TimeFmt.ago(now - tracks.at()));
		else if (tracks.at() == 0L)
			this.info("sitecheck.tracks-pending");
		else
			this.info("sitecheck.tracks", "rows", tracks.rows(), "when", TimeFmt.ago(now - tracks.at()), "server", this.plugin().getSettings().serverName);
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return new String[] { "/{label} sitecheck" };
	}
}
