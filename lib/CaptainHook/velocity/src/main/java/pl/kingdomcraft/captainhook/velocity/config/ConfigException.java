package pl.kingdomcraft.captainhook.velocity.config;

/**
 * A configuration file is missing, unreadable or has an invalid value. The message names the file and the key.
 */
public final class ConfigException extends Exception {

	private static final long serialVersionUID = 1L;

	public ConfigException(final String message) {
		super(message);
	}

	public ConfigException(final String message, final Throwable cause) {
		super(message, cause);
	}
}
