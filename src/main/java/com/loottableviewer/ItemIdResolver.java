package com.loottableviewer;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import net.runelite.api.ItemID;

final class ItemIdResolver
{
    private static final Pattern ITEM_VARIANT_SUFFIX = Pattern.compile("_[0-9]+$");
    private static final Map<String, Candidate> ITEM_IDS_BY_NAME = buildItemIdsByName();

    private ItemIdResolver()
    {
    }

    static int resolve(String itemName)
    {
        Candidate candidate = ITEM_IDS_BY_NAME.get(normalizeItemName(itemName));
        return candidate == null ? 0 : candidate.itemId;
    }

    static String normalizeItemName(String itemName)
    {
        if (itemName == null)
        {
            return "";
        }

        return itemName
            .toLowerCase(Locale.ENGLISH)
            .replace('\u00A0', ' ')
            .replace('\u2019', '\'')
            .replace("'", "")
            .replaceAll("(?i)\\s*\\((?:m|noted)\\)\\s*", " ")
            .replaceAll("[^a-z0-9]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private static Map<String, Candidate> buildItemIdsByName()
    {
        Map<String, Candidate> itemIdsByName = new HashMap<>();
        for (Field field : ItemID.class.getFields())
        {
            int modifiers = field.getModifiers();
            if (!Modifier.isStatic(modifiers) || field.getType() != int.class)
            {
                continue;
            }

            try
            {
                String constantName = field.getName();
                int itemId = field.getInt(null);
                register(itemIdsByName, constantName, constantName, itemId);

                String baseName = ITEM_VARIANT_SUFFIX.matcher(constantName).replaceFirst("");
                if (!baseName.equals(constantName))
                {
                    register(itemIdsByName, baseName, constantName, itemId);
                }
            }
            catch (IllegalAccessException ignored)
            {
            }
        }

        return Collections.unmodifiableMap(itemIdsByName);
    }

    private static void register(Map<String, Candidate> itemIdsByName, String displayName, String constantName, int itemId)
    {
        String normalizedName = normalizeItemName(displayName.replace('_', ' '));
        if (normalizedName.isBlank())
        {
            return;
        }

        Candidate candidate = new Candidate(itemId, priority(constantName));
        Candidate existing = itemIdsByName.get(normalizedName);
        if (existing == null || candidate.priority < existing.priority)
        {
            itemIdsByName.put(normalizedName, candidate);
        }
    }

    private static int priority(String constantName)
    {
        return ITEM_VARIANT_SUFFIX.matcher(constantName).find() ? 1 : 0;
    }

    private static final class Candidate
    {
        private final int itemId;
        private final int priority;

        private Candidate(int itemId, int priority)
        {
            this.itemId = itemId;
            this.priority = priority;
        }
    }
}
