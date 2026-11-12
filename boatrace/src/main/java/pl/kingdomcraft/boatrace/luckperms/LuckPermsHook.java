package pl.kingdomcraft.boatrace.luckperms;

import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Safe doorway to LuckPerms (softdepend): the team tag of a team race as a transient meta node, so chat / TAB formats
 * can show it between the rank prefix and the name (%luckperms_meta_<key>%). Never refers to LuckPerms' types
 * (only {@link LuckPermsGateway} does). Without LuckPerms every call is a no-op.
 */
public final class LuckPermsHook {

	private final JavaPlugin plugin;
	private LuckPermsGateway gateway;

	public LuckPermsHook(final JavaPlugin plugin) {
		this.plugin = plugin;
	}

	/** Safe to call again on reload. */
	public void init(final boolean enabled) {
		this.gateway = null;

		if (!enabled || !Bukkit.getPluginManager().isPluginEnabled("LuckPerms"))
			return;

		try {
			this.gateway = new LuckPermsGateway();
			this.plugin.getLogger().info("LuckPerms found - team races set the team tag as a transient meta.");

		} catch (final Throwable t) {
			this.plugin.getLogger().log(Level.WARNING, "Could not hook into LuckPerms - no team tag meta.", t);
		}
	}

	public void setMeta(final UUID uuid, final String key, final String value) {
		if (this.gateway != null && !key.isEmpty())
			try {
				this.gateway.setMeta(uuid, key, value);

			} catch (final Throwable t) {
				this.plugin.getLogger().warning("Could not set the LuckPerms meta " + key + ": " + t);
			}
	}

	public void clearMeta(final UUID uuid, final String key) {
		if (this.gateway != null && !key.isEmpty())
			try {
				this.gateway.clearMeta(uuid, key);

			} catch (final Throwable t) {
				this.plugin.getLogger().warning("Could not clear the LuckPerms meta " + key + ": " + t);
			}
	}
}
