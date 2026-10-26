package com.banktaggearbuilder.ui;

import com.banktaggearbuilder.BankTagGearBuilderConfig;
import com.banktaggearbuilder.BankTagGearBuilderPlugin;
import com.banktaggearbuilder.BankTagWriter;
import com.banktaggearbuilder.model.BuildResult;
import com.banktaggearbuilder.model.ContentPage;
import com.banktaggearbuilder.model.GearSetup;
import com.banktaggearbuilder.model.GeneratedTag;
import com.banktaggearbuilder.model.StrategyPage;
import com.banktaggearbuilder.model.TagStatus;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.util.QuantityFormatter;

public class GearBuilderPanel extends PluginPanel
{
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final Color UPDATE_COLOR = ColorScheme.BRAND_ORANGE;
	private static final Color OK_COLOR = ColorScheme.PROGRESS_COMPLETE_COLOR;
	private static final Color MUTED = ColorScheme.LIGHT_GRAY_COLOR;

	private final BankTagGearBuilderPlugin plugin;
	private final ItemManager itemManager;
	private final BankTagGearBuilderConfig config;

	private final JPanel banner = new JPanel(new BorderLayout());
	/** Everything below the banner; hidden until the bank has been seen. */
	private final JPanel body = new JPanel();
	private final JLabel bannerLabel = new JLabel();
	private final JLabel statusLabel = new JLabel();
	private final IconTextField searchField = new IconTextField();
	private final DefaultListModel<ContentPage> listModel = new DefaultListModel<>();
	private final JList<ContentPage> contentList = new JList<>(listModel);
	private final JComboBox<GearSetup> setupCombo = new JComboBox<>();
	private final JLabel sourceLabel = new JLabel();
	private final JButton buildButton = new JButton("Build setup");
	private final JButton createButton = new JButton("Create tag");
	private final JButton copyButton = new JButton("Copy import string");
	private final JPanel actionRow = new JPanel(new GridLayout(1, 2, 4, 0));
	private final JPanel resultPanel = new JPanel();
	private final JPanel generatedPanel = new JPanel();
	private final JButton refreshButton = new JButton("Refresh all");
	private final JButton syncButton = new JButton("Sync from wiki");

	private List<ContentPage> allContent = new ArrayList<>();
	private boolean contentLoading;
	private ContentPage selectedContent;
	private List<TagStatus> currentStatuses = new ArrayList<>();
	private StrategyPage currentPage;
	private BuildResult currentResult;
	/** The setup {@link #currentResult} was built for. Create/Copy only act on this setup. */
	private GearSetup resultSetup;

	public GearBuilderPanel(BankTagGearBuilderPlugin plugin, ItemManager itemManager, BankTagGearBuilderConfig config)
	{
		super();
		this.plugin = plugin;
		this.itemManager = itemManager;
		this.config = config;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel container = new JPanel();
		container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
		container.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JLabel title = new JLabel("Bank Tag Gear Builder");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(Color.WHITE);
		container.add(title);
		container.add(Box.createVerticalStrut(6));

		// Large warning shown until the plugin has seen the bank. Nothing can be matched before then.
		banner.setBackground(ColorScheme.BRAND_ORANGE);
		banner.setBorder(new EmptyBorder(10, 10, 10, 10));
		bannerLabel.setFont(FontManager.getRunescapeBoldFont());
		bannerLabel.setForeground(Color.BLACK);
		banner.add(bannerLabel, BorderLayout.CENTER);
		banner.setVisible(false);
		container.add(banner);
		container.add(Box.createVerticalStrut(6));

		statusLabel.setFont(FontManager.getRunescapeSmallFont());
		statusLabel.setForeground(MUTED);
		container.add(statusLabel);
		container.add(Box.createVerticalStrut(8));

		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel contentHeader = new JPanel(new BorderLayout());
		contentHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
		contentHeader.add(header("1. Pick content"), BorderLayout.WEST);
		syncButton.setFont(FontManager.getRunescapeSmallFont());
		syncButton.setToolTipText("Download this page and the content list from the OSRS Wiki again instead of using the saved copies");
		syncButton.addActionListener(e -> confirmSync());
		// Shown once content is picked, since it syncs that page (along with the list)
		syncButton.setVisible(false);
		contentHeader.add(syncButton, BorderLayout.EAST);
		body.add(contentHeader);
		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setPreferredSize(new Dimension(PANEL_WIDTH - 20, 30));
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyReleased(KeyEvent e)
			{
				filterContent();
			}
		});
		searchField.addClearListener(this::filterContent);
		body.add(searchField);
		body.add(Box.createVerticalStrut(4));

		contentList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		contentList.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		contentList.setVisibleRowCount(8);
		contentList.addListSelectionListener(e ->
		{
			if (!e.getValueIsAdjusting() && contentList.getSelectedValue() != null)
			{
				selectContent(contentList.getSelectedValue());
			}
		});
		JScrollPane listScroll = new JScrollPane(contentList);
		listScroll.setPreferredSize(new Dimension(PANEL_WIDTH - 20, 160));
		listScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 160));
		body.add(listScroll);
		body.add(Box.createVerticalStrut(8));

		body.add(header("2. Choose setup"));
		setupCombo.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus)
			{
				super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
				if (value instanceof GearSetup)
				{
					setText(((GearSetup) value).getDisplayName());
				}
				return this;
			}
		});
		setupCombo.setEnabled(false);
		// A result belongs to one setup; changing the selection discards it until Build is pressed again
		setupCombo.addActionListener(e ->
		{
			if (resultSetup != null && resultSetup != selectedSetup())
			{
				clearResult();
			}
		});
		body.add(setupCombo);
		body.add(Box.createVerticalStrut(4));

		sourceLabel.setFont(FontManager.getRunescapeSmallFont());
		sourceLabel.setForeground(MUTED);
		setWrapped(sourceLabel, "Select content above");
		body.add(sourceLabel);
		body.add(Box.createVerticalStrut(4));

		buildButton.setEnabled(false);
		buildButton.addActionListener(e -> build());
		body.add(buildButton);
		body.add(Box.createVerticalStrut(8));

		body.add(header("3. Result"));
		resultPanel.setLayout(new BoxLayout(resultPanel, BoxLayout.Y_AXIS));
		resultPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.add(resultPanel);

		createButton.addActionListener(e -> doCreate());
		copyButton.addActionListener(e -> doCopy());
		actionRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
		actionRow.add(createButton);
		actionRow.add(copyButton);
		actionRow.setVisible(false);
		body.add(actionRow);
		body.add(Box.createVerticalStrut(12));

		JPanel genHeader = new JPanel(new BorderLayout());
		genHeader.setBackground(ColorScheme.DARK_GRAY_COLOR);
		genHeader.add(header("Generated tags"), BorderLayout.WEST);
		refreshButton.setFont(FontManager.getRunescapeSmallFont());
		refreshButton.setToolTipText("Re-fetch each tag's wiki page and re-check every tag against your bank");
		refreshButton.addActionListener(e ->
		{
			refreshButton.setEnabled(false);
			plugin.refreshSources(msg ->
			{
				refreshButton.setEnabled(true);
				setWrapped(statusLabel, msg);
			});
		});
		genHeader.add(refreshButton, BorderLayout.EAST);
		body.add(genHeader);
		body.add(Box.createVerticalStrut(4));

		generatedPanel.setLayout(new BoxLayout(generatedPanel, BoxLayout.Y_AXIS));
		generatedPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
		body.add(generatedPanel);

		leftAlign(body);
		container.add(body);

		leftAlign(container);
		add(container, BorderLayout.NORTH);
		onConfigChanged();
		updateStatusLabel();
	}

	/**
	 * BoxLayout positions each child by its own alignment hint, and buttons, combo boxes and
	 * panels default to centred, which reads as right-aligned next to left-aligned labels.
	 * Pin every child to the left edge and let it use the full panel width.
	 */
	private static void leftAlign(JPanel vertical)
	{
		for (Component c : vertical.getComponents())
		{
			if (c instanceof JComponent)
			{
				JComponent jc = (JComponent) c;
				jc.setAlignmentX(Component.LEFT_ALIGNMENT);
				jc.setMaximumSize(new Dimension(Integer.MAX_VALUE, jc.getMaximumSize().height));
			}
		}
	}

	@Override
	public void onActivate()
	{
		updateStatusLabel();
		if (allContent.isEmpty())
		{
			loadContentList(false);
		}
	}

	/**
	 * Fills the content list.
	 *
	 * @param force skip the saved list and download it from the wiki
	 */
	private void loadContentList(boolean force)
	{
		if (contentLoading)
		{
			return;
		}
		contentLoading = true;
		setWrapped(statusLabel, "Loading content list from the OSRS Wiki...");
		plugin.loadContent(force, pages ->
		{
			contentLoading = false;
			allContent = pages;
			filterContent();
			updateStatusLabel();
		}, err ->
		{
			contentLoading = false;
			setWrapped(statusLabel, err);
		});
	}

	public void onConfigChanged()
	{
		switch (config.outputMode())
		{
			case CREATE_TAG:
				buildButton.setText("Build and create tag");
				break;
			case COPY_IMPORT_STRING:
				buildButton.setText("Build and copy import string");
				break;
			default:
				buildButton.setText("Build setup");
				break;
		}
	}

	private void updateStatusLabel()
	{
		if (!plugin.isLoggedIn())
		{
			showBanner("LOG IN FIRST. Gear is matched against your bank, so the plugin needs you logged in.");
		}
		else if (!plugin.isBankSeen())
		{
			showBanner("OPEN YOUR BANK ONCE. The plugin only knows what you own after it has seen your bank this session. Builds and update checks wait until then.");
		}
		else
		{
			banner.setVisible(false);
			body.setVisible(true);
			if (plugin.isBankFromCache())
			{
				setWrapped(statusLabel, "Source: OSRS Wiki. Using your bank as saved on " + formatDate(plugin.getBankCacheSavedAt()) + ". Open the bank to refresh it.");
			}
			else
			{
				setWrapped(statusLabel, "Source: OSRS Wiki. Bank seen this session.");
			}
		}
		revalidate();
		repaint();
	}

	private void showBanner(String text)
	{
		setWrapped(bannerLabel, text);
		banner.setVisible(true);
		body.setVisible(false);
		setWrapped(statusLabel, "");
	}

	private void filterContent()
	{
		String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
		ContentPage selected = contentList.getSelectedValue();
		listModel.clear();
		for (ContentPage page : allContent)
		{
			if (q.isEmpty() || page.getDisplayName().toLowerCase(Locale.ROOT).contains(q))
			{
				listModel.addElement(page);
			}
		}
		if (selected != null && listModel.contains(selected))
		{
			contentList.setSelectedValue(selected, false);
		}
	}

	private void selectContent(ContentPage page)
	{
		if (page.equals(selectedContent))
		{
			// Re-selected after the list was filtered; nothing to fetch
			return;
		}
		selectedContent = page;
		syncButton.setVisible(true);
		loadPage(page, false);
	}

	private void confirmSync()
	{
		ContentPage page = selectedContent;
		if (page == null)
		{
			return;
		}
		int choice = JOptionPane.showConfirmDialog(this,
			"Download " + page.getDisplayName() + " and the content list from the OSRS Wiki again?" + System.lineSeparator()
				+ "The saved copies are replaced with the current wiki versions.",
			"Sync from wiki", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
		if (choice == JOptionPane.YES_OPTION)
		{
			loadPage(page, true);
			loadContentList(true);
		}
	}

	/**
	 * Fetches a page's setups and fills the setup picker.
	 *
	 * @param force skip the saved copy and download the page from the wiki
	 */
	private void loadPage(ContentPage page, boolean force)
	{
		currentPage = null;
		clearResult();
		setupCombo.setModel(new DefaultComboBoxModel<>());
		setupCombo.setEnabled(false);
		buildButton.setEnabled(false);
		syncButton.setEnabled(false);
		setWrapped(sourceLabel, (force ? "Syncing " : "Fetching ") + page.getDisplayName() + "...");

		plugin.fetchStrategy(page, force, sp ->
		{
			if (contentList.getSelectedValue() == null || !contentList.getSelectedValue().equals(page))
			{
				return;
			}
			currentPage = sp;
			DefaultComboBoxModel<GearSetup> model = new DefaultComboBoxModel<>();
			for (GearSetup s : sp.getSetups())
			{
				model.addElement(s);
			}
			setupCombo.setModel(model);
			setupCombo.setEnabled(true);
			buildButton.setEnabled(true);
			syncButton.setEnabled(true);
			setWrapped(sourceLabel, "Source: OSRS Wiki, pulled " + formatDate(sp.getFetchedAt()) + ". " + sp.getSetups().size() + " setup(s).");
		}, err ->
		{
			if (page.equals(selectedContent))
			{
				syncButton.setEnabled(true);
				setWrapped(sourceLabel, err);
			}
		});
	}

	private GearSetup selectedSetup()
	{
		Object s = setupCombo.getSelectedItem();
		return s instanceof GearSetup ? (GearSetup) s : null;
	}

	private void clearResult()
	{
		currentResult = null;
		resultSetup = null;
		actionRow.setVisible(false);
		resultPanel.removeAll();
		resultPanel.revalidate();
		resultPanel.repaint();
	}

	/** True if the shown result was built for the setup currently selected. */
	private boolean resultMatchesSelection()
	{
		GearSetup setup = selectedSetup();
		if (currentResult == null || resultSetup == null || setup != resultSetup)
		{
			clearResult();
			setWrapped(statusLabel, "Press Build for this setup first.");
			return false;
		}
		return true;
	}

	private void build()
	{
		GearSetup setup = selectedSetup();
		if (currentPage == null || setup == null)
		{
			return;
		}
		if (!plugin.isLoggedIn() || !plugin.isBankSeen())
		{
			// The banner already says what to do; make sure it is showing
			updateStatusLabel();
			return;
		}
		buildButton.setEnabled(false);
		setWrapped(statusLabel, "Matching against your bank...");
		plugin.build(currentPage.getPage(), setup, result ->
		{
			buildButton.setEnabled(true);
			if (selectedSetup() != setup)
			{
				// Selection changed while matching; this result is for a setup no longer chosen
				return;
			}
			currentResult = result;
			resultSetup = setup;
			createButton.setText(plugin.hasGeneratedTag(plugin.tagNameFor(currentPage.getPage(), setup)) ? "Update tag" : "Create tag");
			showResult(result);
			updateStatusLabel();
			switch (config.outputMode())
			{
				case CREATE_TAG:
					doCreate();
					break;
				case COPY_IMPORT_STRING:
					doCopy();
					break;
				default:
					break;
			}
		});
	}

	private void doCreate()
	{
		GearSetup setup = selectedSetup();
		if (currentPage == null || setup == null || !resultMatchesSelection())
		{
			return;
		}
		if (currentResult.getTagItemIds().isEmpty())
		{
			setWrapped(statusLabel, "Nothing to tag: you own none of the recommended items.");
			return;
		}
		String tag = plugin.tagNameFor(currentPage.getPage(), setup);
		for (TagStatus status : currentStatuses)
		{
			if (status.getTag().getTag().equals(tag) && status.isUserEdited())
			{
				int choice = JOptionPane.showConfirmDialog(this,
					"You edited the tag '" + tag + "' yourself in Bank Tags.\nUpdating it replaces your edits with this result. Continue?",
					"Overwrite your edits?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
				if (choice != JOptionPane.YES_OPTION)
				{
					return;
				}
			}
		}
		createButton.setEnabled(false);
		plugin.createTag(currentPage, setup, currentResult, created ->
		{
			createButton.setEnabled(true);
			setWrapped(statusLabel, "Created tag tab '" + created + "'. If the bank is open it shows the tag now; the tab itself appears next time the bank opens.");
		});
	}

	private void doCopy()
	{
		GearSetup setup = selectedSetup();
		if (currentPage == null || setup == null || !resultMatchesSelection())
		{
			return;
		}
		String tag = plugin.tagNameFor(currentPage.getPage(), setup);
		copyToClipboard(BankTagWriter.exportString(tag, currentResult.getIconItemId(), currentResult.getTagItemIds(), currentResult.getExactItemIds()));
		setWrapped(statusLabel, "Import string copied. In the bank, right-click the tag tab '+' and choose Import tag tab.");
	}

	private void copyToClipboard(String text)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
	}

	private void showResult(BuildResult result)
	{
		resultPanel.removeAll();

		List<String> groups = result.getGroups();
		for (String group : groups)
		{
			if (groups.size() > 1 || !group.isEmpty())
			{
				resultPanel.add(header(group.isEmpty() ? "Equipment" : group));
			}
			for (BuildResult.SlotPick pick : result.getPicks())
			{
				if (pick.getGroup().equals(group))
				{
					resultPanel.add(itemRow(pick.getItemId(), pick.getSlot().getDisplayName(), pick.getName() + "  (#" + pick.getRank() + ")", Color.WHITE));
				}
			}
			for (BuildResult.MissingSlot miss : result.getMissing())
			{
				if (!miss.getGroup().equals(group))
				{
					continue;
				}
				String detail = miss.getSuggestionName() == null
					? "none owned"
					: "none owned. Cheapest: " + miss.getSuggestionName()
					+ (miss.getSuggestionPrice() > 0 ? " (" + QuantityFormatter.quantityToStackSize(miss.getSuggestionPrice()) + " gp)" : "");
				resultPanel.add(itemRow(miss.getSuggestionItemId(), miss.getSlot().getDisplayName(), detail, MUTED));
			}
		}

		if (!result.getInventory().isEmpty())
		{
			resultPanel.add(Box.createVerticalStrut(4));
			resultPanel.add(header("Inventory"));
			for (BuildResult.InventoryPick pick : result.getInventory())
			{
				resultPanel.add(itemRow(pick.getItemId(), null, pick.getName(), Color.WHITE));
			}
		}
		if (!result.getRunePouch().isEmpty())
		{
			resultPanel.add(Box.createVerticalStrut(4));
			resultPanel.add(header("Rune pouch"));
			for (BuildResult.InventoryPick pick : result.getRunePouch())
			{
				resultPanel.add(itemRow(pick.getItemId(), null, pick.getName(), Color.WHITE));
			}
		}
		if (!result.getUnownedInventory().isEmpty())
		{
			resultPanel.add(smallText("Not owned: " + String.join(", ", result.getUnownedInventory()), MUTED));
		}
		if (!result.getUnresolvedNames().isEmpty())
		{
			resultPanel.add(smallText("Not recognised as items: " + String.join(", ", result.getUnresolvedNames()), MUTED));
		}
		if (result.getPicks().isEmpty() && result.getInventory().isEmpty())
		{
			resultPanel.add(smallText("You own none of the recommended items for this setup.", MUTED));
		}

		actionRow.setVisible(true);
		leftAlign(resultPanel);
		resultPanel.revalidate();
		resultPanel.repaint();
	}

	public void setStatuses(List<TagStatus> statuses)
	{
		currentStatuses = statuses;
		generatedPanel.removeAll();
		if (statuses.isEmpty())
		{
			generatedPanel.add(smallText("No tags generated yet.", MUTED));
		}
		for (TagStatus status : statuses)
		{
			generatedPanel.add(statusRow(status));
			generatedPanel.add(Box.createVerticalStrut(4));
		}
		leftAlign(generatedPanel);
		generatedPanel.revalidate();
		generatedPanel.repaint();
		updateStatusLabel();
	}

	private JPanel statusRow(TagStatus status)
	{
		GeneratedTag g = status.getTag();
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.Y_AXIS));
		row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		row.setBorder(new EmptyBorder(6, 6, 6, 6));

		JPanel top = new JPanel(new BorderLayout(4, 0));
		top.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		if (g.getIconItemId() > 0)
		{
			itemManager.getImage(g.getIconItemId()).addTo(icon);
		}
		top.add(icon, BorderLayout.WEST);
		JLabel name = new JLabel("<html><b>" + escape(g.getTag()) + "</b><br>"
			+ "<span style='color:#a0a0a0'>OSRS Wiki, pulled " + formatDate(g.getFetchedAt())
			+ (g.isSourceChanged() ? ". Wiki page updated" : "") + "</span></html>");
		name.setFont(FontManager.getRunescapeSmallFont());
		name.setForeground(Color.WHITE);
		top.add(name, BorderLayout.CENTER);
		row.add(top);

		if (status.isUserEdited())
		{
			row.add(smallText("Edited by you in Bank Tags", UPDATE_COLOR));
		}
		if (status.isUpdateAvailable())
		{
			row.add(smallText("Update available from your last build", UPDATE_COLOR));
			for (String change : status.getChanges())
			{
				row.add(smallText("  " + change, Color.WHITE));
			}
		}

		JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
		buttons.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		if (status.isUpdateAvailable())
		{
			JButton apply = smallButton("Apply update");
			apply.setToolTipText("Write the result of your last build to this tag");
			apply.addActionListener(e ->
			{
				if (status.isUserEdited())
				{
					int choice = JOptionPane.showConfirmDialog(this,
						"You edited the tag '" + g.getTag() + "' yourself in Bank Tags.\nApplying replaces your edits with the built result. Continue?",
						"Overwrite your edits?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
					if (choice != JOptionPane.YES_OPTION)
					{
						return;
					}
				}
				plugin.applyUpdate(g.getTag());
			});
			buttons.add(apply);
		}
		JButton copy = smallButton("Copy string");
		copy.addActionListener(e ->
		{
			copyToClipboard(BankTagWriter.exportString(g.getTag(), g.getIconItemId(), g.getItemIds(), new HashSet<>(g.getExactItemIds() == null ? Collections.emptyList() : g.getExactItemIds())));
			setWrapped(statusLabel, "Import string for '" + g.getTag() + "' copied.");
		});
		buttons.add(copy);
		JButton remove = smallButton("Remove");
		remove.addActionListener(e ->
		{
			Object[] options = {"Remove tag from bank too", "Stop tracking only", "Cancel"};
			int choice = JOptionPane.showOptionDialog(this,
				"Stop tracking '" + g.getTag() + "'?",
				"Remove generated tag", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[2]);
			if (choice == 0 || choice == 1)
			{
				plugin.removeGenerated(g.getTag(), choice == 0);
			}
		});
		buttons.add(remove);
		row.add(buttons);
		leftAlign(row);
		return row;
	}

	private JPanel itemRow(int itemId, String slot, String text, Color color)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(1, 0, 1, 0));
		JLabel icon = new JLabel();
		icon.setPreferredSize(new Dimension(36, 32));
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		if (itemId > 0)
		{
			itemManager.getImage(itemId).addTo(icon);
		}
		row.add(icon, BorderLayout.WEST);
		String html = "<html>" + (slot != null ? "<span style='color:#a0a0a0'>" + escape(slot) + "</span><br>" : "") + escape(text) + "</html>";
		JLabel label = new JLabel(html);
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		row.add(label, BorderLayout.CENTER);
		return row;
	}

	private static JLabel header(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(FontManager.getRunescapeBoldFont());
		label.setForeground(ColorScheme.BRAND_ORANGE);
		label.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		return label;
	}

	private static JLabel smallText(String text, Color color)
	{
		JLabel label = new JLabel("<html>" + escape(text) + "</html>");
		label.setFont(FontManager.getRunescapeSmallFont());
		label.setForeground(color);
		return label;
	}

	private static JButton smallButton(String text)
	{
		JButton button = new JButton(text);
		button.setFont(FontManager.getRunescapeSmallFont());
		button.setFocusPainted(false);
		return button;
	}

	/** Wraps text in HTML so long messages wrap inside the narrow side panel. */
	private static void setWrapped(JLabel label, String text)
	{
		label.setText("<html>" + escape(text) + "</html>");
	}

	private static String formatDate(long epochMillis)
	{
		if (epochMillis <= 0)
		{
			return "unknown date";
		}
		return DATE.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
