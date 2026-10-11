package com.banktaggearbuilder;

import com.banktaggearbuilder.model.BuildResult;
import com.banktaggearbuilder.model.ContentPage;
import com.banktaggearbuilder.model.GearSetup;
import com.banktaggearbuilder.model.GeneratedTag;
import com.banktaggearbuilder.model.StrategyPage;
import com.banktaggearbuilder.model.TagStatus;
import com.banktaggearbuilder.ui.GearBuilderPanel;
import com.banktaggearbuilder.wiki.WikiClient;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDependency;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.banktags.BankTagsPlugin;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

@Slf4j
@PluginDescriptor(
	name = "Bank Tag Gear Builder",
	description = "Builds a Bank Tags tag for a boss or activity from the OSRS Wiki's recommended gear, using only items you own",
	tags = {"bank", "tags", "gear", "equipment", "setup", "loadout", "wiki", "boss"}
)
@PluginDependency(BankTagsPlugin.class)
public class BankTagGearBuilderPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private BankTagGearBuilderConfig config;

	@Inject
	private WikiClient wikiClient;

	@Inject
	private ItemNameIndex itemNameIndex;

	@Inject
	private OwnedItems ownedItems;

	@Inject
	private GearMatcher matcher;

	@Inject
	private BankTagWriter writer;

	@Inject
	private GeneratedTagStore store;

	/** RS-profile config key holding the cached bank, so each account keeps its own. */
	private static final String BANK_CACHE_KEY = "bankCache";

	@Inject
	private ConfigManager configManager;

	private GearBuilderPanel panel;
	private NavigationButton navButton;
	/** Set once a bank cache lookup found a profile to read from, so ticks stop retrying. */
	private boolean bankCacheChecked;

	/** Tags this plugin created. Only touched on the client thread after startUp. */
	private final List<GeneratedTag> generated = new ArrayList<>();

	@Override
	protected void startUp()
	{
		panel = new GearBuilderPanel(this, itemManager, config);
		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Bank Tag Gear Builder")
			.icon(icon)
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		clientThread.invoke(() ->
		{
			generated.clear();
			generated.addAll(store.loadAll());
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				itemNameIndex.ensureBuilt(this::recheckAll);
				ownedItems.update(InventoryID.INV, client.getItemContainer(InventoryID.INV));
				ownedItems.update(InventoryID.WORN, client.getItemContainer(InventoryID.WORN));
			}
			recheckAll();
		});
	}

	@Override
	protected void shutDown()
	{
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		panel = null;
		ownedItems.clear();
	}

	@Provides
	BankTagGearBuilderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BankTagGearBuilderConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			bankCacheChecked = false;
			itemNameIndex.ensureBuilt(this::recheckAll);
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			ownedItems.clear();
			bankCacheChecked = false;
			recheckAll();
		}
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		// The RS profile can become available a little after LOGGED_IN; keep trying until it does
		if (!bankCacheChecked && !ownedItems.isBankSeen())
		{
			loadBankCache();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		boolean firstBankSight = event.getContainerId() == InventoryID.BANK
			&& (!ownedItems.isBankSeen() || ownedItems.isBankFromCache());
		if (ownedItems.update(event.getContainerId(), event.getItemContainer()) && firstBankSight)
		{
			saveBankCache();
			recheckAll();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed event)
	{
		if (event.getGroupId() == InterfaceID.BANKMAIN)
		{
			saveBankCache();
			recheckAll();
		}
	}

	private void saveBankCache()
	{
		if (!ownedItems.isBankSeen() || ownedItems.isBankFromCache() || configManager.getRSProfileKey() == null)
		{
			return;
		}
		configManager.setRSProfileConfiguration(BankTagGearBuilderConfig.GROUP, BANK_CACHE_KEY, ownedItems.serializeBank());
	}

	/**
	 * @return true if the bank is known, live or from cache
	 */
	private boolean loadBankCache()
	{
		if (ownedItems.isBankSeen())
		{
			return true;
		}
		if (client.getGameState() != GameState.LOGGED_IN || configManager.getRSProfileKey() == null)
		{
			return false;
		}
		bankCacheChecked = true;
		String cached = configManager.getRSProfileConfiguration(BankTagGearBuilderConfig.GROUP, BANK_CACHE_KEY);
		if (ownedItems.loadBank(cached))
		{
			log.debug("Loaded bank cache saved at {}", ownedItems.getCacheSavedAt());
			recheckAll();
			return true;
		}
		return false;
	}

	public boolean isBankFromCache()
	{
		return ownedItems.isBankFromCache();
	}

	public long getBankCacheSavedAt()
	{
		return ownedItems.getCacheSavedAt();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		// Ignore our own tag_ and bank cache writes, otherwise every save would trigger another re-check
		if (BankTagGearBuilderConfig.GROUP.equals(event.getGroup())
			&& !event.getKey().startsWith(GeneratedTagStore.KEY_PREFIX)
			&& !event.getKey().equals(BANK_CACHE_KEY))
		{
			clientThread.invoke(this::recheckAll);
			GearBuilderPanel p = panel;
			if (p != null)
			{
				SwingUtilities.invokeLater(p::onConfigChanged);
			}
		}
	}

	// ---- Called from the side panel (Swing thread). Callbacks are delivered on the Swing thread. ----

	public void loadContent(Consumer<List<ContentPage>> onSuccess, Consumer<String> onError)
	{
		wikiClient.listContent(
			pages -> SwingUtilities.invokeLater(() -> onSuccess.accept(pages)),
			err -> SwingUtilities.invokeLater(() -> onError.accept(err)));
	}

	public void fetchStrategy(ContentPage page, Consumer<StrategyPage> onSuccess, Consumer<String> onError)
	{
		wikiClient.fetchStrategy(page,
			sp -> SwingUtilities.invokeLater(() -> onSuccess.accept(sp)),
			err -> SwingUtilities.invokeLater(() -> onError.accept(err)));
	}

	public boolean isBankSeen()
	{
		return ownedItems.isBankSeen();
	}

	public boolean isLoggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN;
	}

	public void build(GearSetup setup, Consumer<BuildResult> onResult)
	{
		itemNameIndex.ensureBuilt(() ->
		{
			BuildResult result = matcher.match(setup, config.matchEquippedAndInventory(), config.includeInventory());
			SwingUtilities.invokeLater(() -> onResult.accept(result));
		});
	}

	public String tagNameFor(ContentPage page, GearSetup setup)
	{
		String name = page.getDisplayName();
		if (config.tagNaming() == BankTagGearBuilderConfig.TagNaming.CONTENT_AND_STYLE && !setup.getStyle().isEmpty())
		{
			name += " - " + setup.getStyle();
		}
		return BankTagWriter.sanitizeTagName(name);
	}

	public void createTag(StrategyPage page, GearSetup setup, BuildResult result, Consumer<String> onDone)
	{
		String tag = tagNameFor(page.getPage(), setup);
		clientThread.invoke(() ->
		{
			GeneratedTag existing = findGenerated(tag);
			GeneratedTag g = existing != null ? existing : new GeneratedTag();
			g.setTag(tag);
			g.setPageTitle(page.getPage().getTitle());
			g.setDisplayName(page.getPage().getDisplayName());
			g.setStyle(setup.getStyle());
			g.setRevId(page.getRevId());
			g.setFetchedAt(page.getFetchedAt());
			g.setSetup(setup);
			applyResult(g, result, existing != null ? existing.getItemIds() : null);
			if (existing == null)
			{
				generated.add(g);
			}
			chat("Created tag tab '" + tag + "' with " + result.getTagItemIds().size() + " items."
				+ (writer.isBankOpen() ? " It is open now; the tab appears in the strip next time you open the bank." : ""));
			recheckAll();
			SwingUtilities.invokeLater(() -> onDone.accept(tag));
		});
	}

	public void applyUpdate(String tag)
	{
		clientThread.invoke(() ->
		{
			GeneratedTag g = findGenerated(tag);
			if (g == null || !itemNameIndex.isReady())
			{
				return;
			}
			BuildResult result = matcher.match(g.getSetup(), config.matchEquippedAndInventory(), config.includeInventory());
			List<String> changes = diff(g, result);
			applyResult(g, result, g.getItemIds());
			chat("Updated tag '" + g.getTag() + "': " + String.join("; ", changes));
			recheckAll();
		});
	}

	public void removeGenerated(String tag, boolean deleteBankTag)
	{
		clientThread.invoke(() ->
		{
			GeneratedTag g = findGenerated(tag);
			if (g == null)
			{
				return;
			}
			if (deleteBankTag)
			{
				writer.deleteTag(g.getTag());
			}
			store.remove(g.getTag());
			generated.remove(g);
			recheckAll();
		});
	}

	/**
	 * Re-fetches every generated tag's wiki page, replacing the stored setup if the page changed,
	 * then re-checks all tags.
	 */
	public void refreshSources(Consumer<String> onStatus)
	{
		clientThread.invoke(() ->
		{
			if (generated.isEmpty())
			{
				SwingUtilities.invokeLater(() -> onStatus.accept("No generated tags to refresh."));
				return;
			}
			List<GeneratedTag> snapshot = new ArrayList<>(generated);
			AtomicInteger remaining = new AtomicInteger(snapshot.size());
			AtomicInteger changed = new AtomicInteger();
			Runnable finish = () ->
			{
				if (remaining.decrementAndGet() == 0)
				{
					int n = changed.get();
					clientThread.invoke(this::recheckAll);
					SwingUtilities.invokeLater(() -> onStatus.accept(n == 0 ? "All wiki pages unchanged." : n + " wiki page(s) changed."));
				}
			};
			for (GeneratedTag g : snapshot)
			{
				wikiClient.fetchStrategy(new ContentPage(g.getPageTitle()), sp -> clientThread.invoke(() ->
				{
					if (sp.getRevId() != g.getRevId())
					{
						GearSetup match = pickSetup(sp.getSetups(), g.getStyle());
						g.setSetup(match);
						g.setStyle(match.getStyle());
						g.setRevId(sp.getRevId());
						g.setFetchedAt(sp.getFetchedAt());
						g.setSourceChanged(true);
						store.save(g);
						changed.incrementAndGet();
					}
					finish.run();
				}), err ->
				{
					log.debug("Refresh failed for {}: {}", g.getPageTitle(), err);
					finish.run();
				});
			}
		});
	}

	// ---- Client thread ----

	private GeneratedTag findGenerated(String tag)
	{
		for (GeneratedTag g : generated)
		{
			if (g.getTag().equals(tag))
			{
				return g;
			}
		}
		return null;
	}

	private static GearSetup pickSetup(List<GearSetup> setups, String style)
	{
		String want = style == null ? "" : style.toLowerCase(Locale.ROOT);
		for (GearSetup s : setups)
		{
			if (s.getStyle().toLowerCase(Locale.ROOT).equals(want))
			{
				return s;
			}
		}
		return setups.get(0);
	}

	private void applyResult(GeneratedTag g, BuildResult result, List<Integer> previousItemIds)
	{
		List<Integer> ids = result.getTagItemIds();
		Set<Integer> exact = result.getExactItemIds();
		int icon = result.getIconItemId() > 0 ? result.getIconItemId() : g.getIconItemId();
		writer.writeTag(g.getTag(), ids, exact, icon, previousItemIds, config.arrangeBySlot(), result);
		g.setItemIds(ids);
		g.setExactItemIds(new ArrayList<>(exact));
		g.setIconItemId(icon);
		Map<String, String> picks = new LinkedHashMap<>();
		GearMatcher.summarize(result, picks);
		for (BuildResult.InventoryPick pick : result.getInventory())
		{
			picks.put("inv:" + pick.getName(), pick.getName());
		}
		g.setPicks(picks);
		g.setSourceChanged(false);
		store.save(g);
	}

	/**
	 * Human readable differences between what the tag holds and what the bank now supports.
	 */
	private static List<String> diff(GeneratedTag g, BuildResult result)
	{
		List<String> changes = new ArrayList<>();
		Map<String, String> now = new LinkedHashMap<>();
		GearMatcher.summarize(result, now);
		Map<String, String> before = g.getPicks() == null ? Collections.emptyMap() : g.getPicks();

		Set<String> slots = new LinkedHashSet<>();
		for (String k : before.keySet())
		{
			if (!k.startsWith("inv:"))
			{
				slots.add(k);
			}
		}
		slots.addAll(now.keySet());
		for (String slot : slots)
		{
			String old = before.get(slot);
			String cur = now.get(slot);
			if (old == null && cur != null)
			{
				changes.add(slot + ": " + cur + " added");
			}
			else if (old != null && cur == null)
			{
				changes.add(slot + ": " + old + " no longer owned");
			}
			else if (old != null && !old.equals(cur))
			{
				changes.add(slot + ": " + cur + " replaced " + old);
			}
		}

		Set<String> oldInv = new LinkedHashSet<>();
		for (String k : before.keySet())
		{
			if (k.startsWith("inv:"))
			{
				oldInv.add(k.substring(4));
			}
		}
		Set<String> newInv = new LinkedHashSet<>();
		for (BuildResult.InventoryPick pick : result.getInventory())
		{
			newInv.add(pick.getName());
		}
		for (String name : newInv)
		{
			if (!oldInv.contains(name))
			{
				changes.add("Inventory: " + name + " added");
			}
		}
		for (String name : oldInv)
		{
			if (!newInv.contains(name))
			{
				changes.add("Inventory: " + name + " no longer owned");
			}
		}
		return changes;
	}

	private void recheckAll()
	{
		if (!ownedItems.isBankSeen() && !bankCacheChecked && client.getGameState() == GameState.LOGGED_IN && configManager.getRSProfileKey() != null)
		{
			// loadBankCache re-enters recheckAll when a cache is found
			if (loadBankCache())
			{
				return;
			}
		}

		List<TagStatus> statuses = new ArrayList<>();
		boolean canMatch = itemNameIndex.isReady() && ownedItems.isBankSeen() && client.getGameState() == GameState.LOGGED_IN;

		for (GeneratedTag g : generated)
		{
			if (!canMatch)
			{
				statuses.add(new TagStatus(g, null, false, Collections.emptyList()));
				continue;
			}

			BuildResult result = matcher.match(g.getSetup(), config.matchEquippedAndInventory(), config.includeInventory());
			Set<Integer> current = new HashSet<>(writer.currentItems(g.getTag()));
			boolean userEdited = !current.equals(new HashSet<>(g.getItemIds()));
			List<String> changes = diff(g, result);

			if (!changes.isEmpty())
			{
				switch (config.updateMode())
				{
					case AUTO_UPDATE:
						if (!userEdited)
						{
							applyResult(g, result, g.getItemIds());
							chat(g.getTag() + ": " + String.join("; ", changes));
							changes = Collections.emptyList();
						}
						break;
					case OFF:
						changes = Collections.emptyList();
						break;
					default:
						break;
				}
			}
			statuses.add(new TagStatus(g, result, userEdited, changes));
		}

		GearBuilderPanel p = panel;
		if (p != null)
		{
			SwingUtilities.invokeLater(() -> p.setStatuses(statuses));
		}
	}

	private void chat(String message)
	{
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.CONSOLE)
			.runeLiteFormattedMessage(new ChatMessageBuilder()
				.append(ChatColorType.HIGHLIGHT)
				.append("Gear Builder: ")
				.append(ChatColorType.NORMAL)
				.append(message)
				.build())
			.build());
	}
}
