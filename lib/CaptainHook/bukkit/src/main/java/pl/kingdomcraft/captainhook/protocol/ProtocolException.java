package pl.kingdomcraft.captainhook.protocol;

/**
 * Thrown for plugin messages that are not valid messages of this protocol.
 */
public final class ProtocolException extends Exception {

	private static final long serialVersionUID = 1L;

	public ProtocolException(final String message) {
		super(message);
	}
}
