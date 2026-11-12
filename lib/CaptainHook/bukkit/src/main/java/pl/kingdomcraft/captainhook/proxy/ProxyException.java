package pl.kingdomcraft.captainhook.proxy;

/**
 * A request to the proxy that got no answer or was refused. Futures of {@link ProxyLink} and of the public API
 * complete exceptionally with it.
 */
public final class ProxyException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	/**
	 * Nobody on this server can carry a plugin message right now (no players, or the proxy plugin is missing).
	 */
	public static final String NO_CARRIER = "no_carrier";

	/**
	 * The proxy did not answer in time. The request may or may not have been carried out.
	 */
	public static final String TIMEOUT = "timeout";

	/**
	 * The plugin was reloaded or disabled while the request was waiting.
	 */
	public static final String STOPPED = "stopped";

	private final String code;

	public ProxyException(final String code, final String message) {
		super(message != null ? code + ": " + message : code, null, false, false); // an expected outcome, no stack trace needed

		this.code = code;
	}

	/**
	 * @return one of the constants above, or an error code of the proxy (the Protocol.ERROR_ constants)
	 */
	public String getCode() {
		return this.code;
	}
}
