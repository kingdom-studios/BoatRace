package pl.kingdomcraft.boatrace.captainhook;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import pl.kingdomcraft.captainhook.CaptainHookAPI;
import pl.kingdomcraft.captainhook.minigame.MinigameStat;
import pl.kingdomcraft.captainhook.proxy.ProxyException;

/**
 * The ONLY class that refers to CaptainHook's own types. {@link CaptainHookHook} creates it only when the CaptainHook plugin
 * is enabled, so without CaptainHook it is never loaded and no NoClassDefFoundError can happen. Its methods take and return
 * BoatRace / JDK types only.
 * <p>
 * CaptainHook's client sends every call to the proxy without blocking and completes the futures on the main thread.
 */
final class CaptainHookGateway {

	CaptainHookGateway() {
		// Touch the API now, so an incompatible CaptainHook version fails here (reported once) instead of mid-race.
		CaptainHookAPI.isProxyAvailable();
	}

	CompletableFuture<Void> record(final String game, final List<StatChange> changes) {
		final List<MinigameStat> stats = new ArrayList<>(changes.size());

		for (final StatChange change : changes)
			stats.add(switch (change.op()) {
				case ADD -> MinigameStat.add(change.player(), change.stat(), change.value());
				case MIN -> MinigameStat.min(change.player(), change.stat(), change.value());
				case MAX -> MinigameStat.max(change.player(), change.stat(), change.value());
				case SET -> MinigameStat.set(change.player(), change.stat(), change.value());
			});

		return CaptainHookAPI.recordMinigameStats(game, stats);
	}

	CompletableFuture<?> addPassXp(final UUID player, final long amount) {
		return CaptainHookAPI.addPassXP(player, amount);
	}

	/** @return CaptainHook's error code (no_carrier, timeout, ...), or null if the failure is not one of CaptainHook's */
	String errorCode(final Throwable cause) {
		return cause instanceof final ProxyException proxy ? proxy.getCode() : null;
	}
}
