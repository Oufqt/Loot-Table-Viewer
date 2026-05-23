package com.loottableviewer.model;

import java.util.Collections;
import java.util.List;

public class LootLookupResult
{
    private final String sourceName;
    private final String normalizedSourceName;
    private final String sourceType;
    private final String wikiPageTitle;
    private final String wikiUrl;
    private final List<ReceivedItem> receivedItems;
    private final List<DropEntry> drops;
    private final String statusMessage;

    public LootLookupResult(
        String sourceName,
        String normalizedSourceName,
        String sourceType,
        String wikiPageTitle,
        String wikiUrl,
        List<ReceivedItem> receivedItems,
        List<DropEntry> drops,
        String statusMessage)
    {
        this.sourceName = sourceName;
        this.normalizedSourceName = normalizedSourceName;
        this.sourceType = sourceType;
        this.wikiPageTitle = wikiPageTitle;
        this.wikiUrl = wikiUrl;
        this.receivedItems = receivedItems == null ? Collections.emptyList() : Collections.unmodifiableList(receivedItems);
        this.drops = drops == null ? Collections.emptyList() : Collections.unmodifiableList(drops);
        this.statusMessage = statusMessage;
    }

    public static LootLookupResult empty(String sourceName, String normalizedSourceName, String sourceType, List<ReceivedItem> receivedItems, String statusMessage)
    {
        return new LootLookupResult(
            sourceName,
            normalizedSourceName,
            sourceType,
            "",
            "",
            receivedItems,
            Collections.emptyList(),
            statusMessage
        );
    }

    public String getSourceName()
    {
        return sourceName;
    }

    public String getNormalizedSourceName()
    {
        return normalizedSourceName;
    }

    public String getSourceType()
    {
        return sourceType;
    }

    public String getWikiPageTitle()
    {
        return wikiPageTitle;
    }

    public String getWikiUrl()
    {
        return wikiUrl;
    }

    public List<ReceivedItem> getReceivedItems()
    {
        return receivedItems;
    }

    public List<DropEntry> getDrops()
    {
        return drops;
    }

    public String getStatusMessage()
    {
        return statusMessage;
    }
}
