package com.loottableviewer;

import com.google.inject.Provides;
import com.loottableviewer.model.DropEntry;
import com.loottableviewer.model.LootLookupResult;
import com.loottableviewer.model.ReceivedItem;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ItemComposition;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

@PluginDescriptor(
    name = "Loot Table Viewer",
    description = "Displays recent loot and wiki drop rates beside received items",
    tags = {"loot", "drops", "wiki", "raids", "chest"}
)
public class LootTableViewerPlugin extends Plugin
{
    private static final DateTimeFormatter HISTORY_TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm:ss a");

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private ItemManager itemManager;

    @Inject
    private LootTableViewerPanel panel;

    @Inject
    private LootTableLookupService lookupService;

    @Inject
    private SourceNameNormalizer sourceNameNormalizer;

    @Inject
    private LootTableViewerConfig config;

    private final Map<String, LootLookupResult> cache = new ConcurrentHashMap<>();
    private volatile ExecutorService executor;
    private NavigationButton navigationButton;

    @Provides
    LootTableViewerConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LootTableViewerConfig.class);
    }

    @Override
    protected void startUp()
    {
        executor = Executors.newSingleThreadExecutor();

        BufferedImage icon = buildNavigationIcon();
        navigationButton = NavigationButton.builder()
            .tooltip("Loot Table Viewer")
            .icon(icon)
            .priority(7)
            .panel(panel)
            .build();

        clientToolbar.addNavigation(navigationButton);
    }

    private BufferedImage buildNavigationIcon()
    {
        BufferedImage icon = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = icon.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        graphics.setColor(new Color(18, 14, 10, 125));
        graphics.fillOval(3, 25, 26, 5);

        graphics.setColor(new Color(74, 43, 22));
        graphics.fillRoundRect(3, 12, 26, 16, 4, 4);
        graphics.setColor(new Color(183, 111, 33));
        graphics.fillRoundRect(4, 5, 24, 11, 6, 6);
        graphics.setColor(new Color(121, 70, 27));
        graphics.fillRect(4, 15, 24, 12);

        graphics.setColor(new Color(232, 162, 45));
        graphics.fillRect(5, 15, 22, 3);
        graphics.fillRect(8, 7, 3, 20);
        graphics.fillRect(21, 7, 3, 20);

        graphics.setColor(new Color(246, 199, 72));
        graphics.fillRoundRect(14, 18, 5, 6, 2, 2);

        graphics.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        graphics.setColor(new Color(32, 23, 15));
        graphics.drawRoundRect(3, 5, 26, 23, 5, 5);
        graphics.drawLine(4, 16, 28, 16);

        graphics.dispose();
        return icon;
    }

    @Override
    protected void shutDown()
    {
        if (navigationButton != null)
        {
            clientToolbar.removeNavigation(navigationButton);
            navigationButton = null;
        }

        cache.clear();
        ExecutorService currentExecutor = executor;
        executor = null;
        if (currentExecutor != null)
        {
            currentExecutor.shutdownNow();
        }

        SwingUtilities.invokeLater(panel::clearHistory);
    }

    @Subscribe
    public void onLootReceived(LootReceived event)
    {
        if (event == null || event.getItems() == null || event.getItems().isEmpty())
        {
            return;
        }

        final String sourceName = event.getName() == null || event.getName().isBlank()
            ? "Unknown source"
            : event.getName();

        final List<ReceivedItem> receivedItems = mapReceivedItems(event);
        if (receivedItems.isEmpty())
        {
            return;
        }

        final String receivedAtText = LocalTime.now().format(HISTORY_TIME_FORMAT);
        final boolean showReceivedItems = config == null || config.showReceivedItems();
        final String normalizedSourceName = sourceNameNormalizer.normalize(sourceName);
        final String sourceType = determineSourceType(sourceName);
        final String cacheKey = cacheKey(normalizedSourceName, sourceType);
        final List<LootTableViewerPanel.ReceivedLootRow> initialRows = buildReceivedRows(receivedItems, null);
        if (initialRows.isEmpty())
        {
            return;
        }

        SwingUtilities.invokeLater(() ->
            panel.addHistoryEntry(new LootTableViewerPanel.LootHistoryEntry(
                0,
                cacheKey,
                sourceName,
                receivedAtText,
                "",
                "",
                initialRows,
                Collections.emptyList(),
                "Looking up OSRS Wiki drop data...",
                showReceivedItems
            ))
        );

        LootLookupResult cached = cache.get(cacheKey);
        if (cached != null)
        {
            updateHistoryFromResult(cacheKey, receivedItems, cached);
            return;
        }

        ExecutorService currentExecutor = executor;
        if (currentExecutor == null || currentExecutor.isShutdown())
        {
            updateHistoryStatus(cacheKey, "Wiki lookup unavailable while plugin is stopping.");
            return;
        }

        CompletableFuture
                .supplyAsync(() ->
                {
                    return lookupService.lookup(
                            sourceName,
                            normalizedSourceName,
                            sourceType,
                            receivedItems,
                            stageMessage -> updateHistoryStatusIfCurrent(currentExecutor, cacheKey, stageMessage)
                    );
                }, currentExecutor)
                .thenAccept(result ->
                {
                    cache.put(cacheKey, result);
                    if (isExecutorCurrent(currentExecutor))
                    {
                        updateHistoryFromResult(cacheKey, receivedItems, result);
                    }
                })
                .exceptionally(ex ->
                {
                    if (isExecutorCurrent(currentExecutor))
                    {
                        updateHistoryStatus(cacheKey, "Wiki lookup failed: " + safeMessage(ex));
                    }
                    return null;
                });
    }

    private void updateHistoryFromResult(String sourceKey, List<ReceivedItem> receivedItems, LootLookupResult result)
    {
        List<LootTableViewerPanel.ReceivedLootRow> receivedRows = buildReceivedDropRateRows(receivedItems, result.getDrops());
        SwingUtilities.invokeLater(() ->
            panel.updateSourceEntry(
                sourceKey,
                result.getWikiPageTitle(),
                result.getWikiUrl(),
                receivedRows,
                result.getDrops(),
                result.getStatusMessage()
            )
        );
    }

    private void updateHistoryStatusIfCurrent(ExecutorService currentExecutor, String sourceKey, String stageMessage)
    {
        if (isExecutorCurrent(currentExecutor))
        {
            updateHistoryStatus(sourceKey, stageMessage);
        }
    }

    private void updateHistoryStatus(String sourceKey, String status)
    {
        SwingUtilities.invokeLater(() ->
            panel.updateSourceEntry(sourceKey, null, null, null, null, status)
        );
    }

    private boolean isExecutorCurrent(ExecutorService currentExecutor)
    {
        return currentExecutor != null && currentExecutor == executor && !currentExecutor.isShutdown();
    }

    private String cacheKey(String normalizedSourceName, String sourceType)
    {
        return (normalizedSourceName == null ? "" : normalizedSourceName.toLowerCase(Locale.ENGLISH))
            + "|"
            + (sourceType == null ? "" : sourceType);
    }

    private String safeMessage(Throwable throwable)
    {
        if (throwable == null)
        {
            return "unknown error";
        }

        Throwable cause = throwable.getCause() == null ? throwable : throwable.getCause();
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private List<LootTableViewerPanel.ReceivedLootRow> buildReceivedRows(List<ReceivedItem> receivedItems, List<DropEntry> drops)
    {
        List<LootTableViewerPanel.ReceivedLootRow> rows = new ArrayList<>();

        for (ReceivedItem item : receivedItems)
        {
            long unitPrice = itemManager.getItemPrice(item.getItemId());
            long totalPrice = unitPrice * Math.max(item.getQuantity(), 1);
            long highAlchTotal = highAlchPrice(item.getItemId()) * Math.max(item.getQuantity(), 1);
            String dropRate = findDropRateForItem(item.getItemId(), item.getItemName(), drops);

            rows.add(new LootTableViewerPanel.ReceivedLootRow(
                item.getItemId(),
                item.getItemName(),
                item.getQuantity(),
                formatPrice(totalPrice),
                dropRate,
                totalPrice,
                formatPrice(highAlchTotal),
                highAlchTotal
            ));
        }

        return rows;
    }

    private List<LootTableViewerPanel.ReceivedLootRow> buildReceivedDropRateRows(List<ReceivedItem> receivedItems, List<DropEntry> drops)
    {
        List<LootTableViewerPanel.ReceivedLootRow> rows = new ArrayList<>();

        for (ReceivedItem item : receivedItems)
        {
            String dropRate = findDropRateForItem(item.getItemId(), item.getItemName(), drops);
            rows.add(new LootTableViewerPanel.ReceivedLootRow(
                item.getItemId(),
                item.getItemName(),
                item.getQuantity(),
                "",
                dropRate
            ));
        }

        return rows;
    }

    private long highAlchPrice(int itemId)
    {
        ItemComposition itemComposition = itemManager.getItemComposition(itemId);
        return itemComposition == null ? 0 : itemComposition.getHaPrice();
    }

    private String findDropRateForItem(int itemId, String itemName, List<DropEntry> drops)
    {
        if (drops == null || drops.isEmpty())
        {
            return "";
        }

        for (DropEntry drop : drops)
        {
            if (drop.getItemId() > 0 && itemId > 0 && drop.getItemId() == itemId)
            {
                return safeDropRate(drop);
            }
        }

        String normalizedItemName = normalizeItemName(itemName);
        for (DropEntry drop : drops)
        {
            if (normalizeItemName(drop.getItemName()).equals(normalizedItemName))
            {
                return safeDropRate(drop);
            }
        }

        return "";
    }

    private String safeDropRate(DropEntry drop)
    {
        if (drop == null)
        {
            return "";
        }

        if (drop.getDropRateText() != null && !drop.getDropRateText().isBlank())
        {
            return drop.getDropRateText();
        }

        if (drop.getRarity() != null && !drop.getRarity().isBlank())
        {
            return drop.getRarity();
        }

        return "";
    }

    private String normalizeItemName(String value)
    {
        if (value == null)
        {
            return "";
        }

        return value
            .toLowerCase(Locale.ENGLISH)
            .replace(" (noted)", "")
            .replace("(noted)", "")
            .replace('\u00A0', ' ')
            .replaceAll("\\s+", " ")
            .trim();
    }

    private List<ReceivedItem> mapReceivedItems(LootReceived event)
    {
        List<ReceivedItem> items = new ArrayList<>();
        if (event.getItems() == null)
        {
            return items;
        }

        event.getItems().forEach(stack ->
        {
            int itemId = stack.getId();
            ItemComposition itemComposition = itemManager.getItemComposition(itemId);
            String name = itemComposition == null ? ("Item " + itemId) : itemComposition.getName();
            items.add(new ReceivedItem(itemId, name, stack.getQuantity()));
        });
        return items;
    }

    private String determineSourceType(String sourceName)
    {
        if (sourceName == null)
        {
            return "UNKNOWN";
        }

        String lower = sourceName.toLowerCase(Locale.ENGLISH);
        if (lower.contains("chest") || lower.contains("casket") || lower.contains("reward"))
        {
            return "CHEST";
        }
        if (lower.contains("chambers") || lower.contains("xeric") || lower.contains("theatre") || lower.contains("amascut") || lower.equals("cox") || lower.equals("tob") || lower.equals("toa"))
        {
            return "RAID";
        }
        return "MONSTER";
    }

    private String formatPrice(long price)
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
