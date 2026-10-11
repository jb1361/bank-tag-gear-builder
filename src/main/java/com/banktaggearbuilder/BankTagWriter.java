package com.banktaggearbuilder;

import com.banktaggearbuilder.model.BuildResult;
import com.banktaggearbuilder.model.EquipmentSlot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.plugins.banktags.BankTagsService;
import net.runelite.client.plugins.banktags.TagManager;
import net.runelite.client.plugins.banktags.tabs.Layout;
import net.runelite.client.plugins.banktags.tabs.LayoutManager;
import net.runelite.client.plugins.banktags.tabs.TabManager;
import net.runelite.client.plugins.banktags.tabs.TagTab;
import net.runelite.client.util.Text;

/**
 * Writes tags into the Bank Tags plugin's storage. Must be called on the client thread.
 * <p>
 * Tab names and icons are written to the Bank Tags config keys, which is what its TabManager
 * does on save. TabManager itself only holds the tab list while the bank is open, so it is
 * only updated (and only then saved through) when the bank is open and its list matches
 * the saved one. Saving through it with the bank closed would drop the player's other tabs.
 */
@Slf4j
@Singleton
public class BankTagWriter
{
	private final Client client;
	private final TagManager tagManager;
	private final TabManager tabManager;
	private final LayoutManager layoutManager;
	private final BankTagsService bankTagsService;
	private final ConfigManager configManager;

	@Inject
	private BankTagWriter(Client client, TagManager tagManager, TabManager tabManager, LayoutManager layoutManager,
		BankTagsService bankTagsService, ConfigManager configManager)
	{
		this.client = client;
		this.tagManager = tagManager;
		this.tabManager = tabManager;
		this.layoutManager = layoutManager;
		this.bankTagsService = bankTagsService;
		this.configManager = configManager;
	}

	public boolean isBankOpen()
	{
		return client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER) != null;
	}

	private List<String> savedTabNames()
	{
		return new ArrayList<>(Text.fromCSV(nullToEmpty(configManager.getConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG))));
	}

	/**
	 * True when Bank Tags' in-memory tab list is loaded and agrees with the saved list, which
	 * means it is safe to change the list through TabManager and let it save.
	 */
	private boolean liveTabsLoaded()
	{
		if (!isBankOpen())
		{
			return false;
		}
		List<String> live = tabManager.getTabs().stream().map(TagTab::getTag).collect(Collectors.toList());
		return live.equals(savedTabNames());
	}

	/**
	 * Makes a name safe to use as a bank tag: lower case, no CSV or menu-breaking characters.
	 */
	public static String sanitizeTagName(String name)
	{
		String s = Text.standardize(name).replaceAll("[<>/:,*\"]", "").trim();
		return s.isEmpty() ? "gear" : s;
	}

	/**
	 * Creates or rewrites a tag so it contains exactly {@code itemIds}.
	 *
	 * @param exactIds        items tagged by exact id; every other item matches all its variants
	 * @param previousItemIds items this plugin previously wrote, removed if no longer wanted
	 */
	public void writeTag(String tag, List<Integer> itemIds, Set<Integer> exactIds, int iconItemId, List<Integer> previousItemIds, boolean layoutBySlot, BuildResult layoutSource)
	{
		tag = sanitizeTagName(tag);

		Set<Integer> wanted = new HashSet<>(itemIds);
		if (previousItemIds != null)
		{
			for (int old : previousItemIds)
			{
				if (!wanted.contains(old))
				{
					tagManager.removeTag(old, tag);
				}
			}
		}
		for (int id : itemIds)
		{
			boolean variation = !exactIds.contains(id);
			// Drop any stale tag of the other kind so a potion never matches all doses
			if (previousItemIds != null && previousItemIds.contains(id))
			{
				tagManager.removeTag(id, tag);
			}
			tagManager.addTag(id, tag, variation);
		}

		if (layoutBySlot && layoutSource != null)
		{
			layoutManager.saveLayout(buildLayout(tag, layoutSource));
		}

		if (liveTabsLoaded())
		{
			// Bank is open: register the tab with Bank Tags' live list so it is kept if the
			// player reorders or adds tabs before closing the bank, then save through it.
			TagTab tab = tabManager.find(tag);
			if (tab == null)
			{
				tab = new TagTab();
				tab.setTag(tag);
				tab.setIconItemId(iconItemId > 0 ? iconItemId : 0);
				tabManager.add(tab);
			}
			else if (iconItemId > 0)
			{
				tab.setIconItemId(iconItemId);
			}
			tabManager.save();
		}
		else
		{
			List<String> tabs = savedTabNames();
			if (!tabs.contains(tag))
			{
				tabs.add(tag);
				configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG, Text.toCSV(tabs));
			}
			if (iconItemId > 0)
			{
				configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + tag, String.valueOf(iconItemId));
			}
		}

		if (isBankOpen())
		{
			// Show the new tag's contents right away. The tab strip itself is redrawn by
			// Bank Tags the next time the bank is opened.
			bankTagsService.openBankTag(tag, BankTagsService.OPTION_ALLOW_MODIFICATIONS);
		}
	}

	/**
	 * Lays the tag out as a compact equipment-screen shape in the 8-wide bank grid:
	 * <pre>
	 *   .  H  .   .  2h S1 S2 S3
	 *   C  N  A   .  S4 S5 .  .
	 *   W  B  Sh  .  .  .  .  .
	 *   .  L  .   .  .  .  .  .
	 *   G  F  R   .  .  .  .  .
	 * </pre>
	 * followed by inventory items on the rows below.
	 */
	private static Layout buildLayout(String tag, BuildResult result)
	{
		final int w = BankTagsPlugin.BANK_ITEMS_PER_ROW;
		Layout layout = new Layout(tag);
		Set<Integer> placed = new HashSet<>();

		// One 5-row equipment block per style, stacked top to bottom. A single setup has one.
		List<String> groups = result.getGroups();
		if (groups.isEmpty())
		{
			groups = new ArrayList<>();
			groups.add("");
		}
		for (int g = 0; g < groups.size(); g++)
		{
			placeEquipmentBlock(layout, result, groups.get(g), w * 5 * g, placed);
		}

		// Inventory below the equipment, 4 wide like the in-game inventory. If the setup has a
		// rune pouch, the pouch sits to the right with its runes in a 2-wide block beneath it:
		//   I I I I . P . .
		//   I I I I . R R .
		//   I I I I . R R .
		final int invRow = 5 * groups.size();
		final int invWidth = 4;
		final int pouchCol = 5;
		List<BuildResult.InventoryPick> runes = result.getRunePouch();
		BuildResult.InventoryPick pouch = null;
		if (!runes.isEmpty())
		{
			for (BuildResult.InventoryPick pick : result.getInventory())
			{
				if (pick.getName().toLowerCase().contains("rune pouch"))
				{
					pouch = pick;
					break;
				}
			}
		}

		int i = 0;
		for (BuildResult.InventoryPick pick : result.getInventory())
		{
			if (pick == pouch || !placed.add(pick.getItemId()))
			{
				continue;
			}
			layout.setItemAtPos(pick.getItemId(), w * (invRow + i / invWidth) + i % invWidth);
			i++;
		}

		if (pouch != null)
		{
			layout.setItemAtPos(pouch.getItemId(), w * invRow + pouchCol);
			placed.add(pouch.getItemId());
		}
		int r = 0;
		for (BuildResult.InventoryPick rune : runes)
		{
			if (!placed.add(rune.getItemId()))
			{
				continue;
			}
			layout.setItemAtPos(rune.getItemId(), w * (invRow + 1 + r / 2) + pouchCol + r % 2);
			r++;
		}
		return layout;
	}

	/**
	 * Places one style's equipment starting at {@code base} (the first cell of a row). The
	 * worn slots form the 3-wide block; two-handed, special attack and any extra picks for an
	 * already filled slot go in columns 4-7 of the same rows.
	 */
	private static void placeEquipmentBlock(Layout layout, BuildResult result, String group, int base, Set<Integer> placed)
	{
		final int w = BankTagsPlugin.BANK_ITEMS_PER_ROW;
		int[] extraCells = new int[5 * 4];
		for (int row = 0, i = 0; row < 5; row++)
		{
			for (int col = 4; col < w; col++)
			{
				extraCells[i++] = base + w * row + col;
			}
		}
		int extra = 0;
		Set<EquipmentSlot> filled = new HashSet<>();

		// Note: Layout.setItemAtPos takes (itemId, pos)
		for (BuildResult.SlotPick pick : result.getPicks())
		{
			if (!pick.getGroup().equals(group) || !placed.add(pick.getItemId()))
			{
				continue;
			}
			int pos = filled.add(pick.getSlot()) ? equipmentCell(pick.getSlot(), w) : -1;
			if (pos < 0)
			{
				if (extra >= extraCells.length)
				{
					continue;
				}
				pos = extraCells[extra++];
			}
			else
			{
				pos += base;
			}
			layout.setItemAtPos(pick.getItemId(), pos);
		}
	}

	/** Grid cell for a worn slot, or -1 for slots that go in the spare columns. */
	private static int equipmentCell(EquipmentSlot slot, int w)
	{
		switch (slot)
		{
			case HEAD:
				return w * 0 + 1;
			case CAPE:
				return w * 1 + 0;
			case NECK:
				return w * 1 + 1;
			case AMMO:
				return w * 1 + 2;
			case WEAPON:
				return w * 2 + 0;
			case BODY:
				return w * 2 + 1;
			case SHIELD:
				return w * 2 + 2;
			case LEGS:
				return w * 3 + 1;
			case HANDS:
				return w * 4 + 0;
			case FEET:
				return w * 4 + 1;
			case RING:
				return w * 4 + 2;
			default:
				return -1;
		}
	}

	/**
	 * Items currently in the tag, as the ids Bank Tags stores them under.
	 */
	public List<Integer> currentItems(String tag)
	{
		return tagManager.getItemsForTag(sanitizeTagName(tag));
	}

	public void deleteTag(String tag)
	{
		tag = sanitizeTagName(tag);
		if (isBankOpen() && tag.equals(bankTagsService.getActiveTag()))
		{
			bankTagsService.closeBankTag();
		}
		tagManager.removeTag(tag);
		if (liveTabsLoaded())
		{
			tabManager.remove(tag);
			tabManager.save();
		}
		else
		{
			List<String> tabs = savedTabNames();
			if (tabs.remove(tag))
			{
				configManager.setConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_TABS_CONFIG, Text.toCSV(tabs));
			}
			configManager.unsetConfiguration(BankTagsPlugin.CONFIG_GROUP, BankTagsPlugin.TAG_ICON_PREFIX + tag);
		}
		layoutManager.removeLayout(tag);
	}

	/**
	 * The text Bank Tags' "Import tag tab" accepts. Negative ids tell it to match all
	 * variants of an item (charged, degraded, ornamented...); exact ids stay positive.
	 */
	public static String exportString(String tag, int iconItemId, List<Integer> itemIds, Set<Integer> exactIds)
	{
		List<String> data = new ArrayList<>();
		data.add("banktags");
		data.add("1");
		data.add(sanitizeTagName(tag));
		data.add(String.valueOf(Math.max(iconItemId, 0)));
		for (int id : itemIds)
		{
			data.add(String.valueOf(exactIds.contains(id) ? id : -id));
		}
		return Text.toCSV(data);
	}

	private static String nullToEmpty(String s)
	{
		return s == null ? "" : s;
	}
}
