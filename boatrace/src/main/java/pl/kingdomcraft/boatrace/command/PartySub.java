package pl.kingdomcraft.boatrace.command;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.boatrace.party.Party;
import pl.kingdomcraft.boatrace.party.PartyManager;

/** One class for all /party subcommands (create, invite, join, leave, list, kick, limit). */
final class PartySub extends RaceSubCommand {

	enum Action {
		CREATE("create", "Create a party (you become the leader).", null, 0),
		INVITE("invite", "Invite a player to your party (leader).", "<player>", 1),
		JOIN("join", "Join the party of the player who invited you.", "<player>", 1),
		LEAVE("leave", "Leave the party (the leader disbands it).", null, 0),
		LIST("list", "List the party members.", null, 0),
		KICK("kick", "Kick a player from your party (leader).", "<player>", 1),
		LIMIT("limit", "Set the maximum party size (leader).", "<number>", 1);

		final String label;
		final String description;
		final String usage;
		final int minArgs;

		Action(final String label, final String description, final String usage, final int minArgs) {
			this.label = label;
			this.description = description;
			this.usage = usage;
			this.minArgs = minArgs;
		}
	}

	private final Action action;

	PartySub(final SimpleCommandGroup group, final Action action) {
		super(group, action.label, action.description, action.usage, action.minArgs, false);

		this.action = action;
	}

	@Override
	protected void onCommand() {
		final Player player = this.requirePlayer();
		final PartyManager parties = this.plugin().getParties();

		switch (this.action) {
			case CREATE -> parties.create(player);
			case INVITE -> parties.invite(player, this.args[0]);
			case JOIN -> parties.join(player, this.args[0]);
			case LEAVE -> parties.leave(player);
			case LIST -> parties.list(player);
			case KICK -> parties.kick(player, this.args[0]);
			case LIMIT -> {
				final int limit;

				try {
					limit = Integer.parseInt(this.args[0]);

				} catch (final NumberFormatException ex) {
					this.fail("arena.not-a-number", "value", this.args[0]);
					return;
				}

				parties.setLimit(player, limit);
			}
		}
	}

	@Override
	protected String[] getMultilineUsageMessage() {
		return this.action.usage == null ? new String[] { "/{label} " + this.action.label } : new String[] { "/{label} " + this.action.label + " " + this.action.usage };
	}

	@Override
	protected List<String> tabComplete() {
		if (this.args.length != 1 || !(this.getSender() instanceof Player player))
			return new ArrayList<>();

		switch (this.action) {
			case INVITE, JOIN:
				return this.completeLastWordPlayerNames();

			case KICK: {
				final Party party = this.plugin().getParties().get(player.getUniqueId());
				final List<String> names = new ArrayList<>();

				if (party != null)
					for (final java.util.UUID uuid : party.getMembers()) {
						final Player member = Bukkit.getPlayer(uuid);

						if (member != null && !uuid.equals(player.getUniqueId()))
							names.add(member.getName());
					}

				return this.completeLastWord(names);
			}

			default:
				return new ArrayList<>();
		}
	}
}
