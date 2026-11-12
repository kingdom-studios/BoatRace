package pl.kingdomcraft.captainhook;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import pl.kingdomcraft.captainhook.achievement.AchievementProgress;
import pl.kingdomcraft.captainhook.achievement.Achievements;
import pl.kingdomcraft.captainhook.coins.KingdomCoins;
import pl.kingdomcraft.captainhook.minigame.MinigameStat;
import pl.kingdomcraft.captainhook.mission.MissionTracker;
import pl.kingdomcraft.captainhook.minigame.MinigameTopEntry;
import pl.kingdomcraft.captainhook.minigame.Minigames;
import pl.kingdomcraft.captainhook.pass.PassProgress;
import pl.kingdomcraft.captainhook.pass.SeasonPass;
import pl.kingdomcraft.captainhook.proxy.ProxyException;
import pl.kingdomcraft.captainhook.proxy.ProxyLink;

/**
 * The API of Captain Hook for other plugins on this server: season pass, achievements and minigames.
 * KingdomCoins have their own class, {@link KingdomCoins}.
 *
 * Add "depend: [CaptainHook]" (or softdepend) to your plugin.yml.
 *
 * How it works: this server has no database. Every method here becomes a message to the CaptainHook plugin on
 * the Velocity proxy, which does the work and answers. So:
 *
 * - Methods return a {@link CompletableFuture} and never block. They can be called from any thread; the future
 *   always completes on the main thread, so its callbacks may use the Bukkit API directly.
 * - A future fails with {@link ProxyException} when the proxy cannot be reached, does not answer or refuses the
 *   request; {@link ProxyException#getCode()} tells which. Always handle that case: a message needs a player on
 *   this server to travel with, so an empty server cannot reach the proxy.
 * - Methods that add something (XP, achievement progress, minigame stats) do not fail on an empty server: the
 *   change waits up to five minutes for the next player to join and is sent then.
 * - Results arriving later are announced with events: PassLevelUpEvent, AchievementUnlockEvent and
 *   MinigameJoinEvent in the event package.
 *
 * <pre>
 * CaptainHookAPI.addPassXP(player.getUniqueId(), 50).whenComplete((progress, error) -> {
 *     if (error != null)
 *         getLogger().warning("Could not add pass XP: " + error.getMessage());
 * });
 * </pre>
 */
public final class CaptainHookAPI {

	private CaptainHookAPI() {
	}

	/**
	 * @return true if a request to the proxy could be sent right now (somebody on this server can carry it)
	 */
	public static boolean isProxyAvailable() {
		return ProxyLink.isAvailable();
	}

	/* ------------------------------------------------------------------------------- */
	/* Season pass */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Adds season pass XP to a player, online or not. The player is told about new levels wherever they are,
	 * and PassLevelUpEvent is called on that server.
	 *
	 * Fails with ProxyException "no_season" while no season is active.
	 *
	 * @param amount 1 or more
	 * @return the player's progress after the change
	 */
	public static CompletableFuture<PassProgress> addPassXP(final UUID player, final long amount) {
		return SeasonPass.addXp(player, amount);
	}

	/**
	 * Asks the proxy for the season pass progress of any player. Fails with ProxyException "no_season" while no
	 * season is active.
	 */
	public static CompletableFuture<PassProgress> getPassProgress(final UUID player) {
		return SeasonPass.get(player);
	}

	/**
	 * @return the progress of a player on this server from memory, or null if it is not loaded yet or no season
	 *         is active. Safe for scoreboards and placeholders.
	 */
	public static PassProgress getCachedPassProgress(final UUID player) {
		return SeasonPass.getCached(player);
	}

	/**
	 * Counts something a player did for season pass missions, for objectives only a plugin on this server can see:
	 * ROLEPLAY_QUEST_COMPLETE (call it when a quest is finished), or any other objective a server may report
	 * (PLAYTIME_MINUTES, BLOCK_BREAK, MOB_KILL, ITEM_CRAFT, which CaptainHook already counts itself).
	 *
	 * Fire and forget: progress is summed and sent to the proxy within seconds, which decides whether any active mission
	 * wants it. Missions with conditions on this server's name or world see them; give material or entity if the mission has such a filter.
	 *
	 * @param objective the objective name, e.g. "ROLEPLAY_QUEST_COMPLETE"
	 * @param amount    1 or more
	 */
	public static void reportMissionProgress(final UUID player, final String objective, final long amount) {
		final org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(player);

		MissionTracker.report(player, objective, amount, online != null ? online.getWorld().getName() : null, null, null);
	}

	/* ------------------------------------------------------------------------------- */
	/* Achievements */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Adds progress to an achievement of achievements.yml (on the proxy) whose source is "manual". Reaching the
	 * goal unlocks it and grants its rewards; the player is told wherever they are, and AchievementUnlockEvent
	 * is called on that server.
	 *
	 * Fails with ProxyException "unknown_achievement" for an unknown ID and "bad_request" for achievements that
	 * follow a stat (those progress on their own).
	 *
	 * @param amount 1 or more
	 * @return the standing after the change, see {@link AchievementProgress#isNewlyUnlocked()}
	 */
	public static CompletableFuture<AchievementProgress> addAchievementProgress(final UUID player, final String achievementId, final long amount) {
		return Achievements.addProgress(player, achievementId, amount);
	}

	/**
	 * @return the standing of any player in every achievement, in the order of achievements.yml
	 */
	public static CompletableFuture<List<AchievementProgress>> getAchievements(final UUID player) {
		return Achievements.list(player);
	}

	/* ------------------------------------------------------------------------------- */
	/* Minigames */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Adds to a counter stat of a minigame, e.g. addMinigameStat("house_of_terror", uuid, "wins", 1).
	 *
	 * @param game name of the minigame: a-z, 0-9 and _, at most 32 characters. Also its key on the website
	 * @param stat name of the stat, same rules
	 */
	public static CompletableFuture<Void> addMinigameStat(final String game, final UUID player, final String stat, final long amount) {
		return Minigames.recordStats(game, Collections.singletonList(MinigameStat.add(player, stat, amount)));
	}

	/**
	 * Reports several stat changes of a minigame at once, typically at the end of a game: all players, all stats.
	 * Build the changes with {@link MinigameStat#add}, {@link MinigameStat#set}, {@link MinigameStat#max} and
	 * {@link MinigameStat#min}.
	 *
	 * Changes of one message (150 of them) are saved together or not at all. Achievements that follow a stat of
	 * this minigame are updated right after.
	 */
	public static CompletableFuture<Void> recordMinigameStats(final String game, final Collection<MinigameStat> changes) {
		return Minigames.recordStats(game, changes);
	}

	/**
	 * @return all stats of a player in a minigame by name, empty if they never played
	 */
	public static CompletableFuture<Map<String, Long>> getMinigameStats(final String game, final UUID player) {
		return Minigames.getStats(game, player);
	}

	/**
	 * @param limit     number of lines, at most 100
	 * @param ascending true for stats where lower is better (times)
	 * @return the best players of a minigame stat
	 */
	public static CompletableFuture<List<MinigameTopEntry>> getMinigameTop(final String game, final String stat, final int limit, final boolean ascending) {
		return Minigames.getTop(game, stat, limit, ascending);
	}

	/**
	 * Sends a player to the server running a minigame and tells that server what to do with them: once the
	 * player has arrived there, MinigameJoinEvent is called on it (right away if the player is there already).
	 * A join replaces an older one of the same player and is forgotten if the player does not arrive in time.
	 *
	 * Fails with ProxyException "unknown_server" if the proxy has no such server and "player_offline" if the
	 * player is not on the network.
	 *
	 * @param server name of the target server in velocity.toml
	 * @param arena  which arena to join, or null if the minigame should pick one
	 * @param data   anything the minigame plugin needs (at most 4096 characters), or null
	 * @return true if the player is on their way (or already there)
	 */
	public static CompletableFuture<Boolean> queueMinigameJoin(final UUID player, final String game, final String server, final String arena, final String data) {
		return Minigames.queueJoin(player, game, server, arena, data, 0, true);
	}

	/**
	 * Like {@link #queueMinigameJoin(UUID, String, String, String, String)} with more control.
	 *
	 * @param ttlSeconds how long the join waits for the player (at most 600), 0 for the default of the proxy
	 * @param connect    false to only store the join, when another plugin moves the player
	 * @return true if the player is on their way or already there; with connect = false that is only the case
	 *         when they are already there, the join is stored either way
	 */
	public static CompletableFuture<Boolean> queueMinigameJoin(final UUID player, final String game, final String server, final String arena, final String data,
			final int ttlSeconds, final boolean connect) {
		return Minigames.queueJoin(player, game, server, arena, data, ttlSeconds, connect);
	}

	/**
	 * Forgets the pending join of a player, e.g. when they left the queue.
	 *
	 * @return true if there was one
	 */
	public static CompletableFuture<Boolean> cancelMinigameJoin(final UUID player) {
		return Minigames.cancelJoin(player);
	}
}
