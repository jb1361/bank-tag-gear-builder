package com.banktaggearbuilder;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BankTagGearBuilderPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BankTagGearBuilderPlugin.class);
		RuneLite.main(args);
	}
}
