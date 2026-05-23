package com.loottableviewer;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class LootTableViewerLauncher
{
    public static void main(String[] args) throws Exception
    {
        ExternalPluginManager.loadBuiltin(LootTableViewerPlugin.class);
        RuneLite.main(args);
    }
}
