package com.banktaggearbuilder.wiki;

import com.banktaggearbuilder.model.EquipmentSlot;
import com.banktaggearbuilder.model.GearSetup;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WikiTextParserTest
{
	private final WikiTextParser parser = new WikiTextParser();

	private static String resource(String name) throws IOException
	{
		try (InputStream in = WikiTextParserTest.class.getResourceAsStream(name))
		{
			assertNotNull("missing test resource " + name, in);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	public void parsesVorkathStrategies() throws IOException
	{
		List<GearSetup> setups = parser.parse(resource("vorkath-strategies.wikitext"));

		assertEquals(2, setups.size());
		GearSetup ranged = setups.get(0);
		GearSetup melee = setups.get(1);
		assertEquals("Ranged (crossbow)", ranged.getStyle());
		assertEquals("Melee", melee.getStyle());

		// weapon1 = {{plink|Dragon hunter crossbow}}
		assertEquals(Collections.singletonList(Collections.singletonList("Dragon hunter crossbow")),
			ranged.getSlots().get(EquipmentSlot.WEAPON).get(0));

		// head1 has two alternatives separated by "/" and a footnote that must be dropped
		List<List<String>> head1 = ranged.getSlots().get(EquipmentSlot.HEAD).get(0);
		assertEquals(Arrays.asList(Collections.singletonList("Masori mask (f)"), Collections.singletonList("Void ranger helm")), head1);

		// head4 uses <br> as a separator and a pic= override: "Blessed coif|pic=Ancient coif"
		List<List<String>> head4 = ranged.getSlots().get(EquipmentSlot.HEAD).get(3);
		assertEquals(3, head4.size());
		assertEquals(Arrays.asList("Blessed coif", "Ancient coif"), head4.get(2));

		// ammo1 joins two items with "+"
		List<List<String>> ammo1 = ranged.getSlots().get(EquipmentSlot.AMMO).get(0);
		assertEquals(Arrays.asList(Collections.singletonList("Ruby dragon bolts (e)"), Collections.singletonList("Diamond dragon bolts (e)")), ammo1);

		// ring5 uses txt= which must not leak into the name
		assertEquals(Collections.singletonList("Explorer's ring 4"), ranged.getSlots().get(EquipmentSlot.RING).get(4).get(0));

		// weapon4 in melee uses ">" as a separator
		List<List<String>> weapon4 = melee.getSlots().get(EquipmentSlot.WEAPON).get(3);
		assertEquals(Arrays.asList(Collections.singletonList("Belle's folly"), Collections.singletonList("Zamorakian hasta")), weapon4);

		// special1 has trailing plain text "(on stab)" outside the plink that must be ignored
		assertEquals(Arrays.asList(Collections.singletonList("Voidwaker"), Collections.singletonList("Burning claws")),
			melee.getSlots().get(EquipmentSlot.SPECIAL).get(0));
	}

	@Test
	public void matchesInventoryToSetupByTabberLabel() throws IOException
	{
		List<GearSetup> setups = parser.parse(resource("vorkath-strategies.wikitext"));
		GearSetup ranged = setups.get(0);

		List<List<String>> inv = ranged.getInventory();
		assertFalse(inv.isEmpty());
		assertEquals("Diamond dragon bolts (e)", inv.get(0).get(0));
		assertTrue(inv.stream().anyMatch(names -> names.get(0).equals("Divine rune pouch")));
		// {{Cheap food}} expands to a ranked list of foods
		assertTrue(inv.stream().anyMatch(names -> names.get(0).equals("Shark") && names.contains("Monkfish")));
		// Duplicates collapse to one entry
		assertEquals(1, inv.stream().filter(names -> names.get(0).equals("Cooked karambwan")).count());

		// The {{Rune pouch}} next to the inventory is kept separately
		assertEquals(Arrays.asList(
			Collections.singletonList("Law rune"),
			Collections.singletonList("Dust rune"),
			Collections.singletonList("Chaos rune")), ranged.getRunePouch());
	}

	@Test
	public void handlesAlternativesWithoutPlink()
	{
		List<List<String>> alts = parser.parseAlternatives("[[Rune crossbow]] / Dragon crossbow<ref>note</ref>");
		assertEquals(Arrays.asList(Collections.singletonList("Rune crossbow"), Collections.singletonList("Dragon crossbow")), alts);
	}
}
