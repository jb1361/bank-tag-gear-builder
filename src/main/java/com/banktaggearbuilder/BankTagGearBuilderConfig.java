package com.banktaggearbuilder;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(BankTagGearBuilderConfig.GROUP)
public interface BankTagGearBuilderConfig extends Config
{
	String GROUP = "bank-tag-gear-builder";

	enum OutputMode
	{
		ASK("Ask each time"),
		CREATE_TAG("Create tag automatically"),
		COPY_IMPORT_STRING("Copy import string");

		private final String label;

		OutputMode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum UpdateMode
	{
		NOTIFY("Notify only"),
		AUTO_UPDATE("Auto-update"),
		OFF("Off");

		private final String label;

		UpdateMode(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	enum TagNaming
	{
		CONTENT_AND_STYLE("Content + style"),
		CONTENT("Content name");

		private final String label;

		TagNaming(String label)
		{
			this.label = label;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	@ConfigItem(
		keyName = "outputMode",
		name = "Output",
		description = "What to do with a built setup: create the Bank Tags tag, copy an import string, or show both options",
		position = 1
	)
	default OutputMode outputMode()
	{
		return OutputMode.ASK;
	}

	@ConfigItem(
		keyName = "updateMode",
		name = "When bank changes",
		description = "Auto-update rebuilds generated tags silently. Notify only marks them 'Update available' in the side panel.",
		position = 2
	)
	default UpdateMode updateMode()
	{
		return UpdateMode.NOTIFY;
	}

	@ConfigItem(
		keyName = "includeInventory",
		name = "Include inventory items",
		description = "Add the wiki's recommended inventory (potions, food, runes, teleports) to the tag",
		position = 3
	)
	default boolean includeInventory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "matchEquippedAndInventory",
		name = "Count equipped and inventory items",
		description = "Treat items you are wearing or carrying as owned, not only items in the bank",
		position = 4
	)
	default boolean matchEquippedAndInventory()
	{
		return true;
	}

	@ConfigItem(
		keyName = "tagNaming",
		name = "Tag naming",
		description = "Name tags after the content only, or the content and the setup style (e.g. 'vorkath - ranged')",
		position = 5
	)
	default TagNaming tagNaming()
	{
		return TagNaming.CONTENT_AND_STYLE;
	}

	@ConfigItem(
		keyName = "arrangeBySlot",
		name = "Arrange items by slot",
		description = "Give created tags a Bank Tags layout shaped like the equipment screen, with special attack weapons beside it and inventory items on the rows below",
		position = 6
	)
	default boolean arrangeBySlot()
	{
		return true;
	}
}
