package pl.kingdomcraft.captainhook.api;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.mineacademy.fo.Common;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.kingdomcraft.captainhook.settings.Settings;

/**
 * Blocking HTTP client for the KingdomCraft web API (account linking), signing every request with HMAC-SHA256.
 *
 * Calls block until the response arrives, so they must only be made off the main thread (Common.runAsync).
 */
public final class ApiClient {

	private static final Gson GSON = new Gson();

	private static final char[] HEX = "0123456789abcdef".toCharArray();

	private ApiClient() {
	}

	public static ApiResponse get(final String path) {
		return request("GET", path, "");
	}

	public static ApiResponse post(final String path, final Object body) {
		return request("POST", path, GSON.toJson(body));
	}

	private static ApiResponse request(final String method, final String path, final String body) {
		final ApiResponse response = execute(method, path, body);

		if (response.getStatus() == 401)
			Common.warning("Web API rejected our signature (" + response.getErrorCode() + ") on " + method + " " + path
					+ ". Check api.secret and api.server-id in config.yml and make sure the server clock is NTP-synced.");

		return response;
	}

	private static ApiResponse execute(final String method, final String path, final String body) {
		try {
			final byte[] payload = body.getBytes(StandardCharsets.UTF_8);
			final String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
			final HttpURLConnection connection = (HttpURLConnection) new URL(Settings.Api.BASE_URL + path).openConnection();

			connection.setRequestMethod(method);
			connection.setConnectTimeout(Settings.Api.TIMEOUT_MILLIS);
			connection.setReadTimeout(Settings.Api.TIMEOUT_MILLIS);
			connection.setRequestProperty("Accept", "application/json");
			connection.setRequestProperty("X-KC-Server", Settings.Api.SERVER_ID);
			connection.setRequestProperty("X-KC-Timestamp", timestamp);
			connection.setRequestProperty("X-KC-Signature", sign(timestamp, payload));

			if (payload.length > 0) {
				connection.setDoOutput(true);
				connection.setFixedLengthStreamingMode(payload.length);
				connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

				try (OutputStream output = connection.getOutputStream()) {
					output.write(payload);
				}
			}

			final int status = connection.getResponseCode();

			return new ApiResponse(status, readJson(status < 400 ? connection.getInputStream() : connection.getErrorStream()));

		} catch (final IOException ex) {
			return new ApiResponse(ApiResponse.NETWORK_ERROR, new JsonObject());
		}
	}

	/**
	 * hex(HMAC_SHA256(secret, timestamp + "\n" + rawBody)) in lower case.
	 */
	private static String sign(final String timestamp, final byte[] payload) {
		try {
			final Mac mac = Mac.getInstance("HmacSHA256");

			mac.init(new SecretKeySpec(Settings.Api.SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			mac.update((timestamp + "\n").getBytes(StandardCharsets.UTF_8));

			final byte[] digest = mac.doFinal(payload);
			final char[] hex = new char[digest.length * 2];

			for (int i = 0; i < digest.length; i++) {
				hex[i * 2] = HEX[(digest[i] >> 4) & 0xF];
				hex[i * 2 + 1] = HEX[digest[i] & 0xF];
			}

			return new String(hex);

		} catch (final GeneralSecurityException ex) {
			throw new IllegalStateException("HmacSHA256 is unavailable in this JVM", ex);
		}
	}

	/**
	 * Reads the stream fully (so the keep-alive connection can be reused) and returns it as JSON,
	 * or an empty object for empty/non-JSON bodies such as a proxy's HTML error page.
	 */
	private static JsonObject readJson(final InputStream stream) {
		if (stream == null)
			return new JsonObject();

		try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
			final JsonElement element = new JsonParser().parse(reader);

			return element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();

		} catch (final IOException | RuntimeException ex) {
			return new JsonObject();
		}
	}
}
