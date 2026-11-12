package pl.kingdomcraft.captainhook.protocol;

/**
 * Constants of the plugin messaging protocol between the Velocity module (master) and the Bukkit module (client),
 * documented in docs/NETWORK_PROTOCOL.md.
 *
 * This package exists twice, byte for byte: in the bukkit and in the velocity module. ProtocolParityTest of the
 * velocity module fails the build when the two copies differ, so always change both (and the document).
 */
public final class Protocol {

	public static final String CHANNEL_NAMESPACE = "captainhook";
	public static final String CHANNEL_NAME = "main";
	public static final String CHANNEL = CHANNEL_NAMESPACE + ":" + CHANNEL_NAME;

	/**
	 * Raised on every change old receivers cannot ignore. Adding message types or optional fields does not count.
	 */
	public static final int VERSION = 1;

	/**
	 * A backend server accepts at most 32767 bytes per plugin message from the proxy, and old Bukkit versions
	 * refuse to send more than 32766, so both directions stay below that.
	 */
	public static final int MAX_PAYLOAD_BYTES = 32766;

	/**
	 * Players per {@link #STATS_BATCH}, small enough to stay below {@link #MAX_PAYLOAD_BYTES}.
	 */
	public static final int STATS_BATCH_SIZE = 50;

	/* ------------------------------------------------------------------------------- */
	/* Message types */
	/* ------------------------------------------------------------------------------- */

	/**
	 * Answer to a request, in either direction.
	 */
	public static final String REPLY = "reply";

	// Requests, Bukkit to Velocity
	public static final String STATUS = "status";
	public static final String PLAYER_READY = "player.ready";
	public static final String STATS_BATCH = "stats.batch";
	public static final String COINS_FIND = "coins.find";
	public static final String COINS_HISTORY = "coins.history";
	public static final String COINS_CHANGE = "coins.change";
	public static final String COINS_SET = "coins.set";
	public static final String COINS_PAY = "coins.pay";
	public static final String PASS_ADD_XP = "pass.add_xp";
	public static final String PASS_GET = "pass.get";
	public static final String ACHIEVEMENT_PROGRESS = "achievement.progress";
	public static final String ACHIEVEMENT_LIST = "achievement.list";
	public static final String MINIGAME_STATS = "minigame.stats";
	public static final String MINIGAME_STATS_GET = "minigame.stats.get";
	public static final String MINIGAME_TOP = "minigame.top";
	public static final String MINIGAME_JOIN_QUEUE = "minigame.join.queue";
	public static final String MINIGAME_JOIN_CANCEL = "minigame.join.cancel";
	public static final String MISSION_PROGRESS = "mission.progress";
	public static final String MISSION_LIST = "mission.list";
	public static final String MISSION_ADMIN = "mission.admin";

	// Notifications, Velocity to Bukkit
	public static final String COINS_BALANCE = "coins.balance";
	public static final String COINS_TOP = "coins.top";
	public static final String PASS_UPDATE = "pass.update";
	public static final String ACHIEVEMENT_UNLOCKED = "achievement.unlocked";
	public static final String MINIGAME_JOIN = "minigame.join";
	public static final String REWARD_DISPATCH = "reward.dispatch";
	public static final String MISSION_COMPLETED = "mission.completed";

	// Notifications, Bukkit to Velocity
	public static final String REWARD_ACK = "reward.ack";

	/**
	 * Limits of one reward.dispatch, checked by both sides.
	 */
	public static final int MAX_REWARD_COMMANDS = 20;
	public static final int MAX_REWARD_COMMAND_LENGTH = 256;

	/* ------------------------------------------------------------------------------- */
	/* Error codes of failed replies */
	/* ------------------------------------------------------------------------------- */

	public static final String ERROR_UNSUPPORTED_VERSION = "unsupported_version";
	public static final String ERROR_UNKNOWN_TYPE = "unknown_type";
	public static final String ERROR_BAD_REQUEST = "bad_request";
	public static final String ERROR_DATABASE = "database_error";
	public static final String ERROR_TOO_LARGE = "too_large";
	public static final String ERROR_NO_SEASON = "no_season";
	public static final String ERROR_UNKNOWN_ACHIEVEMENT = "unknown_achievement";
	public static final String ERROR_UNKNOWN_SERVER = "unknown_server";
	public static final String ERROR_PLAYER_OFFLINE = "player_offline";

	private Protocol() {
	}
}
