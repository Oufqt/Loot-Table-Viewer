package com.loottableviewer;

import com.loottableviewer.model.DropEntry;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import net.runelite.api.ItemID;
import net.runelite.http.api.item.ItemPrice;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;

public class LootTableViewerPanel extends PluginPanel
{
    private static final int MAX_HISTORY_ENTRIES = 20;
    private static final int ITEMS_PER_ROW = 5;
    private static final int SLOT_SIZE = 42;
    private static final int POTENTIAL_ROW_HEIGHT = 56;
    private static final int POTENTIAL_ICON_SLOT_SIZE = 44;
    private static final int SCROLLBAR_WIDTH = 10;
    private static final int MAX_ICON_CACHE_SIZE = 512;
    private static final int TITLE_PADDING = 5;
    private static final String CONFIG_GROUP = "loottableviewer";
    private static final String UPDATE_NOTICE_KEY = "updateNoticeVersion";
    private static final String UPDATE_NOTICE_VERSION = "2026-05-30-performance-icons-prefetch";
    private static final String EXPANDED_ARROW = "\u25BE";
    private static final String COLLAPSED_ARROW = "\u25B8";
    private static final String PLUGIN_NAME = "Loot Table Viewer";
    private static final Icon NOTHING_ICON = new NothingIcon();

    private final ItemManager itemManager;
    private final ConfigManager configManager;

    private final JLabel sourceLabel = new JLabel(PLUGIN_NAME);
    private final JLabel wikiLinkLabel = new JLabel();
    private final JPanel headerPanel;
    private final JPanel contentPanel = new ScrollableContentPanel();
    private final List<LootHistoryEntry> historyEntries = new ArrayList<>();
    private final Map<String, Boolean> expandedCategories = new HashMap<>();
    private final Map<String, Boolean> expandedPotentialTables = new HashMap<>();
    private final Map<String, Integer> itemIdsByName = new HashMap<>();
    private final Map<String, AsyncBufferedImage> itemImageCache = new LinkedHashMap<String, AsyncBufferedImage>(128, 0.75f, true)
    {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AsyncBufferedImage> eldest)
        {
            return size() > MAX_ICON_CACHE_SIZE;
        }
    };
    private final Map<String, PendingLookupUpdate> pendingLookupUpdates = new HashMap<>();
    private String selectedSourceKey;
    private boolean renderQueued;
    private boolean updateNoticeVisible;

    @Inject
    public LootTableViewerPanel(ItemManager itemManager, ConfigManager configManager)
    {
        super(false);
        this.itemManager = itemManager;
        this.configManager = configManager;
        this.updateNoticeVisible = shouldShowUpdateNotice();

        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);

        headerPanel = buildHeader();
        add(headerPanel, BorderLayout.NORTH);
        add(buildContentScrollPane(), BorderLayout.CENTER);

        showEmptyState();
    }

    private JPanel buildHeader()
    {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARK_GRAY_COLOR);
        header.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));

        sourceLabel.setHorizontalAlignment(SwingConstants.LEFT);
        sourceLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(java.awt.Font.BOLD));
        sourceLabel.setForeground(ColorScheme.BRAND_ORANGE);
        wikiLinkLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        header.add(sourceLabel, BorderLayout.WEST);
        header.add(wikiLinkLabel, BorderLayout.EAST);
        return header;
    }

    private JScrollPane buildContentScrollPane()
    {
        contentPanel.setLayout(new BoxLayout(contentPanel, BoxLayout.Y_AXIS));
        contentPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        contentPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 4));

        JScrollPane scrollPane = new JScrollPane(contentPanel);
        scrollPane.setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setViewportBorder(BorderFactory.createEmptyBorder());
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.getViewport().setOpaque(true);

        JScrollBar verticalBar = scrollPane.getVerticalScrollBar();
        verticalBar.setUnitIncrement(16);
        verticalBar.setBlockIncrement(96);
        verticalBar.setPreferredSize(new Dimension(SCROLLBAR_WIDTH, 0));
        verticalBar.setBorder(BorderFactory.createEmptyBorder());
        verticalBar.setOpaque(false);
        verticalBar.setUI(new RuneLiteScrollBarUI());
        return scrollPane;
    }

    public void addHistoryEntry(LootHistoryEntry entry)
    {
        if (entry == null)
        {
            return;
        }

        String sourceKey = entry.getSourceKey();
        selectedSourceKey = sourceKey;
        expandedPotentialTables.putIfAbsent(sourceKey, Boolean.TRUE);
        for (int i = 0; i < historyEntries.size(); i++)
        {
            LootHistoryEntry existing = historyEntries.get(i);
            if (existing.getSourceKey().equals(sourceKey))
            {
                historyEntries.remove(i);
                LootHistoryEntry mergedEntry = existing.withMergedLoot(entry);
                historyEntries.add(0, applyPendingLookupUpdate(sourceKey, mergedEntry));
                requestRender();
                return;
            }
        }

        historyEntries.add(0, applyPendingLookupUpdate(sourceKey, entry));

        while (historyEntries.size() > MAX_HISTORY_ENTRIES)
        {
            LootHistoryEntry removed = historyEntries.remove(historyEntries.size() - 1);
            expandedCategories.keySet().removeIf(key -> key.startsWith(removed.getSourceKey() + ":"));
            expandedPotentialTables.remove(removed.getSourceKey());
            if (removed.getSourceKey().equals(selectedSourceKey))
            {
                selectedSourceKey = historyEntries.isEmpty() ? null : historyEntries.get(0).getSourceKey();
            }
        }

        requestRender();
    }

    public void updateSourceEntry(String sourceKey, String wikiPageTitle, String wikiUrl, List<ReceivedLootRow> receivedLoot, List<DropEntry> lookupResult, String potentialStatus)
    {
        String safeSourceKey = sourceKey == null ? "" : sourceKey;
        for (int i = 0; i < historyEntries.size(); i++)
        {
            LootHistoryEntry entry = historyEntries.get(i);
            if (entry.getSourceKey().equals(safeSourceKey))
            {
                historyEntries.set(i, entry.withLookupUpdate(wikiPageTitle, wikiUrl, receivedLoot, lookupResult, potentialStatus));
                requestRender();
                return;
            }
        }

        pendingLookupUpdates.put(safeSourceKey, new PendingLookupUpdate(
            wikiPageTitle,
            wikiUrl,
            receivedLoot,
            lookupResult,
            potentialStatus
        ));
    }

    public void updateHistoryEntry(long historyId, String wikiPageTitle, String wikiUrl, List<ReceivedLootRow> receivedLoot, List<DropEntry> lookupResult, String potentialStatus)
    {
        for (int i = 0; i < historyEntries.size(); i++)
        {
            LootHistoryEntry entry = historyEntries.get(i);
            if (entry.getHistoryId() == historyId)
            {
                historyEntries.set(i, entry.withLookupUpdate(wikiPageTitle, wikiUrl, receivedLoot, lookupResult, potentialStatus));
                requestRender();
                return;
            }
        }
    }

    private LootHistoryEntry applyPendingLookupUpdate(String sourceKey, LootHistoryEntry entry)
    {
        PendingLookupUpdate pendingUpdate = pendingLookupUpdates.remove(sourceKey);
        if (pendingUpdate == null)
        {
            return entry;
        }

        return entry.withLookupUpdate(
            pendingUpdate.wikiPageTitle,
            pendingUpdate.wikiUrl,
            pendingUpdate.receivedLoot,
            pendingUpdate.lookupResult,
            pendingUpdate.potentialStatus
        );
    }

    private void removeSourceEntry(String sourceKey)
    {
        if (sourceKey == null)
        {
            return;
        }

        historyEntries.removeIf(entry -> entry.getSourceKey().equals(sourceKey));
        expandedCategories.keySet().removeIf(key -> key.startsWith(sourceKey + ":"));
        expandedPotentialTables.remove(sourceKey);
        if (sourceKey.equals(selectedSourceKey))
        {
            selectedSourceKey = historyEntries.isEmpty() ? null : historyEntries.get(0).getSourceKey();
        }

        if (historyEntries.isEmpty())
        {
            showEmptyState();
            return;
        }

        requestRender();
    }

    public void clearHistory()
    {
        historyEntries.clear();
        expandedCategories.clear();
        expandedPotentialTables.clear();
        pendingLookupUpdates.clear();
        selectedSourceKey = null;
        showEmptyState();
    }

    public void updateDisplay(String sourceName, String wikiUrl, List<ReceivedLootRow> receivedLoot, List<DropEntry> lookupResult, String potentialStatus)
    {
        historyEntries.clear();
        addHistoryEntry(new LootHistoryEntry(
                0,
                sourceKey(sourceName),
                sourceName,
                "",
                "",
                wikiUrl,
                receivedLoot,
            lookupResult,
            potentialStatus,
            true
        ));
    }

    private void requestRender()
    {
        if (!SwingUtilities.isEventDispatchThread())
        {
            SwingUtilities.invokeLater(this::requestRender);
            return;
        }

        if (renderQueued)
        {
            return;
        }

        renderQueued = true;
        SwingUtilities.invokeLater(() ->
        {
            renderQueued = false;
            renderHistory();
        });
    }

    private void renderHistory()
    {
        if (updateNoticeVisible)
        {
            showUpdateNotice();
            return;
        }

        headerPanel.setVisible(historyEntries.isEmpty());
        sourceLabel.setText(PLUGIN_NAME);
        configureWikiLink(null);
        contentPanel.removeAll();

        if (historyEntries.isEmpty())
        {
            contentPanel.add(buildMutedRow("Kill a monster or open a chest to begin."));
            revalidate();
            repaint();
            return;
        }

        LootHistoryEntry selectedEntry = selectedEntry();
        contentPanel.add(buildOverallPanel());
        contentPanel.add(buildSourceTabs());
        contentPanel.add(Box.createVerticalStrut(4));
        contentPanel.add(buildPotentialTablePanel(selectedEntry));
        contentPanel.add(Box.createVerticalStrut(8));
        contentPanel.add(buildSubHeader("Received loot"));
        contentPanel.add(buildHistoryEntry(selectedEntry));

        revalidate();
        repaint();
    }

    private LootHistoryEntry selectedEntry()
    {
        if (selectedSourceKey != null)
        {
            for (LootHistoryEntry entry : historyEntries)
            {
                if (entry.getSourceKey().equals(selectedSourceKey))
                {
                    return entry;
                }
            }
        }

        selectedSourceKey = historyEntries.get(0).getSourceKey();
        return historyEntries.get(0);
    }

    private JPanel buildSourceTabs()
    {
        int columns = Math.min(2, historyEntries.size());
        int rows = Math.max(1, (historyEntries.size() + columns - 1) / columns);
        JPanel tabsPanel = new JPanel(new GridLayout(rows, columns, 2, 2));
        tabsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        tabsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        tabsPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, rows * 26));

        for (LootHistoryEntry entry : historyEntries)
        {
            tabsPanel.add(buildSourceTab(entry));
        }

        for (int i = historyEntries.size(); i < rows * columns; i++)
        {
            JPanel filler = new JPanel();
            filler.setOpaque(false);
            tabsPanel.add(filler);
        }

        return tabsPanel;
    }

    private JPanel buildSourceTab(LootHistoryEntry entry)
    {
        boolean selected = entry.getSourceKey().equals(selectedSourceKey);
        JPanel tab = new JPanel(new BorderLayout());
        tab.setBackground(selected ? ColorScheme.DARKER_GRAY_HOVER_COLOR : ColorScheme.DARKER_GRAY_COLOR);
        tab.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(selected ? ColorScheme.BRAND_ORANGE : ColorScheme.DARK_GRAY_COLOR),
            new EmptyBorder(3, 5, 3, 5)
        ));
        tab.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        tab.setToolTipText(entry.getSourceName() + " - right-click to close");

        JLabel label = buildSmallLabel(truncate(entry.getSourceName(), 14), selected ? Color.WHITE : ColorScheme.LIGHT_GRAY_COLOR);
        JLabel count = buildSmallLabel("x" + QuantityFormatter.quantityToStackSize(entry.getCount()), ColorScheme.LIGHT_GRAY_COLOR);
        tab.add(label, BorderLayout.CENTER);
        tab.add(count, BorderLayout.EAST);

        java.awt.event.MouseAdapter listener = new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                if (javax.swing.SwingUtilities.isRightMouseButton(e))
                {
                    removeSourceEntry(entry.getSourceKey());
                    return;
                }

                selectedSourceKey = entry.getSourceKey();
                expandedPotentialTables.putIfAbsent(selectedSourceKey, Boolean.TRUE);
                requestRender();
            }
        };

        tab.addMouseListener(listener);
        label.addMouseListener(listener);
        count.addMouseListener(listener);
        return tab;
    }

    private JPanel buildOverallPanel()
    {
        long totalCount = 0;
        long totalPrice = 0;
        for (LootHistoryEntry entry : historyEntries)
        {
            totalCount += entry.getCount();
            totalPrice += entry.getTotalPrice();
        }

        JPanel overallPanel = new JPanel(new BorderLayout());
        overallPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(5, 0, 0, 0, ColorScheme.DARK_GRAY_COLOR),
            BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        overallPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        overallPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        overallPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

        JLabel iconLabel = buildCenteredLabel();
        iconLabel.setPreferredSize(new Dimension(34, 32));
        AsyncBufferedImage icon = cachedItemImage(ItemID.CASKET, 1, false);
        icon.addTo(iconLabel);

        JPanel infoPanel = new JPanel(new GridLayout(2, 1));
        infoPanel.setOpaque(false);
        infoPanel.setBorder(new EmptyBorder(0, 7, 0, 0));

        JLabel countLabel = buildSmallLabel("Total count: " + QuantityFormatter.formatNumber(totalCount), ColorScheme.LIGHT_GRAY_COLOR);
        JLabel valueLabel = buildSmallLabel("Total value: " + QuantityFormatter.formatNumber(totalPrice), ColorScheme.LIGHT_GRAY_COLOR);
        valueLabel.setToolTipText("Grand Exchange value: " + QuantityFormatter.formatNumber(totalPrice) + " gp");

        infoPanel.add(countLabel);
        infoPanel.add(valueLabel);
        overallPanel.add(iconLabel, BorderLayout.WEST);
        overallPanel.add(infoPanel, BorderLayout.CENTER);
        return overallPanel;
    }

    private JPanel buildHistoryEntry(LootHistoryEntry entry)
    {
        JPanel lootBox = new JPanel(new BorderLayout(0, 1));
        lootBox.setBorder(new EmptyBorder(5, 0, 0, 0));
        lootBox.setBackground(ColorScheme.DARK_GRAY_COLOR);
        lootBox.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel bodyPanel = buildVerticalPanel();
        if (entry.isShowReceivedItems())
        {
            bodyPanel.add(buildReceivedPanel(entry.getReceivedLoot()));
        }

        lootBox.add(buildHistoryHeader(entry), BorderLayout.NORTH);
        lootBox.add(bodyPanel, BorderLayout.CENTER);
        return lootBox;
    }

    private JPanel buildHistoryHeader(LootHistoryEntry entry)
    {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
        header.setBorder(new EmptyBorder(7, 7, 7, 7));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel titleLabel = buildSmallLabel(entry.getSourceName(), Color.WHITE);
        titleLabel.setMinimumSize(new Dimension(1, titleLabel.getPreferredSize().height));

        JLabel countLabel = buildSmallLabel("x " + QuantityFormatter.quantityToStackSize(entry.getCount()), ColorScheme.LIGHT_GRAY_COLOR);

        JLabel linkLabel = new JLabel();
        configureLinkLabel(linkLabel, entry.getWikiUrl());

        header.add(titleLabel);
        header.add(Box.createHorizontalGlue());
        header.add(countLabel);
        header.add(Box.createRigidArea(new Dimension(TITLE_PADDING, 0)));
        header.add(linkLabel);
        return header;
    }

    private JPanel buildReceivedPanel(List<ReceivedLootRow> receivedLoot)
    {
        if (receivedLoot == null || receivedLoot.isEmpty())
        {
            JPanel receivedPanel = buildVerticalPanel();
            receivedPanel.add(buildMutedRow("No received loot yet."));
            return receivedPanel;
        }

        List<ReceivedLootRow> sortedLoot = new ArrayList<>(receivedLoot);
        sortedLoot.sort(Comparator.comparingLong(ReceivedLootRow::getPriceValue).reversed());

        JPanel itemGrid = buildItemGrid(sortedLoot.size());
        for (ReceivedLootRow row : sortedLoot)
        {
            itemGrid.add(buildReceivedItemSlot(row));
        }

        addEmptySlots(itemGrid, sortedLoot.size());

        JPanel receivedPanel = buildVerticalPanel();
        receivedPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        receivedPanel.add(itemGrid);
        receivedPanel.add(Box.createVerticalStrut(6));
        return receivedPanel;
    }

    private JPanel buildItemGrid(int itemCount)
    {
        int rowCount = Math.max(1, ((itemCount % ITEMS_PER_ROW == 0) ? 0 : 1) + itemCount / ITEMS_PER_ROW);
        JPanel itemGrid = new JPanel(new GridLayout(rowCount, ITEMS_PER_ROW, 1, 1));
        itemGrid.setBackground(ColorScheme.DARK_GRAY_COLOR);
        itemGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        itemGrid.setMaximumSize(new Dimension(Integer.MAX_VALUE, rowCount * SLOT_SIZE));
        return itemGrid;
    }

    private JPanel buildReceivedItemSlot(ReceivedLootRow row)
    {
        JPanel slot = buildSlotContainer();
        JLabel imageLabel = buildCenteredLabel();
        imageLabel.setToolTipText(buildReceivedTooltip(row));

        if (row.getItemId() > 0)
        {
            AsyncBufferedImage itemImage = cachedItemImage(row.getItemId(), row.getQuantity(), row.getQuantity() > 1);
            itemImage.addTo(imageLabel);
        }

        slot.add(imageLabel, BorderLayout.CENTER);
        return slot;
    }

    private AsyncBufferedImage cachedItemImage(int itemId, int quantity, boolean stackable)
    {
        int safeQuantity = Math.max(1, quantity);
        String key = itemId + ":" + safeQuantity + ":" + stackable;
        AsyncBufferedImage cachedImage = itemImageCache.get(key);
        if (cachedImage != null)
        {
            return cachedImage;
        }

        AsyncBufferedImage image = itemManager.getImage(itemId, safeQuantity, stackable);
        itemImageCache.put(key, image);
        return image;
    }

    private JPanel buildSlotContainer()
    {
        JPanel slot = new JPanel(new BorderLayout());
        slot.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        slot.setPreferredSize(new Dimension(SLOT_SIZE, SLOT_SIZE));
        slot.setMinimumSize(new Dimension(SLOT_SIZE, SLOT_SIZE));
        slot.setBorder(new EmptyBorder(1, 1, 1, 1));
        return slot;
    }

    private JLabel buildCenteredLabel()
    {
        JLabel label = new JLabel();
        label.setVerticalAlignment(SwingConstants.CENTER);
        label.setHorizontalAlignment(SwingConstants.CENTER);
        return label;
    }

    private void addEmptySlots(JPanel itemGrid, int itemCount)
    {
        int remainder = itemCount % ITEMS_PER_ROW;
        if (remainder == 0)
        {
            return;
        }

        for (int i = remainder; i < ITEMS_PER_ROW; i++)
        {
            itemGrid.add(buildSlotContainer());
        }
    }

    private JPanel buildPotentialTablePanel(LootHistoryEntry historyEntry)
    {
        JPanel potentialPanel = buildVerticalPanel();
        boolean tableExpanded = expandedPotentialTables.getOrDefault(historyEntry.getSourceKey(), Boolean.TRUE);
        potentialPanel.add(buildPotentialTableHeader(historyEntry, tableExpanded));
        if (!tableExpanded)
        {
            return potentialPanel;
        }

        List<DropEntry> entries = historyEntry.getPotentialLoot();

        if (entries.isEmpty())
        {
            String status = historyEntry.getPotentialStatus();
            if (status != null && !status.isBlank())
            {
                potentialPanel.add(buildMutedRow(status));
            }
        }
        else
        {
            for (Map.Entry<String, List<DropEntry>> group : historyEntry.getPotentialLootByCategory().entrySet())
            {
                String categoryName = group.getKey();
                List<DropEntry> categoryEntries = group.getValue();

                JPanel categoryItemsPanel = buildPotentialRows(categoryEntries);

                String categoryKey = categoryKey(historyEntry.getSourceKey(), categoryName);
                boolean categoryExpanded = expandedCategories.getOrDefault(categoryKey, Boolean.FALSE);
                categoryItemsPanel.setVisible(categoryExpanded);

                JPanel header = buildCategoryHeader(tableCategoryName(historyEntry, categoryName), categoryEntries.size(), categoryExpanded, visible ->
                {
                    expandedCategories.put(categoryKey, visible);
                    categoryItemsPanel.setVisible(visible);
                    revalidate();
                    repaint();
                });

                potentialPanel.add(header);
                potentialPanel.add(categoryItemsPanel);
                potentialPanel.add(Box.createVerticalStrut(4));
            }
        }

        return potentialPanel;
    }

    private JPanel buildPotentialTableHeader(LootHistoryEntry historyEntry, boolean expanded)
    {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
        header.setBorder(new EmptyBorder(5, 6, 5, 6));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel arrowLabel = buildToggleArrow(expanded);
        JLabel titleLabel = buildBoldSmallLabel(tableTitle(historyEntry), Color.WHITE);
        JLabel linkLabel = new JLabel();
        configureLinkLabel(linkLabel, historyEntry.getWikiUrl());
        linkLabel.setFont(FontManager.getRunescapeSmallFont().deriveFont(java.awt.Font.BOLD));
        linkLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        linkLabel.setPreferredSize(new Dimension(36, 20));

        header.add(arrowLabel);
        header.add(Box.createRigidArea(new Dimension(TITLE_PADDING, 0)));
        header.add(titleLabel);
        header.add(Box.createHorizontalGlue());
        header.add(linkLabel);

        java.awt.event.MouseAdapter listener = new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                boolean newExpanded = !"▾".equals(arrowLabel.getText());
                expandedPotentialTables.put(historyEntry.getSourceKey(), newExpanded);
                requestRender();
            }
        };

        header.addMouseListener(listener);
        arrowLabel.addMouseListener(listener);
        titleLabel.addMouseListener(listener);
        return header;
    }

    private String tableTitle(LootHistoryEntry historyEntry)
    {
        String wikiTitle = historyEntry.getWikiPageTitle();
        if (wikiTitle != null && !wikiTitle.isBlank())
        {
            return wikiTitle + " drop table";
        }

        return historyEntry.getSourceName() + " drop table";
    }

    private String tableCategoryName(LootHistoryEntry historyEntry, String categoryName)
    {
        if (categoryName == null || categoryName.isBlank() || isGenericCategory(categoryName))
        {
            return tableTitle(historyEntry);
        }

        return categoryName;
    }

    private boolean isGenericCategory(String categoryName)
    {
        String lower = categoryName.toLowerCase().trim();
        return lower.equals("drops")
            || lower.equals("drop table")
            || lower.equals("loot")
            || lower.equals("loot table");
    }

    private JPanel buildPotentialRows(List<DropEntry> entries)
    {
        JPanel rowsPanel = buildVerticalPanel();
        rowsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        rowsPanel.setBorder(new EmptyBorder(0, 0, 6, 0));
        for (DropEntry entry : entries)
        {
            rowsPanel.add(buildPotentialTableRow(entry));
        }
        return rowsPanel;
    }

    private JPanel buildPotentialTableRow(DropEntry entry)
    {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, ColorScheme.DARK_GRAY_COLOR),
            new EmptyBorder(5, 5, 5, 5)
        ));
        row.setPreferredSize(new Dimension(0, POTENTIAL_ROW_HEIGHT));
        row.setMinimumSize(new Dimension(0, POTENTIAL_ROW_HEIGHT));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, POTENTIAL_ROW_HEIGHT));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setToolTipText(buildPotentialTooltip(entry));

        JLabel iconLabel = buildPotentialIconLabel(entry);

        JLabel nameLabel = buildBoldSmallLabel(truncate(entry.getItemName(), 22), Color.WHITE);
        nameLabel.setToolTipText(buildPotentialTooltip(entry));

        String rateText = displayRate(entry);
        JLabel rateLabel = buildBoldSmallLabel(rateText, dropRateColor(rateText));
        rateLabel.setToolTipText(buildPotentialTooltip(entry));

        JPanel detailPanel = new JPanel();
        detailPanel.setLayout(new BoxLayout(detailPanel, BoxLayout.Y_AXIS));
        detailPanel.setOpaque(false);
        nameLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        rateLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        detailPanel.add(nameLabel);
        detailPanel.add(Box.createVerticalStrut(1));
        detailPanel.add(rateLabel);

        JPanel detailWrapper = new JPanel(new GridBagLayout());
        detailWrapper.setOpaque(false);
        GridBagConstraints detailConstraints = new GridBagConstraints();
        detailConstraints.gridx = 0;
        detailConstraints.gridy = 0;
        detailConstraints.weightx = 1.0;
        detailConstraints.fill = GridBagConstraints.HORIZONTAL;
        detailConstraints.anchor = GridBagConstraints.WEST;
        detailWrapper.add(detailPanel, detailConstraints);

        JLabel quantityLabel = buildBoldSmallLabel(displayQuantity(entry), ColorScheme.LIGHT_GRAY_COLOR);
        quantityLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        quantityLabel.setVerticalAlignment(SwingConstants.CENTER);
        quantityLabel.setPreferredSize(new Dimension(58, POTENTIAL_ROW_HEIGHT - 10));
        quantityLabel.setToolTipText(buildPotentialTooltip(entry));

        row.add(iconLabel, BorderLayout.WEST);
        row.add(detailWrapper, BorderLayout.CENTER);
        row.add(quantityLabel, BorderLayout.EAST);
        return row;
    }

    private JLabel buildPotentialIconLabel(DropEntry entry)
    {
        JLabel iconLabel = buildCenteredLabel();
        Dimension iconSize = new Dimension(POTENTIAL_ICON_SLOT_SIZE, POTENTIAL_ROW_HEIGHT - 10);
        iconLabel.setPreferredSize(iconSize);
        iconLabel.setMinimumSize(iconSize);
        iconLabel.setMaximumSize(iconSize);

        if (isNothingEntry(entry))
        {
            iconLabel.setIcon(NOTHING_ICON);
            return iconLabel;
        }

        int itemId = resolvePotentialItemId(entry);
        if (itemId > 0)
        {
            AsyncBufferedImage itemImage = cachedItemImage(itemId, potentialIconQuantity(entry), false);
            itemImage.addTo(iconLabel);
        }

        return iconLabel;
    }

    private String displayQuantity(DropEntry entry)
    {
        if (entry.getQuantityText() == null || entry.getQuantityText().isBlank())
        {
            return "";
        }

        return "x " + entry.getQuantityText();
    }

    private int potentialIconQuantity(DropEntry entry)
    {
        if (entry == null || entry.getQuantityText() == null || entry.getQuantityText().isBlank())
        {
            return 1;
        }

        int quantity = 1;
        String normalizedQuantity = entry.getQuantityText().replace(",", "");
        for (String part : normalizedQuantity.split("[^0-9]+"))
        {
            if (part.isBlank())
            {
                continue;
            }

            try
            {
                quantity = Math.max(quantity, Integer.parseInt(part));
            }
            catch (NumberFormatException ex)
            {
                return quantity;
            }
        }

        return quantity;
    }

    private String displayRate(DropEntry entry)
    {
        if (entry.getDropRateText() != null && !entry.getDropRateText().isBlank())
        {
            return entry.getDropRateText();
        }

        if (entry.getRarity() != null && !entry.getRarity().isBlank())
        {
            return entry.getRarity();
        }

        return "";
    }

    private Color dropRateColor(String rateText)
    {
        if (rateText == null || rateText.isBlank() || rateText.equalsIgnoreCase("Always"))
        {
            return ColorScheme.LIGHT_GRAY_COLOR;
        }

        return ColorScheme.BRAND_ORANGE;
    }

    private int resolvePotentialItemId(DropEntry entry)
    {
        if (entry.getItemId() > 0)
        {
            return entry.getItemId();
        }

        String itemName = entry.getItemName();
        if (itemName == null || itemName.isBlank())
        {
            return 0;
        }

        int specialItemId = specialPotentialItemId(itemName);
        if (specialItemId > 0)
        {
            return specialItemId;
        }

        String key = ItemIdResolver.normalizeItemName(itemName);
        Integer cachedItemId = itemIdsByName.get(key);
        if (cachedItemId != null)
        {
            return cachedItemId;
        }

        int resolvedItemId = ItemIdResolver.resolve(itemName);
        if (resolvedItemId <= 0)
        {
            resolvedItemId = searchItemId(itemName);
        }

        itemIdsByName.put(key, resolvedItemId);
        return resolvedItemId;
    }

    private int searchItemId(String itemName)
    {
        try
        {
            List<ItemPrice> matches = itemManager.search(itemName);
            if (matches == null || matches.isEmpty())
            {
                return 0;
            }

            String normalizedItemName = ItemIdResolver.normalizeItemName(itemName);
            for (ItemPrice match : matches)
            {
                if (match.getId() > 0 && ItemIdResolver.normalizeItemName(match.getName()).equals(normalizedItemName))
                {
                    return match.getId();
                }
            }

            return matches.get(0).getId();
        }
        catch (RuntimeException ex)
        {
            return 0;
        }
    }

    private int specialPotentialItemId(String itemName)
    {
        String normalizedItemName = normalizeItemName(itemName);
        if (normalizedItemName.equals("coins"))
        {
            return ItemID.COINS_995;
        }

        if (!normalizedItemName.startsWith("clue scroll"))
        {
            return 0;
        }

        if (normalizedItemName.contains("beginner"))
        {
            return ItemID.CLUE_SCROLL_BEGINNER;
        }
        if (normalizedItemName.contains("easy"))
        {
            return ItemID.CLUE_SCROLL_EASY;
        }
        if (normalizedItemName.contains("medium"))
        {
            return ItemID.CLUE_SCROLL_MEDIUM;
        }
        if (normalizedItemName.contains("hard"))
        {
            return ItemID.CLUE_SCROLL_HARD;
        }
        if (normalizedItemName.contains("elite"))
        {
            return ItemID.CLUE_SCROLL_ELITE;
        }
        if (normalizedItemName.contains("master"))
        {
            return ItemID.CLUE_SCROLL_MASTER;
        }

        return ItemID.CLUE_SCROLL;
    }

    private boolean isNothingEntry(DropEntry entry)
    {
        return entry != null && normalizeItemName(entry.getItemName()).equals("nothing");
    }

    private String normalizeItemName(String itemName)
    {
        return itemName == null ? "" : itemName.toLowerCase().replace('\u00A0', ' ').trim();
    }

    private void showEmptyState()
    {
        if (updateNoticeVisible)
        {
            showUpdateNotice();
            return;
        }

        headerPanel.setVisible(true);
        sourceLabel.setText(PLUGIN_NAME);
        configureWikiLink(null);

        contentPanel.removeAll();
        contentPanel.add(buildMutedRow("Kill a monster or open a chest to begin."));
        revalidate();
        repaint();
    }

    private boolean shouldShowUpdateNotice()
    {
        try
        {
            return !UPDATE_NOTICE_VERSION.equals(configManager.getConfiguration(CONFIG_GROUP, UPDATE_NOTICE_KEY));
        }
        catch (RuntimeException ex)
        {
            return true;
        }
    }

    private void showUpdateNotice()
    {
        headerPanel.setVisible(false);
        configureWikiLink(null);
        contentPanel.removeAll();
        contentPanel.add(Box.createVerticalGlue());
        contentPanel.add(buildUpdateNoticePanel());
        contentPanel.add(Box.createVerticalGlue());
        revalidate();
        repaint();
    }

    private JPanel buildUpdateNoticePanel()
    {
        JPanel noticePanel = new JPanel();
        noticePanel.setLayout(new BoxLayout(noticePanel, BoxLayout.Y_AXIS));
        noticePanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        noticePanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
            new EmptyBorder(12, 12, 12, 12)
        ));
        noticePanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        noticePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 240));

        JLabel titleLabel = buildBoldSmallLabel("New update!", ColorScheme.BRAND_ORANGE);
        titleLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        noticePanel.add(titleLabel);
        noticePanel.add(Box.createVerticalStrut(8));

        addNoticeLine(noticePanel, "Instant drop-table prefetch after attacking a mob.");
        addNoticeLine(noticePanel, "Smoother repeated kills.");
        addNoticeLine(noticePanel, "Improved missing icons.");
        addNoticeLine(noticePanel, "Cached prices, alch, icons, and rates.");
        noticePanel.add(Box.createVerticalStrut(8));

        JLabel feedbackLabel = buildBoldSmallLabel("Feedback: Discord Ouf", Color.WHITE);
        feedbackLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        noticePanel.add(feedbackLabel);
        noticePanel.add(Box.createVerticalStrut(10));

        JButton closeButton = new JButton("Got it");
        closeButton.setFocusable(false);
        closeButton.setFont(FontManager.getRunescapeSmallFont().deriveFont(java.awt.Font.BOLD));
        closeButton.setForeground(Color.WHITE);
        closeButton.setBackground(ColorScheme.DARK_GRAY_COLOR);
        closeButton.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(ColorScheme.BRAND_ORANGE),
            new EmptyBorder(4, 10, 4, 10)
        ));
        closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        closeButton.addActionListener(event ->
        {
            updateNoticeVisible = false;
            configManager.setConfiguration(CONFIG_GROUP, UPDATE_NOTICE_KEY, UPDATE_NOTICE_VERSION);
            renderHistory();
        });
        noticePanel.add(closeButton);
        return noticePanel;
    }

    private void addNoticeLine(JPanel noticePanel, String text)
    {
        JLabel label = buildSmallLabel("- " + text, ColorScheme.LIGHT_GRAY_COLOR);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        noticePanel.add(label);
        noticePanel.add(Box.createVerticalStrut(4));
    }

    private JPanel buildVerticalPanel()
    {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    private JPanel buildSubHeader(String title)
    {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        header.setBorder(new EmptyBorder(5, 7, 5, 7));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel titleLabel = buildSmallLabel(title, Color.WHITE);
        header.add(titleLabel, BorderLayout.CENTER);
        return header;
    }

    private JLabel buildSmallLabel(String text, Color color)
    {
        JLabel label = new JLabel(plainLabelText(text));
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setForeground(color);
        return label;
    }

    private JLabel buildBoldSmallLabel(String text, Color color)
    {
        JLabel label = buildSmallLabel(text, color);
        label.setFont(FontManager.getRunescapeSmallFont().deriveFont(java.awt.Font.BOLD));
        return label;
    }

    private String buildReceivedTooltip(ReceivedLootRow row)
    {
        StringBuilder tooltip = new StringBuilder("<html>");
        tooltip.append(escapeHtml(row.getItemName()))
            .append(" x ")
            .append(QuantityFormatter.formatNumber(row.getQuantity()));

        if (row.getPriceText() != null && !row.getPriceText().isBlank())
        {
            tooltip.append("<br>GE price: ").append(escapeHtml(row.getPriceText()));
        }

        if (row.getHighAlchText() != null && !row.getHighAlchText().isBlank())
        {
            tooltip.append("<br>High alch: ").append(escapeHtml(row.getHighAlchText()));
        }

        if (row.getDropRateText() != null && !row.getDropRateText().isBlank())
        {
            tooltip.append("<br>Drop rate: ").append(escapeHtml(row.getDropRateText()));
        }

        tooltip.append("</html>");
        return tooltip.toString();
    }

    private String buildPotentialTooltip(DropEntry entry)
    {
        StringBuilder tooltip = new StringBuilder("<html>");
        tooltip.append(escapeHtml(entry.getItemName()));

        String quantity = displayQuantity(entry);
        if (!quantity.isBlank())
        {
            tooltip.append("<br>Quantity: ").append(escapeHtml(quantity));
        }

        if (entry.getDropRateText() != null && !entry.getDropRateText().isBlank())
        {
            tooltip.append("<br>Drop rate: ").append(escapeHtml(entry.getDropRateText()));
        }

        if (entry.getRarity() != null && !entry.getRarity().isBlank())
        {
            tooltip.append("<br>Rarity: ").append(escapeHtml(entry.getRarity()));
        }

        if (entry.getNotes() != null && !entry.getNotes().isBlank())
        {
            tooltip.append("<br>").append(escapeHtml(entry.getNotes()));
        }

        tooltip.append("</html>");
        return tooltip.toString();
    }

    private String escapeHtml(String value)
    {
        if (value == null)
        {
            return "";
        }

        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private JPanel buildMutedRow(String text)
    {
        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(buildSmallLabel(text, ColorScheme.LIGHT_GRAY_COLOR), BorderLayout.CENTER);
        return row;
    }

    private JPanel buildCategoryHeader(String title, int count, boolean expanded, Consumer<Boolean> toggleAction)
    {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(ColorScheme.DARKER_GRAY_COLOR.darker());
        header.setBorder(new EmptyBorder(5, 6, 5, 6));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        header.setToolTipText(formatDropCount(count));

        JLabel arrowLabel = buildToggleArrow(expanded);
        JLabel titleLabel = buildBoldSmallLabel(title == null ? "Other" : title, ColorScheme.BRAND_ORANGE);

        header.add(arrowLabel, BorderLayout.WEST);
        header.add(titleLabel, BorderLayout.CENTER);

        java.awt.event.MouseAdapter clickListener = new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                boolean newExpanded = !"▾".equals(arrowLabel.getText());
                arrowLabel.setText(newExpanded ? "▾" : "▸");
                toggleAction.accept(newExpanded);
            }
        };

        header.addMouseListener(clickListener);
        arrowLabel.addMouseListener(clickListener);
        titleLabel.addMouseListener(clickListener);

        return header;
    }

    private JLabel buildToggleArrow(boolean expanded)
    {
        JLabel arrowLabel = buildSmallLabel(expanded ? "▾" : "▸", Color.WHITE);
        arrowLabel.setOpaque(false);
        arrowLabel.setBorder(BorderFactory.createEmptyBorder());
        arrowLabel.setHorizontalAlignment(SwingConstants.CENTER);
        arrowLabel.setPreferredSize(new Dimension(20, 20));
        return arrowLabel;
    }

    private String formatDropCount(int count)
    {
        return count == 1 ? "1 drop" : count + " drops";
    }

    private String truncate(String value, int maxLength)
    {
        if (value == null)
        {
            return "";
        }

        return value.length() <= maxLength ? value : value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private void configureWikiLink(String wikiUrl)
    {
        configureLinkLabel(wikiLinkLabel, wikiUrl);
    }

    private void configureLinkLabel(JLabel linkLabel, String wikiUrl)
    {
        for (java.awt.event.MouseListener listener : linkLabel.getMouseListeners())
        {
            linkLabel.removeMouseListener(listener);
        }

        String safeWikiUrl = safeWikiUrl(wikiUrl);
        if (safeWikiUrl.isBlank())
        {
            linkLabel.setText("");
            linkLabel.setBorder(BorderFactory.createEmptyBorder());
            linkLabel.setCursor(Cursor.getDefaultCursor());
            return;
        }

        linkLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        linkLabel.setFont(FontManager.getRunescapeSmallFont());
        linkLabel.setBorder(new EmptyBorder(0, TITLE_PADDING, 0, 0));
        linkLabel.setText("<html><a href=''>Wiki</a></html>");
        linkLabel.addMouseListener(new java.awt.event.MouseAdapter()
        {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e)
            {
                LinkBrowser.browse(safeWikiUrl);
            }
        });
    }

    private String safeWikiUrl(String wikiUrl)
    {
        if (wikiUrl == null || wikiUrl.isBlank())
        {
            return "";
        }

        try
        {
            java.net.URI uri = new java.net.URI(wikiUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"oldschool.runescape.wiki".equalsIgnoreCase(uri.getHost()))
            {
                return "";
            }

            return uri.toString();
        }
        catch (java.net.URISyntaxException ex)
        {
            return "";
        }
    }

    private String plainLabelText(String text)
    {
        if (text == null)
        {
            return "";
        }

        return text.trim().toLowerCase().startsWith("<html")
            ? text.replace("<", "&lt;")
            : text;
    }

    private String categoryKey(String sourceKey, String categoryName)
    {
        return (sourceKey == null ? "" : sourceKey) + ":" + (categoryName == null ? "Other" : categoryName);
    }

    private String sourceKey(String sourceName)
    {
        return normalizeItemName(sourceName == null ? "Unknown source" : sourceName);
    }

    private static class PendingLookupUpdate
    {
        private final String wikiPageTitle;
        private final String wikiUrl;
        private final List<ReceivedLootRow> receivedLoot;
        private final List<DropEntry> lookupResult;
        private final String potentialStatus;

        private PendingLookupUpdate(String wikiPageTitle, String wikiUrl, List<ReceivedLootRow> receivedLoot, List<DropEntry> lookupResult, String potentialStatus)
        {
            this.wikiPageTitle = wikiPageTitle;
            this.wikiUrl = wikiUrl;
            this.receivedLoot = receivedLoot;
            this.lookupResult = lookupResult;
            this.potentialStatus = potentialStatus;
        }
    }

    public static class LootHistoryEntry
    {
        private final long historyId;
        private final String sourceKey;
        private final String sourceName;
        private final String receivedAtText;
        private final String wikiPageTitle;
        private final String wikiUrl;
        private final List<ReceivedLootRow> receivedLoot;
        private final List<DropEntry> potentialLoot;
        private final Map<String, List<DropEntry>> potentialLootByCategory;
        private final String potentialStatus;
        private final boolean showReceivedItems;
        private final long count;

        public LootHistoryEntry(
            long historyId,
            String sourceName,
            String receivedAtText,
            String wikiPageTitle,
            String wikiUrl,
            List<ReceivedLootRow> receivedLoot,
            List<DropEntry> potentialLoot,
            String potentialStatus,
            boolean showReceivedItems)
        {
            this(
                historyId,
                normalizeSourceKey(sourceName),
                sourceName,
                receivedAtText,
                wikiPageTitle,
                wikiUrl,
                receivedLoot,
                potentialLoot,
                potentialStatus,
                showReceivedItems,
                1
            );
        }

        public LootHistoryEntry(
            long historyId,
            String sourceKey,
            String sourceName,
            String receivedAtText,
            String wikiPageTitle,
            String wikiUrl,
            List<ReceivedLootRow> receivedLoot,
            List<DropEntry> potentialLoot,
            String potentialStatus,
            boolean showReceivedItems)
        {
            this(
                historyId,
                sourceKey,
                sourceName,
                receivedAtText,
                wikiPageTitle,
                wikiUrl,
                receivedLoot,
                potentialLoot,
                potentialStatus,
                showReceivedItems,
                1
            );
        }

        private LootHistoryEntry(
            long historyId,
            String sourceKey,
            String sourceName,
            String receivedAtText,
            String wikiPageTitle,
            String wikiUrl,
            List<ReceivedLootRow> receivedLoot,
            List<DropEntry> potentialLoot,
            String potentialStatus,
            boolean showReceivedItems,
            long count)
        {
            this.historyId = historyId;
            this.sourceKey = sourceKey == null || sourceKey.isBlank() ? normalizeSourceKey(sourceName) : sourceKey;
            this.sourceName = sourceName == null || sourceName.isBlank() ? "Unknown source" : sourceName;
            this.receivedAtText = receivedAtText == null ? "" : receivedAtText;
            this.wikiPageTitle = wikiPageTitle == null ? "" : wikiPageTitle;
            this.wikiUrl = wikiUrl == null ? "" : wikiUrl;
            this.receivedLoot = receivedLoot == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(receivedLoot));
            this.potentialLoot = potentialLoot == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(potentialLoot));
            this.potentialLootByCategory = groupPotentialLootByCategory(this.potentialLoot);
            this.potentialStatus = potentialStatus == null ? "" : potentialStatus;
            this.showReceivedItems = showReceivedItems;
            this.count = Math.max(count, 1);
        }

        private LootHistoryEntry withMergedLoot(LootHistoryEntry latest)
        {
            List<ReceivedLootRow> mergedLoot = mergeReceivedLoot(receivedLoot, latest.receivedLoot);
            List<DropEntry> mergedPotentialLoot = latest.potentialLoot.isEmpty() ? potentialLoot : latest.potentialLoot;
            String mergedWikiUrl = latest.wikiUrl.isBlank() ? wikiUrl : latest.wikiUrl;
            String mergedStatus = latest.potentialLoot.isEmpty() && !potentialLoot.isEmpty() ? potentialStatus : latest.potentialStatus;
            if (mergedStatus == null || mergedStatus.isBlank())
            {
                mergedStatus = potentialStatus;
            }

            return new LootHistoryEntry(
                latest.historyId,
                sourceKey,
                latest.sourceName,
                latest.receivedAtText,
                latest.wikiPageTitle.isBlank() ? wikiPageTitle : latest.wikiPageTitle,
                mergedWikiUrl,
                mergedLoot,
                mergedPotentialLoot,
                mergedStatus,
                latest.showReceivedItems,
                count + latest.count
            );
        }

        private LootHistoryEntry withLookupUpdate(String wikiPageTitle, String wikiUrl, List<ReceivedLootRow> receivedLoot, List<DropEntry> potentialLoot, String potentialStatus)
        {
            return new LootHistoryEntry(
                historyId,
                sourceKey,
                sourceName,
                receivedAtText,
                wikiPageTitle == null ? this.wikiPageTitle : wikiPageTitle,
                wikiUrl == null ? this.wikiUrl : wikiUrl,
                receivedLoot == null ? this.receivedLoot : updateReceivedDropRates(this.receivedLoot, receivedLoot),
                potentialLoot == null ? this.potentialLoot : potentialLoot,
                potentialStatus == null ? this.potentialStatus : potentialStatus,
                showReceivedItems,
                count
            );
        }

        private static List<ReceivedLootRow> mergeReceivedLoot(List<ReceivedLootRow> existingRows, List<ReceivedLootRow> addedRows)
        {
            Map<String, ReceivedLootRow> merged = new LinkedHashMap<>();
            for (ReceivedLootRow row : existingRows)
            {
                merged.put(row.key(), row);
            }

            for (ReceivedLootRow row : addedRows)
            {
                ReceivedLootRow existing = merged.get(row.key());
                merged.put(row.key(), existing == null ? row : existing.withAdded(row));
            }

            return new ArrayList<>(merged.values());
        }

        private static List<ReceivedLootRow> updateReceivedDropRates(List<ReceivedLootRow> existingRows, List<ReceivedLootRow> lookupRows)
        {
            Map<String, ReceivedLootRow> lookup = new HashMap<>();
            for (ReceivedLootRow row : lookupRows)
            {
                lookup.put(row.key(), row);
            }

            List<ReceivedLootRow> updatedRows = new ArrayList<>();
            for (ReceivedLootRow row : existingRows)
            {
                ReceivedLootRow lookupRow = lookup.get(row.key());
                updatedRows.add(lookupRow == null ? row : row.withDropRate(lookupRow.getDropRateText()));
            }
            return updatedRows;
        }

        private static Map<String, List<DropEntry>> groupPotentialLootByCategory(List<DropEntry> potentialLoot)
        {
            if (potentialLoot == null || potentialLoot.isEmpty())
            {
                return Collections.emptyMap();
            }

            Map<String, List<DropEntry>> grouped = new LinkedHashMap<>();
            for (DropEntry entry : potentialLoot)
            {
                String category = entry.getCategory();
                if (category == null || category.isBlank())
                {
                    category = "Other";
                }

                grouped.computeIfAbsent(category, key -> new ArrayList<>()).add(entry);
            }

            Map<String, List<DropEntry>> immutableGrouped = new LinkedHashMap<>();
            for (Map.Entry<String, List<DropEntry>> group : grouped.entrySet())
            {
                immutableGrouped.put(group.getKey(), Collections.unmodifiableList(group.getValue()));
            }

            return Collections.unmodifiableMap(immutableGrouped);
        }

        private static String normalizeSourceKey(String sourceName)
        {
            return sourceName == null ? "" : sourceName.toLowerCase().replace('\u00A0', ' ').trim();
        }

        public long getHistoryId()
        {
            return historyId;
        }

        public String getSourceKey()
        {
            return sourceKey;
        }

        public String getSourceName()
        {
            return sourceName;
        }

        public String getReceivedAtText()
        {
            return receivedAtText;
        }

        public String getWikiPageTitle()
        {
            return wikiPageTitle;
        }

        public String getWikiUrl()
        {
            return wikiUrl;
        }

        public List<ReceivedLootRow> getReceivedLoot()
        {
            return receivedLoot;
        }

        public List<DropEntry> getPotentialLoot()
        {
            return potentialLoot;
        }

        public Map<String, List<DropEntry>> getPotentialLootByCategory()
        {
            return potentialLootByCategory;
        }

        public String getPotentialStatus()
        {
            return potentialStatus;
        }

        public long getCount()
        {
            return count;
        }

        public long getTotalPrice()
        {
            long totalPrice = 0;
            for (ReceivedLootRow row : receivedLoot)
            {
                totalPrice += row.getPriceValue();
            }
            return totalPrice;
        }

        public boolean isShowReceivedItems()
        {
            return showReceivedItems;
        }
    }

    public static class ReceivedLootRow
    {
        private final int itemId;
        private final String itemName;
        private final int quantity;
        private final String priceText;
        private final String dropRateText;
        private final long priceValue;
        private final String highAlchText;
        private final long highAlchValue;

        public ReceivedLootRow(int itemId, String itemName, int quantity, String priceText, String dropRateText)
        {
            this(itemId, itemName, quantity, priceText, dropRateText, 0, "", 0);
        }

        public ReceivedLootRow(int itemId, String itemName, int quantity, String priceText, String dropRateText, long priceValue)
        {
            this(itemId, itemName, quantity, priceText, dropRateText, priceValue, "", 0);
        }

        public ReceivedLootRow(int itemId, String itemName, int quantity, String priceText, String dropRateText, long priceValue, String highAlchText, long highAlchValue)
        {
            this.itemId = itemId;
            this.itemName = itemName == null ? "" : itemName;
            this.quantity = quantity;
            this.priceText = priceText == null ? "" : priceText;
            this.dropRateText = dropRateText == null ? "" : dropRateText;
            this.priceValue = priceValue;
            this.highAlchText = highAlchText == null ? "" : highAlchText;
            this.highAlchValue = highAlchValue;
        }

        public int getItemId()
        {
            return itemId;
        }

        public String getItemName()
        {
            return itemName;
        }

        public int getQuantity()
        {
            return quantity;
        }

        public String getPriceText()
        {
            return priceText;
        }

        public String getDropRateText()
        {
            return dropRateText;
        }

        public long getPriceValue()
        {
            return priceValue;
        }

        public String getHighAlchText()
        {
            return highAlchText;
        }

        public long getHighAlchValue()
        {
            return highAlchValue;
        }

        private String key()
        {
            return itemId > 0 ? "id:" + itemId : "name:" + normalizeName(itemName);
        }

        private ReceivedLootRow withAdded(ReceivedLootRow row)
        {
            int mergedQuantity = quantity + row.quantity;
            long mergedPrice = priceValue + row.priceValue;
            long mergedHighAlch = highAlchValue + row.highAlchValue;
            String mergedDropRate = !row.dropRateText.isBlank() ? row.dropRateText : dropRateText;
            return new ReceivedLootRow(
                itemId > 0 ? itemId : row.itemId,
                itemName == null || itemName.isBlank() ? row.itemName : itemName,
                mergedQuantity,
                formatPrice(mergedPrice),
                mergedDropRate,
                mergedPrice,
                formatPrice(mergedHighAlch),
                mergedHighAlch
            );
        }

        private ReceivedLootRow withDropRate(String dropRate)
        {
            if (dropRate == null || dropRate.isBlank())
            {
                return this;
            }

            return new ReceivedLootRow(itemId, itemName, quantity, priceText, dropRate, priceValue, highAlchText, highAlchValue);
        }

        private static String normalizeName(String value)
        {
            return value == null ? "" : value.toLowerCase().replace('\u00A0', ' ').trim();
        }

        private static String formatPrice(long price)
        {
            if (price >= 1_000_000)
            {
                return String.format("%.1fm gp", price / 1_000_000.0);
            }

            if (price >= 1_000)
            {
                return String.format("%.1fk gp", price / 1_000.0);
            }

            return price + " gp";
        }
    }

    private static class ScrollableContentPanel extends JPanel implements Scrollable
    {
        @Override
        public Dimension getPreferredScrollableViewportSize()
        {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
        {
            return 96;
        }

        @Override
        public boolean getScrollableTracksViewportWidth()
        {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight()
        {
            return false;
        }
    }

    private static class RuneLiteScrollBarUI extends BasicScrollBarUI
    {
        @Override
        protected void configureScrollBarColors()
        {
            thumbColor = ColorScheme.DARKER_GRAY_HOVER_COLOR;
            trackColor = ColorScheme.DARK_GRAY_COLOR;
        }

        @Override
        protected JButton createDecreaseButton(int orientation)
        {
            return createZeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation)
        {
            return createZeroButton();
        }

        @Override
        protected void paintTrack(Graphics graphics, JComponent component, Rectangle trackBounds)
        {
            graphics.setColor(ColorScheme.DARK_GRAY_COLOR);
            graphics.fillRect(trackBounds.x, trackBounds.y, trackBounds.width, trackBounds.height);
        }

        @Override
        protected void paintThumb(Graphics graphics, JComponent component, Rectangle thumbBounds)
        {
            if (thumbBounds.isEmpty() || !scrollbar.isEnabled())
            {
                return;
            }

            Graphics2D graphics2D = (Graphics2D) graphics.create();
            graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics2D.setColor(isDragging || isThumbRollover() ? ColorScheme.LIGHT_GRAY_COLOR : ColorScheme.DARKER_GRAY_HOVER_COLOR);

            int thumbWidth = Math.max(4, thumbBounds.width - 4);
            int thumbX = thumbBounds.x + ((thumbBounds.width - thumbWidth) / 2);
            int thumbY = thumbBounds.y + 2;
            int thumbHeight = Math.max(10, thumbBounds.height - 4);
            graphics2D.fillRoundRect(thumbX, thumbY, thumbWidth, thumbHeight, 4, 4);
            graphics2D.dispose();
        }

        private JButton createZeroButton()
        {
            JButton button = new JButton();
            Dimension zero = new Dimension(0, 0);
            button.setPreferredSize(zero);
            button.setMinimumSize(zero);
            button.setMaximumSize(zero);
            button.setBorder(BorderFactory.createEmptyBorder());
            button.setFocusable(false);
            return button;
        }
    }

    private static class NothingIcon implements Icon
    {
        private static final int ICON_SIZE = 16;

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y)
        {
            Graphics2D graphics2D = (Graphics2D) graphics.create();
            graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics2D.setColor(new Color(185, 49, 49));
            graphics2D.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

            int inset = 2;
            int diameter = ICON_SIZE - (inset * 2) - 1;
            graphics2D.drawOval(x + inset, y + inset, diameter, diameter);
            graphics2D.drawLine(x + 4, y + ICON_SIZE - 4, x + ICON_SIZE - 4, y + 4);
            graphics2D.dispose();
        }

        @Override
        public int getIconWidth()
        {
            return ICON_SIZE;
        }

        @Override
        public int getIconHeight()
        {
            return ICON_SIZE;
        }
    }
}
