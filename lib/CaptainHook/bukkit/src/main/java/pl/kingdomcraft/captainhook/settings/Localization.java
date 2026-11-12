package pl.kingdomcraft.captainhook.settings;

import java.util.List;

import org.mineacademy.fo.settings.SimpleLocalization;

/**
 * Maps localization/messages_{Locale}.yml.
 */
public final class Localization extends SimpleLocalization {

	@Override
	protected int getConfigVersion() {
		return 1;
	}

	public static final class Link {

		public static String DESCRIPTION;
		public static String STATUS_LINKED;
		public static String STATUS_NOT_LINKED;
		public static String INVALID_FORMAT;
		public static String VERIFYING;
		public static String SUCCESS;
		public static String INVALID_CODE;
		public static String NICKNAME_MISMATCH;
		public static String ALREADY_LINKED;
		public static String RATE_LIMITED;
		public static String CONFIG_ERROR;
		public static String UNAVAILABLE;

		private static void init() {
			setPathPrefix("Link");

			DESCRIPTION = getString("Description");
			STATUS_LINKED = getString("Status_Linked");
			STATUS_NOT_LINKED = getString("Status_Not_Linked");
			INVALID_FORMAT = getString("Invalid_Format");
			VERIFYING = getString("Verifying");
			SUCCESS = getString("Success");
			INVALID_CODE = getString("Invalid_Code");
			NICKNAME_MISMATCH = getString("Nickname_Mismatch");
			ALREADY_LINKED = getString("Already_Linked");
			RATE_LIMITED = getString("Rate_Limited");
			CONFIG_ERROR = getString("Config_Error");
			UNAVAILABLE = getString("Unavailable");
		}
	}

	public static final class Coins {

		public static String DESCRIPTION_BALANCE;
		public static String DESCRIPTION_PAY;
		public static String DESCRIPTION_TOP;
		public static String DESCRIPTION_GIVE;
		public static String DESCRIPTION_TAKE;
		public static String DESCRIPTION_SET;
		public static String DESCRIPTION_HISTORY;
		public static String BALANCE;
		public static String BALANCE_OTHER;
		public static String BALANCE_CHANGED;
		public static String LOADING;
		public static String PLAYER_NOT_FOUND;
		public static String INVALID_AMOUNT;
		public static String INVALID_PAGE;
		public static String NOT_ENOUGH;
		public static String PAY_SELF;
		public static String PAY_SENT;
		public static String PAY_RECEIVED;
		public static String TOP_HEADER;
		public static String TOP_LINE;
		public static String TOP_EMPTY;
		public static String ADMIN_GIVE;
		public static String ADMIN_TAKE;
		public static String ADMIN_SET;
		public static String ADMIN_NOT_ENOUGH;
		public static String HISTORY_HEADER;
		public static String HISTORY_LINE;
		public static String HISTORY_EMPTY;
		public static String DATABASE_ERROR;

		private static void init() {
			setPathPrefix("KingdomCoins");

			DESCRIPTION_BALANCE = getString("Description_Balance");
			DESCRIPTION_PAY = getString("Description_Pay");
			DESCRIPTION_TOP = getString("Description_Top");
			DESCRIPTION_GIVE = getString("Description_Give");
			DESCRIPTION_TAKE = getString("Description_Take");
			DESCRIPTION_SET = getString("Description_Set");
			DESCRIPTION_HISTORY = getString("Description_History");
			BALANCE = getString("Balance");
			BALANCE_OTHER = getString("Balance_Other");
			BALANCE_CHANGED = getString("Balance_Changed");
			LOADING = getString("Loading");
			PLAYER_NOT_FOUND = getString("Player_Not_Found");
			INVALID_AMOUNT = getString("Invalid_Amount");
			INVALID_PAGE = getString("Invalid_Page");
			NOT_ENOUGH = getString("Not_Enough");
			PAY_SELF = getString("Pay_Self");
			PAY_SENT = getString("Pay_Sent");
			PAY_RECEIVED = getString("Pay_Received");
			TOP_HEADER = getString("Top_Header");
			TOP_LINE = getString("Top_Line");
			TOP_EMPTY = getString("Top_Empty");
			ADMIN_GIVE = getString("Admin_Give");
			ADMIN_TAKE = getString("Admin_Take");
			ADMIN_SET = getString("Admin_Set");
			ADMIN_NOT_ENOUGH = getString("Admin_Not_Enough");
			HISTORY_HEADER = getString("History_Header");
			HISTORY_LINE = getString("History_Line");
			HISTORY_EMPTY = getString("History_Empty");
			DATABASE_ERROR = getString("Database_Error");
		}
	}

	public static final class Pass {

		public static String LEVEL_UP;
		public static String LEVEL_UP_TITLE;
		public static String LEVEL_UP_SUBTITLE;
		public static String LEVEL_UP_LINK;
		public static String LEVEL_UP_HOVER;

		private static void init() {
			setPathPrefix("Pass");

			LEVEL_UP = getString("Level_Up");
			LEVEL_UP_TITLE = getString("Level_Up_Title");
			LEVEL_UP_SUBTITLE = getString("Level_Up_Subtitle");
			LEVEL_UP_LINK = getString("Level_Up_Link");
			LEVEL_UP_HOVER = getString("Level_Up_Hover");
		}
	}

	public static final class Mission {

		public static String COMPLETED;
		public static String COMPLETED_NO_XP;
		public static String DESCRIPTION;
		public static String NO_SEASON;
		public static String UNAVAILABLE;
		public static String STATUS;
		public static String STATUS_MAX;
		public static String HEADER_DAILY;
		public static String HEADER_WEEKLY;
		public static String HEADER_SEASON;
		public static String LINE;
		public static String LINE_DONE;
		public static String EMPTY;
		public static String CLAIM_HINT;
		public static String ADMIN_USAGE;
		public static String ADMIN_DONE;
		public static String ADMIN_FAILED;

		private static void init() {
			setPathPrefix("Mission");

			COMPLETED = getString("Completed");
			COMPLETED_NO_XP = getString("Completed_No_Xp");
			DESCRIPTION = getString("Description");
			NO_SEASON = getString("No_Season");
			UNAVAILABLE = getString("Unavailable");
			STATUS = getString("Status");
			STATUS_MAX = getString("Status_Max");
			HEADER_DAILY = getString("Header_Daily");
			HEADER_WEEKLY = getString("Header_Weekly");
			HEADER_SEASON = getString("Header_Season");
			LINE = getString("Line");
			LINE_DONE = getString("Line_Done");
			EMPTY = getString("Empty");
			CLAIM_HINT = getString("Claim_Hint");
			ADMIN_USAGE = getString("Admin_Usage");
			ADMIN_DONE = getString("Admin_Done");
			ADMIN_FAILED = getString("Admin_Failed");
		}
	}

	public static final class Achievement {

		public static String UNLOCKED;

		private static void init() {
			setPathPrefix("Achievement");

			UNLOCKED = getString("Unlocked");
		}
	}

	public static final class Reward {

		public static String RECEIVED;
		public static String RECEIVED_PREFIX;

		private static void init() {
			setPathPrefix("Reward");

			RECEIVED = getString("Received");
			RECEIVED_PREFIX = getString("Received_Prefix");
		}
	}

	public static final class Admin {

		public static List<String> STATUS;
		public static String DESCRIPTION_STATUS;
		public static String PROXY_CONNECTED;
		public static String PROXY_NO_CARRIER;
		public static String PROXY_ERROR;

		private static void init() {
			setPathPrefix("Admin");

			STATUS = getStringList("Status_Proxy");
			DESCRIPTION_STATUS = getString("Description_Status");
			PROXY_CONNECTED = getString("Proxy_Connected");
			PROXY_NO_CARRIER = getString("Proxy_No_Carrier");
			PROXY_ERROR = getString("Proxy_Error");
		}
	}
}
