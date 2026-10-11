package com.banktaggearbuilder.model;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * One "Recommended equipment" table from a wiki strategy page, plus the inventory
 * that goes with it if the page lists one.
 * <p>
 * Each slot holds ranked entries (rank 1 is best). Each rank may list several
 * alternative items (the wiki separates them with "/"), and each alternative may
 * have several candidate names (the page name and the "pic=" item name).
 */
@Data
public class GearSetup
{
	private String style = "";
	private Map<EquipmentSlot, List<List<List<String>>>> slots = new EnumMap<>(EquipmentSlot.class);
	/** Inventory entries in slot order (1-28). Each entry is a list of alternative names. */
	private List<List<String>> inventory = new ArrayList<>();
	/** Runes the wiki puts in the rune pouch for this setup, if it lists a pouch. */
	private List<List<String>> runePouch = new ArrayList<>();

	public void addRank(EquipmentSlot slot, int rank, List<List<String>> alternatives)
	{
		List<List<List<String>>> ranks = slots.computeIfAbsent(slot, s -> new ArrayList<>());
		while (ranks.size() < rank)
		{
			ranks.add(new ArrayList<>());
		}
		ranks.set(rank - 1, alternatives);
	}

	public boolean isEmpty()
	{
		return slots.values().stream().allMatch(r -> r.stream().allMatch(List::isEmpty));
	}
}
