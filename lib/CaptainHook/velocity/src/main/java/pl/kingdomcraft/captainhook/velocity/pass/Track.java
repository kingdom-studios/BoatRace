package pl.kingdomcraft.captainhook.velocity.pass;

/**
 * The two reward tracks of a tier. Stored in lower case in ch_seasonpass_claims.track.
 */
public enum Track {
	FREE,
	PREMIUM;

	public String key() {
		return this.name().toLowerCase();
	}

	/**
	 * @return the track with this key, or null
	 */
	public static Track find(final String key) {
		for (final Track track : values())
			if (track.key().equalsIgnoreCase(key))
				return track;

		return null;
	}
}
