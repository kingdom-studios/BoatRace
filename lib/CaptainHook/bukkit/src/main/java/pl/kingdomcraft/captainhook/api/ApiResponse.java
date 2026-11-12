package pl.kingdomcraft.captainhook.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Immutable result of an API call. Status -1 means a network failure (no HTTP response).
 */
public final class ApiResponse {

	static final int NETWORK_ERROR = -1;

	private final int status;
	private final JsonObject body;

	ApiResponse(final int status, final JsonObject body) {
		this.status = status;
		this.body = body;
	}

	public int getStatus() {
		return this.status;
	}

	public boolean isSuccess() {
		return this.status >= 200 && this.status < 300;
	}

	/**
	 * Per contract: retry only on 5xx or network errors, never on 4xx.
	 */
	public boolean isRetryable() {
		return this.status == NETWORK_ERROR || this.status >= 500;
	}

	/**
	 * @return the "data" object of a successful response, or an empty object
	 */
	public JsonObject getData() {
		return getObject(this.body, "data");
	}

	/**
	 * @return "data.{key}" as a string, or an empty string
	 */
	public String getDataString(final String key) {
		return getString(this.getData(), key);
	}

	/**
	 * @return "error.code" of a failed response, or an empty string
	 */
	public String getErrorCode() {
		return getString(getObject(this.body, "error"), "code");
	}

	public String getErrorMessage() {
		return getString(getObject(this.body, "error"), "message");
	}

	private static JsonObject getObject(final JsonObject parent, final String key) {
		final JsonElement element = parent.get(key);

		return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
	}

	private static String getString(final JsonObject object, final String key) {
		final JsonElement element = object.get(key);

		return element != null && element.isJsonPrimitive() ? element.getAsString() : "";
	}

	@Override
	public String toString() {
		return this.status == NETWORK_ERROR ? "network error" : "HTTP " + this.status + " " + this.getErrorCode() + " " + this.getErrorMessage();
	}
}
