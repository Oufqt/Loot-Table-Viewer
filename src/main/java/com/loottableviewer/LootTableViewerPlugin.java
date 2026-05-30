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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.InteractingChanged;
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
    private Client client;

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
    private final Map<String, CompletableFuture<LootLookupResult>> inFlightLookups = new ConcurrentHashMap<>();
    private final Map<String, DropRateLookup> dropRateCache = new ConcurrentHashMap<>();
    private final Map<Integer, ItemInfo> itemInfoCache = new ConcurrentHashMap<>();
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
        inFlightLookups.clear();
        dropRateCache.clear();
        itemInfoCache.clear();
        ExecutorService currentExecutor = executor;
        executor = null;
        if (currentExecutor != null)
        {
            currentExecutor.shutdownNow();
        }

        SwingUtilities.invokeLater(panel::clearHistory);
    }

    @Subscribe
    public void onInteractingChanged(InteractingChanged event)
    {
        if (event == null)
        {
            return;
        }

        Player localPlayer = client.getLocalPlayer();
        Actor target = event.getTarget();
        if (localPlayer == null || event.getSource() != localPlayer || !(target instanceof NPC))
        {
            return;
        }

        String sourceName = target.getName();
        if (sourceName == null || sourceName.isBlank())
        {
            return;
        }

        prefetchLookup(sourceName);
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

        LootLookupResult cached = cache.get(cacheKey);
        DropRateLookup initialDropRateLookup = cached == null
            ? DropRateLookup.EMPTY
            : dropRateCache.computeIfAbsent(cacheKey, key -> DropRateLookup.from(cached.getDrops()));
        final List<LootTableViewerPanel.ReceivedLootRow> initialRows = buildReceivedRows(receivedItems, initialDropRateLookup);
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
                cached == null ? "" : cached.getWikiPageTitle(),
                cached == null ? "" : cached.getWikiUrl(),
                initialRows,
                cached == null ? Collections.emptyList() : cached.getDrops(),
                cached == null ? "" : cached.getStatusMessage(),
                showReceivedItems
            ))
        );

        if (cached != null)
        {
            return;
        }

        ExecutorService currentExecutor = executor;
        if (currentExecutor == null || currentExecutor.isShutdown())
        {
            updateHistoryStatus(cacheKey, "Wiki lookup unavailable while plugin is stopping.");
            return;
        }

        CompletableFuture<LootLookupResult> lookupFuture = lookupOrStart(
            cacheKey,
            sourceName,
            normalizedSourceName,
            sourceType,
            receivedItems,
            currentExecutor,
            null
        );

        lookupFuture
                .thenAccept(result ->
                {
                    if (result != null && isExecutorCurrent(currentExecutor))
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

    private void prefetchLookup(String sourceName)
    {
        String normalizedSourceName = sourceNameNormalizer.normalize(sourceName);
        String sourceType = determineSourceType(sourceName);
        String cacheKey = cacheKey(normalizedSourceName, sourceType);
        if (cache.containsKey(cacheKey) || inFlightLookups.containsKey(cacheKey))
        {
            return;
        }

        ExecutorService currentExecutor = executor;
        if (currentExecutor == null || currentExecutor.isShutdown())
        {
            return;
        }

        lookupOrStart(
            cacheKey,
            sourceName,
            normalizedSourceName,
            sourceType,
            Collections.emptyList(),
            currentExecutor,
            null
        );
    }

    private CompletableFuture<LootLookupResult> lookupOrStart(
        String cacheKey,
        String sourceName,
        String normalizedSourceName,
        String sourceType,
        List<ReceivedItem> receivedItems,
        ExecutorService currentExecutor,
        Consumer<String> statusCallback)
    {
        return inFlightLookups.computeIfAbsent(cacheKey, key ->
            CompletableFuture
                .supplyAsync(() -> lookupService.lookup(
                    sourceName,
                    normalizedSourceName,
                    sourceType,
                    receivedItems,
                    statusCallback
                ), currentExecutor)
                .whenComplete((result, ex) ->
                {
                    inFlightLookups.remove(key);
                    if (ex == null && result != null)
                    {
                        cache.put(key, result);
                        dropRateCache.put(key, DropRateLookup.from(result.getDrops()));
                    }
                })
        );
    }

    private void updateHistoryFromResult(String sourceKey, List<ReceivedItem> receivedItems, LootLookupResult result)
    {
        DropRateLookup dropRateLookup = dropRateCache.computeIfAbsent(sourceKey, key -> DropRateLookup.from(result.getDrops()));
        List<LootTableViewerPanel.ReceivedLootRow> receivedRows = buildReceivedDropRateRows(receivedItems, dropRateLookup);
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

    private List<LootTableViewerPanel.ReceivedLootRow> buildReceivedRows(List<ReceivedItem> receivedItems, DropRateLookup dropRateLookup)
    {
        List<LootTableViewerPanel.ReceivedLootRow> rows = new ArrayList<>();

        for (ReceivedItem item : receivedItems)
        {
            ItemInfo itemInfo = itemInfo(item.getItemId());
            long unitPrice = itemInfo.getGePrice();
            long totalPrice = unitPrice * Math.max(item.getQuantity(), 1);
            long highAlchTotal = itemInfo.getHighAlchPrice() * Math.max(item.getQuantity(), 1);
            String dropRate = dropRateLookup.find(item.getItemId(), item.getItemName());

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

    private List<LootTableViewerPanel.ReceivedLootRow> buildReceivedDropRateRows(List<ReceivedItem> receivedItems, DropRateLookup dropRateLookup)
    {
        List<LootTableViewerPanel.ReceivedLootRow> rows = new ArrayList<>();

        for (ReceivedItem item : receivedItems)
        {
            String dropRate = dropRateLookup.find(item.getItemId(), item.getItemName());
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

    private ItemInfo itemInfo(int itemId)
    {
        if (itemId <= 0)
        {
            return new ItemInfo("Item " + itemId, 0, 0);
        }

        return itemInfoCache.computeIfAbsent(itemId, id ->
        {
            ItemComposition itemComposition = itemManager.getItemComposition(id);
            String itemName = itemComposition == null ? ("Item " + id) : itemComposition.getName();
            long gePrice = itemManager.getItemPrice(id);
            long highAlchPrice = itemComposition == null ? 0 : itemComposition.getHaPrice();
            return new ItemInfo(itemName, gePrice, highAlchPrice);
        });
    }

    private static String safeDropRate(DropEntry drop)
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

    private static String normalizeItemName(String value)
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
            items.add(new ReceivedItem(itemId, itemInfo(itemId).getName(), stack.getQuantity()));
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

    private static final class DropRateLookup
    {
        private static final DropRateLookup EMPTY = new DropRateLookup(Collections.emptyMap(), Collections.emptyMap());

        private final Map<Integer, String> byItemId;
        private final Map<String, String> byName;

        private DropRateLookup(Map<Integer, String> byItemId, Map<String, String> byName)
        {
            this.byItemId = byItemId;
            this.byName = byName;
        }

        private static DropRateLookup from(List<DropEntry> drops)
        {
            if (drops == null || drops.isEmpty())
            {
                return EMPTY;
            }

            Map<Integer, String> byItemId = new HashMap<>();
            Map<String, String> byName = new HashMap<>();

            for (DropEntry drop : drops)
            {
                String rate = safeDropRate(drop);
                if (rate.isBlank())
                {
                    continue;
                }

                if (drop.getItemId() > 0)
                {
                    byItemId.putIfAbsent(drop.getItemId(), rate);
                }

                String normalizedName = normalizeItemName(drop.getItemName());
                if (!normalizedName.isBlank())
                {
                    byName.putIfAbsent(normalizedName, rate);
                }
            }

            return new DropRateLookup(Collections.unmodifiableMap(byItemId), Collections.unmodifiableMap(byName));
        }

        private String find(int itemId, String itemName)
        {
            if (itemId > 0)
            {
                String rate = byItemId.get(itemId);
                if (rate != null)
                {
                    return rate;
                }
            }

            return byName.getOrDefault(normalizeItemName(itemName), "");
        }
    }

    private static final class ItemInfo
    {
        private final String name;
        private final long gePrice;
        private final long highAlchPrice;

        private ItemInfo(String name, long gePrice, long highAlchPrice)
        {
            this.name = name == null || name.isBlank() ? "Unknown item" : name;
            this.gePrice = gePrice;
            this.highAlchPrice = highAlchPrice;
        }

        private String getName()
        {
            return name;
        }

        private long getGePrice()
        {
            return gePrice;
        }

        private long getHighAlchPrice()
        {
            return highAlchPrice;
        }
    }
}
