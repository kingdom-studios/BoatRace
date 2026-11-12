package pl.kingdomcraft.boatrace.luckperms;

import java.util.UUID;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.MetaNode;

/**
 * The ONLY class that refers to LuckPerms' types; created by {@link LuckPermsHook} only when LuckPerms is enabled.
 * Meta is TRANSIENT: never saved, gone on logout or restart - a crash can never leave a stale team tag behind.
 */
final class LuckPermsGateway {

	private final LuckPerms api = LuckPermsProvider.get();

	void setMeta(final UUID uuid, final String key, final String value) {
		final User user = this.api.getUserManager().getUser(uuid);

		if (user == null)
			return;

		user.transientData().clear(NodeType.META.predicate(node -> node.getMetaKey().equalsIgnoreCase(key)));
		user.transientData().add(MetaNode.builder(key, value).build());
	}

	void clearMeta(final UUID uuid, final String key) {
		final User user = this.api.getUserManager().getUser(uuid);

		if (user != null)
			user.transientData().clear(NodeType.META.predicate(node -> node.getMetaKey().equalsIgnoreCase(key)));
	}
}
