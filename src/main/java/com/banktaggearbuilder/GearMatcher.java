package com.banktaggearbuilder;

import com.banktaggearbuilder.model.BuildResult;
import com.banktaggearbuilder.model.EquipmentSlot;
import com.banktaggearbuilder.model.GearSetup;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Value;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;

/**
 * Picks the best-ranked owned item for each slot of a setup. Must run on the client thread
 * because it reads item compositions and prices.
 */
@Singleton
public class GearMatcher
{
	/** Trailing charge or dose marker, e.g. "Prayer potion(3)", "Amulet of glory(6)", "Ring of wealth (5)". */
	private static final Pattern CHARGE_SUFFIX = Pattern.compile("\\s?\\((\\d+)\\)$");
	private static final int MAX_CHARGE = 12;

	/** Combination runes and the two elemental runes that replace them when not owned. */
	private static final Map<String, String[]> COMBINATION_RUNES = new HashMap<>();

	static
	{
		COMBINATION_RUNES.put("mist rune", new String[]{"Air rune", "Water rune"});
		COMBINATION_RUNES.put("dust rune", new String[]{"Air rune", "Earth rune"});
		COMBINATION_RUNES.put("mud rune", new String[]{"Water rune", "Earth rune"});
		COMBINATION_RUNES.put("smoke rune", new String[]{"Air rune", "Fire rune"});
		COMBINATION_RUNES.put("steam rune", new String[]{"Water rune", "Fire rune"});
		COMBINATION_RUNES.put("lava rune", new String[]{"Earth rune", "Fire rune"});
	}

	/** A name resolved to the one item that should be tagged for it. */
	@Value
	private static class Resolved
	{
		String name;
		int itemId;
		boolean exact;
	}

	/** The numbered variants of an item family, highest charge first, plus the unnumbered base. */
	@Value
	private static class ChargeFamily
	{
		String base;
		int maxCharge;
		/** Names from highest charge to lowest, then the base name if it is an item. */
		List<String> namesBestFirst;
	}

	private final ItemManager itemManager;
	private final ItemNameIndex index;
	private final OwnedItems owned;

	@Inject
	private GearMatcher(ItemManager itemManager, ItemNameIndex index, OwnedItems owned)
	{
		this.itemManager = itemManager;
		this.index = index;
		this.owned = owned;
	}

	public BuildResult match(GearSetup setup, boolean includeInventoryAndEquipment, boolean includeInventoryItems)
	{
		BuildResult result = new BuildResult();

		for (EquipmentSlot slot : EquipmentSlot.values())
		{
			List<List<List<String>>> ranks = setup.getSlots().get(slot);
			if (ranks == null || ranks.isEmpty())
			{
				continue;
			}
			matchSlot(slot, ranks, includeInventoryAndEquipment, result);
		}

		if (includeInventoryItems)
		{
			for (List<String> names : setup.getInventory())
			{
				if (isPotion(names.get(0)))
				{
					addPotion(names.get(0), result);
					continue;
				}
				Resolved r = firstOwned(names, includeInventoryAndEquipment, result);
				if (r != null)
				{
					result.getInventory().add(new BuildResult.InventoryPick(r.getName(), r.getItemId(), r.isExact()));
				}
				else
				{
					result.getUnownedInventory().add(names.get(0));
				}
			}

			for (List<String> names : setup.getRunePouch())
			{
				addRune(names.get(0), includeInventoryAndEquipment, result);
			}
		}

		return result;
	}

	private void matchSlot(EquipmentSlot slot, List<List<List<String>>> ranks, boolean includeInvEquip, BuildResult result)
	{
		int bestSuggestion = -1;
		String bestSuggestionName = null;
		long bestPrice = Long.MAX_VALUE;
		int fallback = -1;
		String fallbackName = null;

		for (int r = 0; r < ranks.size(); r++)
		{
			for (List<String> names : ranks.get(r))
			{
				Resolved pick = firstOwned(names, includeInvEquip, result);
				if (pick != null)
				{
					result.getPicks().add(new BuildResult.SlotPick(slot, r + 1, pick.getName(), pick.getItemId(), pick.isExact()));
					return;
				}

				// Not owned: remember the cheapest option as the suggestion for this slot
				for (String name : names)
				{
					for (int id : index.lookup(name))
					{
						int canon = OwnedItems.canonical(itemManager, id);
						if (fallback < 0)
						{
							fallback = canon;
							fallbackName = name;
						}
						long price = itemManager.getItemPrice(canon);
						if (price > 0 && price < bestPrice)
						{
							bestPrice = price;
							bestSuggestion = canon;
							bestSuggestionName = name;
						}
					}
				}
			}
		}

		if (bestSuggestion < 0)
		{
			bestSuggestion = fallback;
			bestSuggestionName = fallbackName;
			bestPrice = 0;
		}
		result.getMissing().add(new BuildResult.MissingSlot(slot, bestSuggestionName, bestSuggestion, bestPrice));
	}

	/**
	 * @return the item to tag for the first owned candidate name, or null if none is owned
	 */
	private Resolved firstOwned(List<String> names, boolean includeInvEquip, BuildResult result)
	{
		boolean resolvedAny = false;
		for (String name : names)
		{
			List<Integer> ids = index.lookup(name);
			ChargeFamily family = chargeFamily(name);
			if (ids.isEmpty() && family == null)
			{
				continue;
			}
			resolvedAny = true;

			// Charged items: use only the highest charge the player actually has
			if (family != null)
			{
				for (String variant : family.getNamesBestFirst())
				{
					for (int id : index.lookup(variant))
					{
						int exact = itemManager.canonicalize(id);
						if (owned.ownsExact(exact, includeInvEquip))
						{
							return new Resolved(itemManager.getItemComposition(exact).getName(), exact, true);
						}
					}
				}
			}

			for (int id : ids)
			{
				int canon = OwnedItems.canonical(itemManager, id);
				if (owned.owns(canon, includeInvEquip))
				{
					return new Resolved(itemManager.getItemComposition(canon).getName(), canon, false);
				}
			}
		}
		if (!resolvedAny && !names.isEmpty())
		{
			result.getUnresolvedNames().add(names.get(0));
		}
		return null;
	}

	/**
	 * Finds the numbered variants of a name such as "Amulet of glory" or "Ring of dueling(8)".
	 * Returns null when the item has no "(N)" variants in the game cache.
	 */
	private ChargeFamily chargeFamily(String name)
	{
		String base = CHARGE_SUFFIX.matcher(name).replaceFirst("");
		List<String> found = new ArrayList<>();
		int max = 0;
		for (int charge = MAX_CHARGE; charge >= 1; charge--)
		{
			String tight = base + "(" + charge + ")";
			String spaced = base + " (" + charge + ")";
			if (!index.lookup(tight).isEmpty())
			{
				found.add(tight);
			}
			else if (!index.lookup(spaced).isEmpty())
			{
				found.add(spaced);
			}
			else
			{
				continue;
			}
			max = Math.max(max, charge);
		}
		if (found.isEmpty())
		{
			return null;
		}
		if (!index.lookup(base).isEmpty())
		{
			found.add(base);
		}
		return new ChargeFamily(base, max, found);
	}

	/**
	 * Potions have doses 1-4 and are not equipable; anything numbered beyond 4 or wearable
	 * (jewellery, charged weapons) is a charged item instead.
	 */
	private boolean isPotion(String name)
	{
		Matcher m = CHARGE_SUFFIX.matcher(name);
		if (!m.find())
		{
			return false;
		}
		ChargeFamily family = chargeFamily(name);
		if (family == null || family.getMaxCharge() > 4)
		{
			return false;
		}
		for (int id : index.lookup(family.getNamesBestFirst().get(0)))
		{
			ItemStats stats = itemManager.getItemStats(id);
			if (stats != null && stats.isEquipable())
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Potions are always tagged as exactly their 4-dose item, owned or not, so the tag never
	 * shows partly used doses.
	 */
	private void addPotion(String name, BuildResult result)
	{
		String fourDose = CHARGE_SUFFIX.matcher(name).replaceFirst("(4)");
		List<Integer> ids = index.lookup(fourDose);
		if (ids.isEmpty())
		{
			result.getUnresolvedNames().add(fourDose);
			return;
		}
		// Lowest id is the ordinary item; higher duplicates are minigame or quest copies
		int id = itemManager.canonicalize(ids.get(0));
		result.getInventory().add(new BuildResult.InventoryPick(fourDose, id, true));
	}

	/**
	 * Runes are consumables, so they are always included. A combination rune the player does
	 * not own is replaced by its two elemental runes, which the wiki lists as the alternative.
	 */
	private void addRune(String name, boolean includeInvEquip, BuildResult result)
	{
		List<Integer> ids = index.lookup(name);
		if (ids.isEmpty())
		{
			result.getUnresolvedNames().add(name);
			return;
		}
		int id = OwnedItems.canonical(itemManager, ids.get(0));
		String[] components = COMBINATION_RUNES.get(name.toLowerCase(Locale.ROOT));
		if (components != null && !owned.owns(id, includeInvEquip))
		{
			for (String component : components)
			{
				addRune(component, includeInvEquip, result);
			}
			return;
		}
		for (BuildResult.InventoryPick existing : result.getRunePouch())
		{
			if (existing.getItemId() == id)
			{
				return;
			}
		}
		result.getRunePouch().add(new BuildResult.InventoryPick(itemManager.getItemComposition(id).getName(), id, false));
	}

	/**
	 * Fills {@code into} with slot display name to item name, used for before/after diffs.
	 */
	public static void summarize(BuildResult result, Map<String, String> into)
	{
		into.clear();
		for (BuildResult.SlotPick pick : result.getPicks())
		{
			into.put(pick.getSlot().getDisplayName(), pick.getName());
		}
	}
}
