package pl.kingdomcraft.captainhook.protocol;

import java.nio.charset.StandardCharsets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * One plugin message: a UTF-8 JSON object, see docs/NETWORK_PROTOCOL.md for the envelope.
 *
 * - request:      has an id, the receiver answers with a reply carrying the same id in "re"
 * - notification: has no id and is never answered
 * - reply:        type "reply", "re" is the id of the request, "ok" tells success from failure
 */
public final class Message {

	private final int version;
	private final String type;
	private final long id;
	private final long replyTo;
	private final boolean ok;
	private final String error;
	private final String errorMessage;
	private final String server;
	private final JsonObject data;

	private Message(final int version, final String type, final long id, final long replyTo, final boolean ok, final String error,
			final String errorMessage, final String server, final JsonObject data) {
		this.version = version;
		this.type = type;
		this.id = id;
		this.replyTo = replyTo;
		this.ok = ok;
		this.error = error;
		this.errorMessage = errorMessage;
		this.server = server;
		this.data = data != null ? data : new JsonObject();
	}

	/**
	 * @param server ID of the sending backend server, null on the proxy
	 */
	public static Message request(final String type, final long id, final String server, final JsonObject data) {
		if (id <= 0)
			throw new IllegalArgumentException("Request ID must be positive, got " + id);

		return new Message(Protocol.VERSION, type, id, 0, true, null, null, server, data);
	}

	public static Message notification(final String type, final String server, final JsonObject data) {
		return new Message(Protocol.VERSION, type, 0, 0, true, null, null, server, data);
	}

	public static Message reply(final long replyTo, final JsonObject data) {
		return new Message(Protocol.VERSION, Protocol.REPLY, 0, replyTo, true, null, null, null, data);
	}

	public static Message error(final long replyTo, final String code, final String message) {
		return new Message(Protocol.VERSION, Protocol.REPLY, 0, replyTo, false, code, message, null, null);
	}

	public int getVersion() {
		return this.version;
	}

	public String getType() {
		return this.type;
	}

	/**
	 * @return the request ID, or 0 for notifications and replies
	 */
	public long getId() {
		return this.id;
	}

	public boolean isRequest() {
		return this.id > 0;
	}

	public boolean isReply() {
		return Protocol.REPLY.equals(this.type);
	}

	/**
	 * @return the ID of the request this reply answers
	 */
	public long getReplyTo() {
		return this.replyTo;
	}

	/**
	 * @return false for a failed reply, see {@link #getError()}
	 */
	public boolean isOk() {
		return this.ok;
	}

	/**
	 * @return one of the Protocol.ERROR_ codes of a failed reply, otherwise null
	 */
	public String getError() {
		return this.error;
	}

	/**
	 * @return human readable details of a failed reply for the log, may be null
	 */
	public String getErrorMessage() {
		return this.errorMessage;
	}

	/**
	 * @return ID of the backend server that sent this message, null for messages from the proxy
	 */
	public String getServer() {
		return this.server;
	}

	/**
	 * @return the payload, never null
	 */
	public JsonObject getData() {
		return this.data;
	}

	public byte[] encode() {
		final JsonObject json = new JsonObject();

		json.addProperty("v", this.version);
		json.addProperty("t", this.type);

		if (this.id > 0)
			json.addProperty("id", this.id);

		if (this.isReply()) {
			json.addProperty("re", this.replyTo);
			json.addProperty("ok", this.ok);

			if (this.error != null)
				json.addProperty("err", this.error);

			if (this.errorMessage != null)
				json.addProperty("msg", this.errorMessage);
		}

		if (this.server != null)
			json.addProperty("s", this.server);

		json.add("d", this.data);

		return json.toString().getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * @throws ProtocolException if the bytes are not a message of this protocol (any version)
	 */
	@SuppressWarnings("deprecation") // the static JsonParser methods need Gson 2.8.6, old servers bundle older ones
	public static Message decode(final byte[] bytes) throws ProtocolException {
		final JsonObject json;

		try {
			final JsonElement element = new JsonParser().parse(new String(bytes, StandardCharsets.UTF_8));

			if (!element.isJsonObject())
				throw new ProtocolException("Not a JSON object");

			json = element.getAsJsonObject();

		} catch (final RuntimeException ex) {
			throw new ProtocolException("Malformed JSON: " + ex.getMessage());
		}

		try {
			final String type = Json.getString(json, "t", null);

			if (type == null || type.isEmpty())
				throw new ProtocolException("Missing message type");

			final JsonElement data = json.get("d");

			return new Message(Json.getInt(json, "v", 0), type, Json.getLong(json, "id", 0), Json.getLong(json, "re", 0),
					Json.getBoolean(json, "ok", true), Json.getString(json, "err", null), Json.getString(json, "msg", null),
					Json.getString(json, "s", null), data != null && data.isJsonObject() ? data.getAsJsonObject() : null);

		} catch (final RuntimeException ex) {
			throw new ProtocolException("Malformed envelope: " + ex.getMessage());
		}
	}

	@Override
	public String toString() {
		return this.isReply() ? "reply to " + this.replyTo + (this.ok ? "" : " (" + this.error + ")") : this.type + (this.id > 0 ? " #" + this.id : "");
	}
}
