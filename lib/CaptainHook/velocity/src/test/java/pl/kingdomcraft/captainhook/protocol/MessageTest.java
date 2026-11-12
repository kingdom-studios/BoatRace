package pl.kingdomcraft.captainhook.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;

class MessageTest {

	@Test
	void requestSurvivesTheWire() throws ProtocolException {
		final UUID uuid = UUID.randomUUID();
		final JsonObject data = new JsonObject();

		data.addProperty("uuid", uuid.toString());
		data.addProperty("amount", 9_007_199_254_740_993L); // not representable as a double

		final Message decoded = Message.decode(Message.request(Protocol.PASS_ADD_XP, 42, "hub", data).encode());

		assertEquals(Protocol.VERSION, decoded.getVersion());
		assertEquals(Protocol.PASS_ADD_XP, decoded.getType());
		assertEquals(42, decoded.getId());
		assertEquals("hub", decoded.getServer());
		assertTrue(decoded.isRequest());
		assertFalse(decoded.isReply());
		assertEquals(uuid, Json.getUuid(decoded.getData(), "uuid"));
		assertEquals(9_007_199_254_740_993L, Json.getLong(decoded.getData(), "amount", 0));
	}

	@Test
	void errorReplyCarriesItsCode() throws ProtocolException {
		final Message decoded = Message.decode(Message.error(7, Protocol.ERROR_NO_SEASON, "No season pass is active").encode());

		assertTrue(decoded.isReply());
		assertFalse(decoded.isRequest());
		assertFalse(decoded.isOk());
		assertEquals(7, decoded.getReplyTo());
		assertEquals(Protocol.ERROR_NO_SEASON, decoded.getError());
		assertEquals("No season pass is active", decoded.getErrorMessage());
		assertNull(decoded.getServer());
	}

	@Test
	void notificationHasNoId() throws ProtocolException {
		final Message decoded = Message.decode(Message.notification(Protocol.COINS_TOP, null, null).encode());

		assertFalse(decoded.isRequest());
		assertFalse(decoded.isReply());
		assertTrue(decoded.getData().entrySet().isEmpty());
	}

	@Test
	void unknownFieldsAndOtherVersionsStillDecode() throws ProtocolException {
		final Message decoded = Message.decode("{\"v\":99,\"t\":\"future.type\",\"id\":3,\"new\":[1,2],\"d\":{\"x\":1}}".getBytes(StandardCharsets.UTF_8));

		assertEquals(99, decoded.getVersion());
		assertEquals("future.type", decoded.getType());
		assertEquals(1, Json.getInt(decoded.getData(), "x", 0));
	}

	@Test
	void garbageIsRejected() {
		assertThrows(ProtocolException.class, () -> Message.decode("not json".getBytes(StandardCharsets.UTF_8)));
		assertThrows(ProtocolException.class, () -> Message.decode("[1,2,3]".getBytes(StandardCharsets.UTF_8)));
		assertThrows(ProtocolException.class, () -> Message.decode("{\"v\":1,\"d\":{}}".getBytes(StandardCharsets.UTF_8)));
		assertThrows(ProtocolException.class, () -> Message.decode(new byte[0]));
	}

	@Test
	void lenientReadersFallBackToDefaults() {
		final JsonObject json = new JsonObject();

		json.addProperty("text", "abc");
		json.addProperty("flag", "yes");

		assertEquals(5, Json.getLong(json, "text", 5));
		assertEquals(5, Json.getLong(json, "missing", 5));
		assertFalse(Json.getBoolean(json, "flag", false));
		assertNull(Json.getUuid(json, "text"));
		assertEquals(0, Json.getArray(json, "text").size());
	}
}
