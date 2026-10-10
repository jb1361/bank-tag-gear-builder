package com.banktaggearbuilder;

import com.banktaggearbuilder.model.GeneratedTag;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.config.ConfigManager;

/**
 * Persists the tags this plugin generated in the plugin's config group, one JSON entry per tag.
 */
@Slf4j
@Singleton
public class GeneratedTagStore
{
	static final String KEY_PREFIX = "tag_";

	private final ConfigManager configManager;
	private final Gson gson;

	@Inject
	private GeneratedTagStore(ConfigManager configManager, Gson gson)
	{
		this.configManager = configManager;
		this.gson = gson;
	}

	public List<GeneratedTag> loadAll()
	{
		List<GeneratedTag> tags = new ArrayList<>();
		String prefix = BankTagGearBuilderConfig.GROUP + "." + KEY_PREFIX;
		for (String fullKey : configManager.getConfigurationKeys(prefix))
		{
			String key = fullKey.substring(BankTagGearBuilderConfig.GROUP.length() + 1);
			String json = configManager.getConfiguration(BankTagGearBuilderConfig.GROUP, key);
			if (json == null)
			{
				continue;
			}
			try
			{
				GeneratedTag tag = gson.fromJson(json, GeneratedTag.class);
				if (tag != null && tag.getTag() != null && tag.getSetup() != null)
				{
					tags.add(tag);
				}
			}
			catch (Exception e)
			{
				log.debug("Dropping unreadable generated tag {}", key, e);
				configManager.unsetConfiguration(BankTagGearBuilderConfig.GROUP, key);
			}
		}
		tags.sort((a, b) -> a.getTag().compareToIgnoreCase(b.getTag()));
		return tags;
	}

	public void save(GeneratedTag tag)
	{
		configManager.setConfiguration(BankTagGearBuilderConfig.GROUP, KEY_PREFIX + tag.getTag(), gson.toJson(tag));
	}

	public void remove(String tag)
	{
		configManager.unsetConfiguration(BankTagGearBuilderConfig.GROUP, KEY_PREFIX + tag);
	}
}
