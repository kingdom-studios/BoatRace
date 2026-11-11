package pl.kingdomcraft.boatrace.party;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import pl.kingdomcraft.boatrace.Settings;
import pl.kingdomcraft.boatrace.util.Msg;

/**
 * The built-in, in-memory party system (/party). Parties live only as long as the server runs and are not shared
 * with any other plugin. Every method that fails tells the player why, so commands only call it.
 */
public final class PartyManager implements Listener {

	private final Settings settings;
	private final Map<UUID, Party> byMember = new HashMap<>();

	public PartyManager(final Settings settings) {
		this.settings = settings;
	}

	// ------------------------------------------------------------------------------------------
	// Lookup
	// ------------------------------------------------------------------------------------------

	/** @return the party of the player, or null */
	public Party get(final UUID uuid) {
		return this.byMember.get(uuid);
	}

	/**
	 * Everybody who should start together when {@code player} joins a map: the leader first, then the other ONLINE
	 * members. A player without a party (or a non-leader) is a group of one.
	 */
	public List<Player> groupOf(final Player player) {
		final List<Player> group = new ArrayList<>();
		final Party party = this.byMember.get(player.getUniqueId());

		group.add(player);

		if (party == null || !party.isLeader(player.getUniqueId()))
			return group;

		for (final UUID uuid : party.getMembers()) {
			final Player member = Bukkit.getPlayer(uuid);

			if (member != null && member.isOnline() && !uuid.equals(player.getUniqueId()))
				group.add(member);
		}

		return group;
	}

	/** True when the player is a member (not the leader) of a party with more than one person. */
	public boolean isFollower(final Player player) {
		final Party party = this.byMember.get(player.getUniqueId());

		return party != null && party.size() > 1 && !party.isLeader(player.getUniqueId());
	}

	// ------------------------------------------------------------------------------------------
	// Commands
	// ------------------------------------------------------------------------------------------

	public void create(final Player player) {
		if (this.byMember.containsKey(player.getUniqueId())) {
			Msg.send(player, "party.already-in");
			return;
		}

		this.open(player);
		Msg.send(player, "party.created", "limit", this.settings.partyDefaultLimit);
	}

	private Party open(final Player leader) {
		final Party party = new Party(leader.getUniqueId(), this.settings.partyDefaultLimit);

		this.byMember.put(leader.getUniqueId(), party);

		return party;
	}

	/** Inviting without a party creates one (the inviter becomes its leader) - but only for a valid invitation. */
	public void invite(final Player leader, final String targetName) {
		Party party = this.byMember.get(leader.getUniqueId());

		if (party != null && !party.isLeader(leader.getUniqueId())) {
			Msg.send(leader, "party.not-leader");
			return;
		}

		final Player target = Bukkit.getPlayerExact(targetName);

		if (target == null) {
			Msg.send(leader, "command.player-not-found", "player", targetName);
			return;
		}

		if (target.getUniqueId().equals(leader.getUniqueId())) {
			Msg.send(leader, "party.cannot-invite-self");
			return;
		}

		if (this.byMember.containsKey(target.getUniqueId())) {
			Msg.send(leader, "party.target-in-party", "player", target.getName());
			return;
		}

		if (party == null) {
			party = this.open(leader);
			Msg.send(leader, "party.created-by-invite", "limit", this.settings.partyDefaultLimit);
		}

		if (party.isFull()) {
			Msg.send(leader, "party.full", "limit", party.getLimit());
			return;
		}

		party.invite(target.getUniqueId(), System.currentTimeMillis() + this.settings.partyInviteSeconds * 1000L);

		Msg.send(leader, "party.invite-sent", "player", target.getName(), "seconds", this.settings.partyInviteSeconds);
		Msg.send(target, "party.invited", "leader", leader.getName(), "seconds", this.settings.partyInviteSeconds);
	}

	/** The parties (distinct) that currently have a valid invitation for the player. */
	private List<Party> invitingParties(final UUID invited) {
		final List<Party> result = new ArrayList<>();

		for (final Party party : new java.util.LinkedHashSet<>(this.byMember.values()))
			if (party.hasInvite(invited))
				result.add(party);

		return result;
	}

	/** Names of the leaders who invited the player (tab completion). */
	public List<String> invitersOf(final UUID invited) {
		final List<String> names = new ArrayList<>();

		for (final Party party : this.invitingParties(invited))
			names.add(this.nameOf(party.getLeader()));

		return names;
	}

	/**
	 * /party join [inviter]. Without a name the player joins the party that invited them - as long as there is only
	 * one such invitation; with several, they have to name the leader.
	 */
	public void join(final Player player, final String inviterName) {
		if (this.byMember.containsKey(player.getUniqueId())) {
			Msg.send(player, "party.already-in");
			return;
		}

		final Party party;

		if (inviterName == null) {
			final List<Party> invitations = this.invitingParties(player.getUniqueId());

			if (invitations.isEmpty()) {
				Msg.send(player, "party.no-invites");
				return;
			}

			if (invitations.size() > 1) {
				Msg.send(player, "party.multiple-invites", "leaders", String.join(", ", this.invitersOf(player.getUniqueId())));
				return;
			}

			party = invitations.get(0);

		} else {
			final Player inviter = Bukkit.getPlayerExact(inviterName);

			party = inviter == null ? null : this.byMember.get(inviter.getUniqueId());

			if (party == null || !party.hasInvite(player.getUniqueId())) {
				Msg.send(player, "party.no-invite", "player", inviterName);
				return;
			}
		}

		if (party.isFull()) {
			Msg.send(player, "party.full", "limit", party.getLimit());
			return;
		}

		party.add(player.getUniqueId());
		this.byMember.put(player.getUniqueId(), party);

		Msg.send(player, "party.you-joined", "leader", this.nameOf(party.getLeader()));

		for (final UUID uuid : party.getMembers())
			if (!uuid.equals(player.getUniqueId()))
				this.tell(uuid, "party.joined", "player", player.getName(), "count", party.size(), "limit", party.getLimit());
	}

	/** Leaving as leader disbands the whole party. */
	public void leave(final Player player) {
		final Party party = this.byMember.get(player.getUniqueId());

		if (party == null) {
			Msg.send(player, "party.not-in-party");
			return;
		}

		if (party.isLeader(player.getUniqueId())) {
			this.disband(party);
			return;
		}

		this.remove(party, player.getUniqueId());
		Msg.send(player, "party.you-left");

		for (final UUID uuid : party.getMembers())
			this.tell(uuid, "party.left", "player", player.getName());
	}

	public void list(final Player player) {
		final Party party = this.byMember.get(player.getUniqueId());

		if (party == null) {
			Msg.send(player, "party.not-in-party");
			return;
		}

		Msg.send(player, "party.list-header", "count", party.size(), "limit", party.getLimit());

		for (final UUID uuid : party.getMembers())
			Msg.send(player, "party.list-line", "player", this.nameOf(uuid),
					"role", Msg.text(party.isLeader(uuid) ? "party.role-leader" : "party.role-member"));
	}

	public void kick(final Player leader, final String targetName) {
		final Party party = this.requireLeader(leader);

		if (party == null)
			return;

		UUID target = null;

		for (final UUID uuid : party.getMembers())
			if (this.nameOf(uuid).equalsIgnoreCase(targetName))
				target = uuid;

		if (target == null) {
			Msg.send(leader, "party.not-member", "player", targetName);
			return;
		}

		if (party.isLeader(target)) {
			Msg.send(leader, "party.cannot-kick-self");
			return;
		}

		final String name = this.nameOf(target);

		this.remove(party, target);
		this.tell(target, "party.kicked");

		for (final UUID uuid : party.getMembers())
			this.tell(uuid, "party.kick-broadcast", "player", name);
	}

	public void setLimit(final Player leader, final int limit) {
		final Party party = this.requireLeader(leader);

		if (party == null)
			return;

		final int min = Math.max(2, party.size());

		if (limit < min || limit > this.settings.partyMaxLimit) {
			Msg.send(leader, "party.limit-invalid", "min", min, "max", this.settings.partyMaxLimit, "value", limit);
			return;
		}

		party.setLimit(limit);
		Msg.send(leader, "party.limit-set", "limit", limit);
	}

	// ------------------------------------------------------------------------------------------
	// Lifecycle
	// ------------------------------------------------------------------------------------------

	/** Everybody who leaves the server leaves the party; the leader's departure disbands it. */
	@EventHandler(priority = EventPriority.MONITOR)
	public void onQuit(final PlayerQuitEvent event) {
		final Player player = event.getPlayer();
		final Party party = this.byMember.get(player.getUniqueId());

		if (party == null)
			return;

		if (party.isLeader(player.getUniqueId())) {
			this.disband(party);
			return;
		}

		this.remove(party, player.getUniqueId());

		for (final UUID uuid : party.getMembers())
			this.tell(uuid, "party.left", "player", player.getName());
	}

	/** Server reload / shutdown. */
	public void clear() {
		this.byMember.clear();
	}

	// ------------------------------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------------------------------

	private Party requireLeader(final Player player) {
		final Party party = this.byMember.get(player.getUniqueId());

		if (party == null) {
			Msg.send(player, "party.not-in-party");
			return null;
		}

		if (!party.isLeader(player.getUniqueId())) {
			Msg.send(player, "party.not-leader");
			return null;
		}

		return party;
	}

	private void remove(final Party party, final UUID uuid) {
		party.remove(uuid);
		this.byMember.remove(uuid);
	}

	private void disband(final Party party) {
		final List<UUID> members = party.getMembers();

		for (final UUID uuid : members)
			this.byMember.remove(uuid);

		for (final UUID uuid : members)
			this.tell(uuid, "party.disbanded");
	}

	private void tell(final UUID uuid, final String key, final Object... placeholders) {
		final Player player = Bukkit.getPlayer(uuid);

		if (player != null)
			Msg.send(player, key, placeholders);
	}

	private String nameOf(final UUID uuid) {
		final Player online = Bukkit.getPlayer(uuid);

		if (online != null)
			return online.getName();

		final String name = Bukkit.getOfflinePlayer(uuid).getName();

		return name != null ? name : uuid.toString();
	}
}
