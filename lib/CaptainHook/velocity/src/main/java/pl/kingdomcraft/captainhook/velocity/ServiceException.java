package pl.kingdomcraft.captainhook.velocity;

/**
 * A request that cannot be fulfilled for a reason the caller should hear about (as opposed to a database
 * failure). The code is one of the Protocol.ERROR_ constants and becomes the "err" of the reply.
 */
public final class ServiceException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String code;

	public ServiceException(final String code, final String message) {
		super(message, null, false, false); // an expected outcome, no stack trace needed

		this.code = code;
	}

	public String getCode() {
		return this.code;
	}
}
