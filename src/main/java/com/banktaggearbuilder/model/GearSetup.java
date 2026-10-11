package com.banktaggearbuilder.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * One "Recommended equipment" table from a wiki strategy page, plus the inventory and rune
 * pouch that go with it if the page lists them.
 * <p>
 * Each slot holds ranked entries (rank 1 is best). Each rank may list several alternatives
 * (the wiki separates them with "/"), and an alternative may group items that go together.
 * <p>
 * A combined setup has {@link #components}: several tables (e.g. melee, ranged and magic
 * switches for a raid) that are all built into one tag.
 */
@Data
public class GearSetup
{
	private String style = "";
	/** The wiki section heading the table sits under, for telling similar tables apart. */
	private String section = "";
	private Map<EquipmentSlot, List<List<Alternative>>> slots = new EnumMap<>(EquipmentSlot.class);
	/** Inventory entries in slot order (1-28). Each entry is a list of alternative names. */
	private List<List<String>> inventory = new ArrayList<>();
	/** Runes the wiki puts in the rune pouch for this setup, if it lists a pouch. */
	private List<List<String>> runePouch = new ArrayList<>();
	/** For a combined setup, the tables it is made of; empty otherwise. */
	private List<GearSetup> components = new ArrayList<>();

	public void addRank(EquipmentSlot slot, int rank, List<Alternative> alternatives)
	{
		List<List<Alternative>> ranks = slots.computeIfAbsent(slot, s -> new ArrayList<>());
		while (ranks.size() < rank)
		{
			ranks.add(new ArrayList<>());
		}
		ranks.set(rank - 1, alternatives);
	}

	public boolean isCombined()
	{
		return components != null && !components.isEmpty();
	}

	public boolean isEmpty()
	{
		return !isCombined() && slots.values().stream().allMatch(r -> r.stream().allMatch(List::isEmpty));
	}

	/** Text for the setup picker: "Section: style", or just the style. */
	public String getDisplayName()
	{
		String s = style == null || style.isEmpty() ? "Default setup" : style;
		return section == null || section.isEmpty() ? s : section + ": " + s;
	}
}
