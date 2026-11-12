package pl.kingdomcraft.captainhook.velocity.mission;

/**
 * One mission of the pools in missions.yml.
 *
 * @param key       identifier in the database; do not rename it once assignments exist (a renamed mission counts as a new one)
 * @param always    true = part of every period, counted in the number of missions of that period
 * @param weight    chance of being drawn relative to the others of the pool
 * @param icon      login | clock | quest | vote | generic
 * @param unitEn    shown after the numbers ("min"), null for none
 */
public record MissionDefinition(String key, MissionPeriodType period, boolean always, Objective objective, long target, long xp, String icon,
		String unitEn, String unitPl, String titleEn, String titlePl, MissionFilter filter, int weight) {

	public static final java.util.Set<String> ICONS = java.util.Set.of("login", "clock", "quest", "vote", "generic");
}
