package com.banktaggearbuilder.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;
import lombok.Value;

/**
 * The outcome of matching a {@link GearSetup} against what the player owns.
 */
@Data
public class BuildResult
{
	@Value
	public static class SlotPick
	{
		EquipmentSlot slot;
		int rank;
		String name;
		/** The item id to tag. Variation-tagging makes every variant of this item match. */
		int itemId;
	}

	@Value
	public static class MissingSlot
	{
		EquipmentSlot slot;
		/** Cheapest recommended item, or null if no name on the list could be resolved. */
		String suggestionName;
		int suggestionItemId;
		long suggestionPrice;
	}

	@Value
	public static class InventoryPick
	{
		String name;
		int itemId;
	}

	private final List<SlotPick> picks = new ArrayList<>();
	private final List<MissingSlot> missing = new ArrayList<>();
	private final List<InventoryPick> inventory = new ArrayList<>();
	private final List<String> unownedInventory = new ArrayList<>();
	private final Set<String> unresolvedNames = new LinkedHashSet<>();

	/**
	 * Item ids to include in the tag, in equipment-slot order followed by inventory order.
	 */
	public List<Integer> getTagItemIds()
	{
		Set<Integer> ids = new LinkedHashSet<>();
		for (SlotPick pick : picks)
		{
			ids.add(pick.getItemId());
		}
		for (InventoryPick pick : inventory)
		{
			ids.add(pick.getItemId());
		}
		return new ArrayList<>(ids);
	}

	/** The item used as the tag tab icon: the best weapon, else the first picked item. */
	public int getIconItemId()
	{
		for (SlotPick pick : picks)
		{
			if (pick.getSlot() == EquipmentSlot.WEAPON || pick.getSlot() == EquipmentSlot.TWO_HANDED)
			{
				return pick.getItemId();
			}
		}
		if (!picks.isEmpty())
		{
			return picks.get(0).getItemId();
		}
		if (!inventory.isEmpty())
		{
			return inventory.get(0).getItemId();
		}
		return -1;
	}
}
