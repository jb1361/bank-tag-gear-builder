package com.banktaggearbuilder.model;

import java.util.List;
import lombok.Value;

/**
 * The parsed result of fetching a strategy page from the wiki.
 */
@Value
public class StrategyPage
{
	ContentPage page;
	long revId;
	long fetchedAt;
	List<GearSetup> setups;
}
