package pl.kingdomcraft.captainhook.command;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.bukkit.entity.Player;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.command.SimpleCommand;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.api.ApiClient;
import pl.kingdomcraft.captainhook.api.ApiResponse;
import pl.kingdomcraft.captainhook.settings.Localization.Link;

/**
 * /link         - shows the link status (GET /link/{uuid}, cached for 5 minutes)
 * /link <code>  - links the account using a 6-digit code from the website (POST /link/verify)
 */
@AutoRegister
public final class LinkCommand extends SimpleCommand {

	private static final Pattern CODE_PATTERN = Pattern.compile("[0-9]{6}");
	private static final long STATUS_CACHE_MILLIS = TimeUnit.MINUTES.toMillis(5);

	/**
	 * Results of GET /link/{uuid} and successful links, per contract cacheable for 5 minutes.
	 */
	private final Map<UUID, CachedStatus> statusCache = new ConcurrentHashMap<>();

	public LinkCommand() {
		super("link");

		setUsage("[kod]");
		setDescription(Link.DESCRIPTION);
		setPermission(null);
		setCooldown(5, TimeUnit.SECONDS);
	}

	@Override
	protected void onCommand() {
		checkConsole();

		if (args.length == 0)
			this.showStatus(getPlayer());

		else {
			checkBoolean(CODE_PATTERN.matcher(args[0]).matches(), Link.INVALID_FORMAT);

			this.verify(getPlayer(), args[0]);
		}
	}

	private void verify(final Player player, final String code) {
		final JsonObject body = new JsonObject();

		body.addProperty("code", code);
		body.addProperty("uuid", player.getUniqueId().toString());
		body.addProperty("nickname", player.getName());

		tell(Link.VERIFYING);

		Common.runAsync(() -> {
			final ApiResponse response = ApiClient.post("/link/verify", body);

			if (response.isSuccess())
				this.cacheStatus(player.getUniqueId(), response.getDataString("username"));

			Common.runLater(() -> tellIfOnline(player, this.getVerifyMessage(response)));
		});
	}

	private String getVerifyMessage(final ApiResponse response) {
		if (response.isSuccess())
			return Link.SUCCESS.replace("{username}", response.getDataString("username"));

		switch (response.getErrorCode()) {
			case "invalid_code":
				return Link.INVALID_CODE;
			case "nickname_mismatch":
				return Link.NICKNAME_MISMATCH;
			case "already_linked":
				return Link.ALREADY_LINKED;
			case "rate_limited":
				return Link.RATE_LIMITED;
		}

		if (response.getStatus() == 401)
			return Link.CONFIG_ERROR;

		if (!response.isRetryable())
			Common.log("&cUnexpected /link/verify response: " + response);

		return Link.UNAVAILABLE;
	}

	private void showStatus(final Player player) {
		final CachedStatus cached = this.statusCache.get(player.getUniqueId());

		if (cached != null && !cached.isExpired()) {
			tell(cached.toMessage());

			return;
		}

		Common.runAsync(() -> {
			final ApiResponse response = ApiClient.get("/link/" + player.getUniqueId());
			final boolean linked = "true".equals(response.getDataString("linked"));
			final CachedStatus status = response.isSuccess() ? this.cacheStatus(player.getUniqueId(), linked ? response.getDataString("username") : "") : null;

			Common.runLater(() -> tellIfOnline(player, status != null ? status.toMessage() : response.getStatus() == 401 ? Link.CONFIG_ERROR : Link.UNAVAILABLE));
		});
	}

	private CachedStatus cacheStatus(final UUID uuid, final String username) {
		final CachedStatus status = new CachedStatus(username);

		this.statusCache.values().removeIf(CachedStatus::isExpired);
		this.statusCache.put(uuid, status);

		return status;
	}

	private static void tellIfOnline(final Player player, final String message) {
		if (player.isOnline())
			Common.tell(player, message);
	}

	private static final class CachedStatus {

		private final String username; // "" when not linked
		private final long expiresAt = System.currentTimeMillis() + STATUS_CACHE_MILLIS;

		CachedStatus(final String username) {
			this.username = username;
		}

		boolean isExpired() {
			return System.currentTimeMillis() > this.expiresAt;
		}

		String toMessage() {
			return this.username.isEmpty() ? Link.STATUS_NOT_LINKED : Link.STATUS_LINKED.replace("{username}", this.username);
		}
	}
}
