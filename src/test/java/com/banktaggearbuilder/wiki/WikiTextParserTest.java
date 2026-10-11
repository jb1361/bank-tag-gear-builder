package com.banktaggearbuilder.wiki;

import com.banktaggearbuilder.model.Alternative;
import com.banktaggearbuilder.model.EquipmentSlot;
import com.banktaggearbuilder.model.GearSetup;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

	/** First candidate name of each item of each alternative, for compact assertions. */
	private static List<List<String>> names(List<Alternative> alternatives)
	{
		List<List<String>> out = new ArrayList<>();
		for (Alternative a : alternatives)
		{
			List<String> items = new ArrayList<>();
			for (List<String> candidates : a.getItems())
			{
				items.add(candidates.get(0));
			}
			out.add(items);
		}
		return out;
	}

	private static List<List<String>> singles(String... items)
	{
		List<List<String>> out = new ArrayList<>();
		for (String s : items)
		{
			out.add(Collections.singletonList(s));
		}
		return out;
	}

	private static GearSetup find(List<GearSetup> setups, String style)
	{
		for (GearSetup s : setups)
		{
			if (s.getStyle().equals(style))
			{
				return s;
			}
		}
		throw new AssertionError("no setup with style " + style);
	}

	@Test
	public void parsesVorkathStrategies() throws IOException
	{
		List<GearSetup> setups = parser.parse(resource("vorkath-strategies.wikitext"));

		GearSetup ranged = find(setups, "Ranged (crossbow)");
		GearSetup melee = find(setups, "Melee");
		assertEquals("Equipment", ranged.getSection());

		// weapon1 = {{plink|Dragon hunter crossbow}}
		assertEquals(singles("Dragon hunter crossbow"), names(ranged.getSlots().get(EquipmentSlot.WEAPON).get(0)));

		// head1 has two alternatives separated by "/" and a footnote that must be dropped
		assertEquals(singles("Masori mask (f)", "Void ranger helm"), names(ranged.getSlots().get(EquipmentSlot.HEAD).get(0)));

		// head4 uses <br> as a separator and a pic= override: "Blessed coif|pic=Ancient coif"
		List<Alternative> head4 = ranged.getSlots().get(EquipmentSlot.HEAD).get(3);
		assertEquals(3, head4.size());
		assertEquals(Arrays.asList("Blessed coif", "Ancient coif"), head4.get(2).getItems().get(0));

		// ammo1 joins two items with "+": one alternative holding both
		assertEquals(Collections.singletonList(Arrays.asList("Ruby dragon bolts (e)", "Diamond dragon bolts (e)")),
			names(ranged.getSlots().get(EquipmentSlot.AMMO).get(0)));

		// ring5 uses txt= which must not leak into the name
		assertEquals(singles("Explorer's ring 4"), names(ranged.getSlots().get(EquipmentSlot.RING).get(4)));

		// weapon4 in melee uses ">" as a separator
		assertEquals(singles("Belle's folly", "Zamorakian hasta"), names(melee.getSlots().get(EquipmentSlot.WEAPON).get(3)));

		// special1 has trailing plain text "(on stab)" outside the plink that must be ignored
		assertEquals(singles("Voidwaker", "Burning claws"), names(melee.getSlots().get(EquipmentSlot.SPECIAL).get(0)));
	}

	@Test
	public void addsCombinedSetupForSectionsWithSeveralTables() throws IOException
	{
		List<GearSetup> setups = parser.parse(resource("vorkath-strategies.wikitext"));
		GearSetup combined = setups.get(0);
		assertTrue(combined.isCombined());
		assertEquals(2, combined.getComponents().size());
		assertEquals("All (Ranged (crossbow) + Melee)", combined.getStyle());
		// The combined inventory is the union of its components' inventories
		assertTrue(combined.getInventory().stream().anyMatch(n -> n.get(0).equals("Divine rune pouch")));
		assertFalse(combined.getRunePouch().isEmpty());
	}

	@Test
	public void matchesInventoryToSetupByTabberLabel() throws IOException
	{
		List<GearSetup> setups = parser.parse(resource("vorkath-strategies.wikitext"));
		GearSetup ranged = find(setups, "Ranged (crossbow)");

		List<List<String>> inv = ranged.getInventory();
		assertFalse(inv.isEmpty());
		assertEquals("Diamond dragon bolts (e)", inv.get(0).get(0));
		assertTrue(inv.stream().anyMatch(n -> n.get(0).equals("Divine rune pouch")));
		// {{Cheap food}} expands to a ranked list of foods
		assertTrue(inv.stream().anyMatch(n -> n.get(0).equals("Shark") && n.contains("Monkfish")));
		// Duplicates collapse to one entry
		assertEquals(1, inv.stream().filter(n -> n.get(0).equals("Cooked karambwan")).count());

		// The {{Rune pouch}} next to the inventory is kept separately
		assertEquals(singles("Law rune", "Dust rune", "Chaos rune"), ranged.getRunePouch());
	}

	@Test
	public void unionsInventoriesWhenTheyAreNotLabelledByStyle() throws IOException
	{
		List<GearSetup> setups = parser.parse(resource("toa-strategies.wikitext"));

		GearSetup rangedSwitch = find(setups, "Ranged switch");
		assertEquals("Equipment", rangedSwitch.getSection());
		// weapon1 = Twisted bow + Toxic blowpipe: both items in one alternative
		assertEquals(Collections.singletonList(Arrays.asList("Twisted bow", "Toxic blowpipe")),
			names(rangedSwitch.getSlots().get(EquipmentSlot.WEAPON).get(0)));
		// "See melee section" is not an item
		assertTrue(rangedSwitch.getSlots().get(EquipmentSlot.SPECIAL).size() <= 5);
		for (List<Alternative> rank : rangedSwitch.getSlots().get(EquipmentSlot.SPECIAL))
		{
			for (List<String> item : names(rank))
			{
				assertFalse(item.get(0).toLowerCase().startsWith("see "));
			}
		}

		// The example setups table has five unlabelled inventories: every switch gets their union
		assertTrue(rangedSwitch.getInventory().stream().anyMatch(n -> n.get(0).equals("Toxic blowpipe")));
		assertTrue(rangedSwitch.getInventory().stream().anyMatch(n -> n.get(0).equals("Dragon dagger")));
		assertTrue(rangedSwitch.getRunePouch().stream().anyMatch(n -> n.get(0).equals("Death rune")));

		// And the section gets a combined melee + ranged + magic setup
		GearSetup combined = setups.get(0);
		assertTrue(combined.isCombined());
		assertEquals(3, combined.getComponents().size());
		assertEquals("All (Melee switch + Ranged switch + Magic switch)", combined.getStyle());
		assertTrue(combined.isInventoryFromTiers());

		// Per-boss and per-phase tables further down the page are dropped: only the
		// top-level Equipment section's loadouts are offered
		assertEquals(4, setups.size());
		assertTrue(setups.stream().allMatch(s -> s.getSection().equals("Equipment")));
	}

	@Test
	public void handlesAlternativesWithoutPlink()
	{
		List<Alternative> alts = parser.parseAlternatives("[[Rune crossbow]] / Dragon crossbow<ref>note</ref>");
		assertEquals(singles("Rune crossbow", "Dragon crossbow"), names(alts));
	}

	@Test
	public void joinsItemsWithAndOutsideTemplates()
	{
		List<Alternative> alts = parser.parseAlternatives("{{plink|Voidwaker}} and {{plink|Bandos godsword}} / {{plink|Pestle and mortar}}");
		assertEquals(Arrays.asList(Arrays.asList("Voidwaker", "Bandos godsword"), Collections.singletonList("Pestle and mortar")), names(alts));
	}
}
