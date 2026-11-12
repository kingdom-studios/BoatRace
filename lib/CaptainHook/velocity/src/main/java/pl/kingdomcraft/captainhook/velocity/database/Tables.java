package pl.kingdomcraft.captainhook.velocity.database;

/**
 * Tables of the network database. The website reads them, so existing tables and columns never change meaning.
 */
public final class Tables {

	public static final String COINS = "ch_kingdomcoins";
	public static final String COINS_LOG = "ch_kingdomcoins_log";
	public static final String STATS = "ch_player_stats";
	public static final String STATS_TOTAL = "ch_player_stats_total";

	public static final String MINIGAME_STATS = "ch_minigame_stats";
	public static final String MINIGAME_PENDING_JOINS = "ch_minigame_pending_joins";

	public static final String ACHIEVEMENTS = "ch_achievements";
	public static final String SEASON_PASS = "ch_seasonpass";
	public static final String SEASON_PASS_CLAIMS = "ch_seasonpass_claims";
	public static final String REWARD_QUEUE = "ch_reward_queue";

	// Season pass missions
	public static final String MISSION_ASSIGNMENTS = "ch_mission_assignments";
	public static final String MISSION_PROGRESS = "ch_mission_progress";
	public static final String MISSION_DAYS = "ch_mission_days";
	public static final String XP_LEDGER = "ch_xp_ledger";

	// Copies of achievements.yml and seasonpass.yml, written by the proxy so the website can show and claim them
	public static final String ACHIEVEMENT_DEFINITIONS = "ch_achievement_definitions";
	public static final String SEASON_PASS_SEASONS = "ch_seasonpass_seasons";
	public static final String SEASON_PASS_REWARDS = "ch_seasonpass_rewards";

	private Tables() {
	}
}
