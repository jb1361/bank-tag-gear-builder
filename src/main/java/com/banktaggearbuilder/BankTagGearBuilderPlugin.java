package com.banktaggearbuilder;

import com.google.inject.Provides;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Bank Tag Gear Builder",
	description = "Build and manage gear setups using bank tags",
	tags = {"bank", "tags", "gear", "equipment", "setup", "loadout"}
)
public class BankTagGearBuilderPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private BankTagGearBuilderConfig config;

	@Override
	protected void startUp() throws Exception
	{
		log.debug("Bank Tag Gear Builder started!");
	}

	@Override
	protected void shutDown() throws Exception
	{
		log.debug("Bank Tag Gear Builder stopped!");
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() == GameState.LOGGED_IN)
		{
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "Bank Tag Gear Builder says " + config.greeting(), null);
		}
	}

	@Provides
	BankTagGearBuilderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankTagGearBuilderConfig.class);
	}
}
