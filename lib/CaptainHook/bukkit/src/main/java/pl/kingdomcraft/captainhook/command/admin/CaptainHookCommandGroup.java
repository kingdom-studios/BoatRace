package pl.kingdomcraft.captainhook.command.admin;

import org.mineacademy.fo.annotation.AutoRegister;
import org.mineacademy.fo.command.ReloadCommand;
import org.mineacademy.fo.command.SimpleCommandGroup;

import pl.kingdomcraft.captainhook.Permissions;

/**
 * /captainhook (aliases from Command_Aliases) - plugin administration. Everything about the database
 * (including merging an old SQLite file) is on the proxy: /captainhookproxy.
 */
@AutoRegister
public final class CaptainHookCommandGroup extends SimpleCommandGroup {

	@Override
	protected void registerSubcommands() {
		registerSubcommand(new ReloadCommand(Permissions.ADMIN));
		registerSubcommand(new StatusSubCommand());
	}
}
