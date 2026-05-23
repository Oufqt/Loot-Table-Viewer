package com.loottableviewer;

import com.loottableviewer.model.DropEntry;
import com.loottableviewer.model.LootLookupResult;
import com.loottableviewer.model.ReceivedItem;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class LootTableLookupService
{
    private final OsrsWikiClient wikiClient;
    private final WikiDropParser parser;

    @Inject
    public LootTableLookupService(OsrsWikiClient wikiClient, WikiDropParser parser)
    {
        this.wikiClient = wikiClient;
        this.parser = parser;
    }

    public LootLookupResult lookup(
            String sourceName,
            String normalizedSourceName,
            String sourceType,
            List<ReceivedItem> receivedItems,
            Consumer<String> statusCallback)
    {
        try
        {
            if (statusCallback != null)
            {
                statusCallback.accept("Searching OSRS Wiki...");
            }

            OsrsWikiClient.WikiPageData page = wikiClient.findPage(normalizedSourceName);
            if (page == null)
            {
                if (statusCallback != null)
                {
                    statusCallback.accept("No OSRS Wiki page found.");
                }
                return LootLookupResult.empty(sourceName, normalizedSourceName, sourceType, receivedItems, "No OSRS Wiki page found.");
            }

            if (statusCallback != null)
            {
                statusCallback.accept("Page found: " + page.getTitle() + ". Parsing loot rows...");
            }

            String wikitext = page.getWikitext();
            List<DropEntry> entries = parser.parse(wikitext);
            String status = buildStatusMessage(page.getTitle(), wikitext, entries);

            if (statusCallback != null)
            {
                statusCallback.accept(status);
            }

            return new LootLookupResult(
                    sourceName,
                    normalizedSourceName,
                    sourceType,
                    page.getTitle(),
                    page.getUrl(),
                    receivedItems,
                    entries,
                    status
            );
        }
        catch (IOException ex)
        {
            String message = "Wiki lookup failed: " + ex.getMessage();
            if (statusCallback != null)
            {
                statusCallback.accept(message);
            }
            return LootLookupResult.empty(sourceName, normalizedSourceName, sourceType, receivedItems, message);
        }
        catch (RuntimeException ex)
        {
            String message = "Wiki parsing failed: " + ex.getMessage();
            if (statusCallback != null)
            {
                statusCallback.accept(message);
            }
            return LootLookupResult.empty(sourceName, normalizedSourceName, sourceType, receivedItems, message);
        }
    }

    private String buildStatusMessage(String pageTitle, String wikitext, List<DropEntry> entries)
    {
        if (entries != null && !entries.isEmpty())
        {
            return "Loaded " + entries.size() + " wiki drop rows.";
        }

        if (wikitext == null || wikitext.isBlank())
        {
            return "Wiki page found, but no wikitext was returned.";
        }

        String lower = wikitext.toLowerCase(Locale.ENGLISH);
        if (lower.contains("drop") || lower.contains("loot") || lower.contains("reward"))
        {
            return "Wiki page found (" + pageTitle + "), but no recognizable loot-table rows were found.";
        }

        return "Wiki page found, but no drop rows were parsed.";
    }
}