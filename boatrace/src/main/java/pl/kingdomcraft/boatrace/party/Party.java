package pl.kingdomcraft.boatrace.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One party: a leader, members (the leader is a member too, in join order), a size limit and pending invitations. */
public final class Party {

	private UUID leader;
	private final Set<UUID> members = new LinkedHashSet<>();
	/** invited player -> expiry (epoch ms) */
	private final Map<UUID, Long> invites = new HashMap<>();
	private int limit;

	Party(final UUID leader, final int limit) {
		this.leader = leader;
		this.limit = limit;
		this.members.add(leader);
	}

	public UUID getLeader() {
		return this.leader;
	}

	public boolean isLeader(final UUID uuid) {
		return this.leader.equals(uuid);
	}

	public int getLimit() {
		return this.limit;
	}

	void setLimit(final int limit) {
		this.limit = limit;
	}

	public int size() {
		return this.members.size();
	}

	public boolean isFull() {
		return this.members.size() >= this.limit;
	}

	public boolean contains(final UUID uuid) {
		return this.members.contains(uuid);
	}

	/** Leader first, then the others in join order. */
	public List<UUID> getMembers() {
		return new ArrayList<>(this.members);
	}

	void add(final UUID uuid) {
		this.members.add(uuid);
		this.invites.remove(uuid);
	}

	void remove(final UUID uuid) {
		this.members.remove(uuid);
	}

	void invite(final UUID uuid, final long expiresAt) {
		this.invites.put(uuid, expiresAt);
	}

	/** @return true when the player has a not yet expired invitation */
	boolean hasInvite(final UUID uuid) {
		final Long expires = this.invites.get(uuid);

		if (expires == null)
			return false;

		if (expires < System.currentTimeMillis()) {
			this.invites.remove(uuid);
			return false;
		}

		return true;
	}
}
