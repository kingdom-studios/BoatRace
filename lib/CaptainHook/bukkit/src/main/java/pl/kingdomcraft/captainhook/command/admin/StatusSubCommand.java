package pl.kingdomcraft.captainhook.command.admin;

import org.bukkit.command.CommandSender;
import org.mineacademy.fo.Common;
import org.mineacademy.fo.command.SimpleSubCommand;
import org.mineacademy.fo.model.Replacer;

import com.google.gson.JsonObject;

import pl.kingdomcraft.captainhook.Permissions;
import pl.kingdomcraft.captainhook.protocol.Json;
import pl.kingdomcraft.captainhook.protocol.Protocol;
import pl.kingdomcraft.captainhook.proxy.ProxyException;
import pl.kingdomcraft.captainhook.proxy.ProxyLink;
import pl.kingdomcraft.captainhook.settings.Localization.Admin;
import pl.kingdomcraft.captainhook.settings.Settings;
import pl.kingdomcraft.captainhook.stats.StatsSync;

/**
 * /captainhook status - asks the proxy who it is and shows the state of the link to it.
 */
final class StatusSubCommand extends SimpleSubCommand {

	StatusSubCommand() {
		super("status");

		setDescription(Admin.DESCRIPTION_STATUS);
		setPermission(Permissions.ADMIN);
	}

	@Override
	protected void onCommand() {
		final CommandSender sender = this.sender;

		// The command instance is reused by the next caller, so the callback uses the captured sender
		ProxyLink.request(Protocol.STATUS, new JsonObject()).whenComplete((reply, error) -> Common.tell(sender, Replacer.replaceArray(Admin.STATUS,
				"server", Settings.Api.SERVER_ID,
				"proxy", error == null ? Replacer.replaceArray(Admin.PROXY_CONNECTED, "version", Json.getString(reply, "plugin", "?"), "name", Json.getString(reply, "server", "?"))
						: describe(error),
				"database", error == null ? Json.getString(reply, "database", "?") : "-",
				"pending", StatsSync.getPendingCount(),
				"queued", ProxyLink.getQueuedCount())));
	}

	private static String describe(final Throwable error) {
		final Throwable cause = error.getCause() != null ? error.getCause() : error;

		if (cause instanceof ProxyException && ProxyException.NO_CARRIER.equals(((ProxyException) cause).getCode()))
			return Admin.PROXY_NO_CARRIER;

		return Replacer.replaceArray(Admin.PROXY_ERROR, "error", String.valueOf(cause.getMessage()));
	}
}
