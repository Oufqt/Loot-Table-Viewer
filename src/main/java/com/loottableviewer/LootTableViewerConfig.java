package com.loottableviewer;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("loottableviewer")
public interface LootTableViewerConfig extends Config
{
    @ConfigItem(
        keyName = "maxDropRows",
        name = "Max drop rows",
        description = "Maximum number of wiki drop rows shown in the panel"
    )
    default int maxDropRows()
    {
        return 30;
    }

    @ConfigItem(
        keyName = "showReceivedItems",
        name = "Show received items",
        description = "Show the exact items from the latest loot event"
    )
    default boolean showReceivedItems()
    {
        return true;
    }

    @ConfigItem(
        keyName = "includeAlwaysDrops",
        name = "Include always drops",
        description = "Include always/common guaranteed entries from the wiki output"
    )
    default boolean includeAlwaysDrops()
    {
        return true;
    }

    @ConfigItem(
        keyName = "wikiUserAgent",
        name = "Wiki user-agent",
        description = "User-Agent sent to the OSRS Wiki API"
    )
    default String wikiUserAgent()
    {
        return "RuneLite Loot Table Viewer";
    }
}
