package com.banktaggearbuilder;

import com.banktaggearbuilder.model.Alternative;
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
import net.runelite.client.game.ItemVariationMapping;

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
	/** Trailing degrade level, e.g. "Ahrim's hood 75". */
	private static final Pattern DEGRADE_SUFFIX = Pattern.compile(" (\\d{1,3})$");

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

		if (setup.isCombined())
		{
			for (GearSetup component : setup.getComponents())
			{
				matchEquipment(component, component.getStyle(), includeInventoryAndEquipment, result);
			}
		}
		else
		{
			matchEquipment(setup, "", includeInventoryAndEquipment, result);
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
					if (setup.isInventoryFromTiers() && isEquipable(r.getItemId()))
					{
						// Gear in a tiered example loadout belongs to the ranked tables, which
						// already picked the player's best; only supplies are taken from here
						continue;
					}
					if (!containsItem(result, r.getItemId()))
					{
						result.getInventory().add(new BuildResult.InventoryPick(r.getName(), r.getItemId(), r.isExact()));
					}
				}
				else if (!setup.isInventoryFromTiers())
				{
					result.getUnownedInventory().add(names.get(0));
				}
			}

			for (List<String> names : setup.getRunePouch())
			{
				addRune(names.get(0), includeInventoryAndEquipment, result);
			}

			mergeCombinationRunes(result.getRunePouch(), includeInventoryAndEquipment);
			mergeCombinationRunes(result.getInventory(), includeInventoryAndEquipment);
		}

		return result;
	}

	/**
	 * Where a list holds both elemental runes of a combination rune the player owns (fire and
	 * water with steam runes in the bank), the combination rune replaces the pair.
	 */
	private void mergeCombinationRunes(List<BuildResult.InventoryPick> picks, boolean includeInvEquip)
	{
		for (Map.Entry<String, String[]> e : COMBINATION_RUNES.entrySet())
		{
			BuildResult.InventoryPick first = findByName(picks, e.getValue()[0]);
			BuildResult.InventoryPick second = findByName(picks, e.getValue()[1]);
			if (first == null || second == null)
			{
				continue;
			}
			List<Integer> ids = index.lookup(e.getKey());
			if (ids.isEmpty())
			{
				continue;
			}
			int combo = OwnedItems.canonical(itemManager, ids.get(0));
			if (!owned.owns(combo, includeInvEquip))
			{
				continue;
			}
			int at = picks.indexOf(first);
			picks.remove(first);
			picks.remove(second);
			picks.add(Math.min(at, picks.size()), new BuildResult.InventoryPick(itemManager.getItemComposition(combo).getName(), combo, false));
		}
	}

	private static BuildResult.InventoryPick findByName(List<BuildResult.InventoryPick> picks, String name)
	{
		for (BuildResult.InventoryPick pick : picks)
		{
			if (pick.getName().equalsIgnoreCase(name))
			{
				return pick;
			}
		}
		return null;
	}

	private void matchEquipment(GearSetup setup, String group, boolean includeInvEquip, BuildResult result)
	{
		for (EquipmentSlot slot : EquipmentSlot.values())
		{
			List<List<Alternative>> ranks = setup.getSlots().get(slot);
			if (ranks == null || ranks.isEmpty())
			{
				continue;
			}
			matchSlot(group, slot, ranks, includeInvEquip, result);
		}
	}

	private boolean isEquipable(int itemId)
	{
		ItemStats stats = itemManager.getItemStats(itemId);
		return stats != null && stats.isEquipable();
	}

	/** True if an equipment pick or inventory pick already carries this item. */
	private static boolean containsItem(BuildResult result, int itemId)
	{
		for (BuildResult.SlotPick pick : result.getPicks())
		{
			if (pick.getItemId() == itemId)
			{
				return true;
			}
		}
		for (BuildResult.InventoryPick pick : result.getInventory())
		{
			if (pick.getItemId() == itemId)
			{
				return true;
			}
		}
		return false;
	}

	private void matchSlot(String group, EquipmentSlot slot, List<List<Alternative>> ranks, boolean includeInvEquip, BuildResult result)
	{
		int bestSuggestion = -1;
		String bestSuggestionName = null;
		long bestPrice = Long.MAX_VALUE;
		int fallback = -1;
		String fallbackName = null;

		// Special attack weapons serve different jobs (defence drain, burst damage, core
		// dumping), so every owned one across the ranks is included, not only the best rank
		boolean takeAllRanks = slot == EquipmentSlot.SPECIAL;
		boolean pickedAny = false;

		for (int r = 0; r < ranks.size(); r++)
		{
			for (Alternative alternative : ranks.get(r))
			{
				// Every owned item of the alternative is picked ("Twisted bow + Toxic blowpipe")
				List<Resolved> ownedItems = new ArrayList<>();
				for (List<String> names : alternative.getItems())
				{
					Resolved pick = firstOwned(names, includeInvEquip, result);
					if (pick != null)
					{
						ownedItems.add(pick);
					}
				}
				if (!ownedItems.isEmpty())
				{
					for (Resolved pick : ownedItems)
					{
						if (!containsPick(result, group, pick.getItemId()))
						{
							result.getPicks().add(new BuildResult.SlotPick(group, slot, r + 1, pick.getName(), pick.getItemId(), pick.isExact()));
						}
					}
					pickedAny = true;
					if (!takeAllRanks)
					{
						return;
					}
					break;
				}

				// Not owned: remember the cheapest option as the suggestion for this slot
				for (List<String> names : alternative.getItems())
				{
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
		}

		if (pickedAny)
		{
			return;
		}
		if (bestSuggestion < 0)
		{
			bestSuggestion = fallback;
			bestSuggestionName = fallbackName;
			bestPrice = 0;
		}
		result.getMissing().add(new BuildResult.MissingSlot(group, slot, bestSuggestionName, bestSuggestion, bestPrice));
	}

	/** True if this style already picked the item in some slot (a weapon listed again as a special). */
	private static boolean containsPick(BuildResult result, String group, int itemId)
	{
		for (BuildResult.SlotPick pick : result.getPicks())
		{
			if (pick.getGroup().equals(group) && pick.getItemId() == itemId)
			{
				return true;
			}
		}
		return false;
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
					Resolved best = bestOwnedVariant(canon, includeInvEquip);
					return best != null ? best : new Resolved(itemManager.getItemComposition(canon).getName(), canon, false);
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
	 * When the player owns several variants of one item ("Dizana's quiver (uncharged)" and
	 * "Dizana's quiver", "Masori body" and "Masori body (f)"), tagging by variation would show
	 * all of them, so only the best owned variant is tagged, by exact id.
	 *
	 * @return the best owned variant, or null if fewer than two variants are owned
	 */
	private Resolved bestOwnedVariant(int canonicalId, boolean includeInvEquip)
	{
		int best = -1;
		String bestName = null;
		int bestScore = Integer.MIN_VALUE;
		int ownedCount = 0;
		for (int id : ItemVariationMapping.getVariations(canonicalId))
		{
			if (!owned.ownsExact(id, includeInvEquip))
			{
				continue;
			}
			ownedCount++;
			String name = itemManager.getItemComposition(id).getName();
			int score = variantScore(name);
			if (score > bestScore || (score == bestScore && id < best))
			{
				best = id;
				bestName = name;
				bestScore = score;
			}
		}
		return ownedCount < 2 ? null : new Resolved(bestName, best, true);
	}

	/**
	 * Higher is better: uncharged or broken copies lose, fortified "(f)" upgrades win, and
	 * degraded items ("Ahrim's hood 75") prefer the most charge left. Locked "(l)" copies
	 * count the same as the plain item.
	 */
	private static int variantScore(String name)
	{
		String lower = name.toLowerCase(Locale.ROOT);
		int score = 0;
		if (lower.contains("uncharged") || lower.contains("broken") || lower.contains("inactive") || lower.contains("(empty)"))
		{
			score -= 1000;
		}
		if (lower.endsWith("(f)"))
		{
			score += 500;
		}
		Matcher m = DEGRADE_SUFFIX.matcher(lower);
		if (m.find())
		{
			score += Integer.parseInt(m.group(1));
		}
		return score;
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
		if (!containsItem(result, id))
		{
			result.getInventory().add(new BuildResult.InventoryPick(fourDose, id, true));
		}
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
	 * Fills {@code into} with "[style] slot" to item name, used for before/after diffs.
	 */
	public static void summarize(BuildResult result, Map<String, String> into)
	{
		into.clear();
		for (BuildResult.SlotPick pick : result.getPicks())
		{
			String key = (pick.getGroup().isEmpty() ? "" : pick.getGroup() + " ") + pick.getSlot().getDisplayName();
			// Several picks in one slot ("Twisted bow + Toxic blowpipe") are joined
			into.merge(key, pick.getName(), (a, b) -> a + " + " + b);
		}
	}
}
