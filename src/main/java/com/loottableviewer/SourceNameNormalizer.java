package com.loottableviewer;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.inject.Singleton;

@Singleton
public class SourceNameNormalizer
{
    private final Map<String, String> aliases = new HashMap<>();

    public SourceNameNormalizer()
    {
        aliases.put("cox", "Chambers of Xeric");
        aliases.put("chambers of xeric chest", "Chambers of Xeric");
        aliases.put("chambers of xeric", "Chambers of Xeric");
        aliases.put("tob", "Theatre of Blood");
        aliases.put("theatre of blood", "Theatre of Blood");
        aliases.put("toa", "Tombs of Amascut");
        aliases.put("tombs of amascut", "Tombs of Amascut");
        aliases.put("barrows chest", "Chest");
        aliases.put("barrows", "Chest");
        aliases.put("reward casket (easy)", "Reward casket (easy)");
        aliases.put("reward casket (medium)", "Reward casket (medium)");
        aliases.put("reward casket (hard)", "Reward casket (hard)");
        aliases.put("reward casket (elite)", "Reward casket (elite)");
        aliases.put("reward casket (master)", "Reward casket (master)");
    }

    public String normalize(String sourceName)
    {
        if (sourceName == null || sourceName.trim().isEmpty())
        {
            return "";
        }

        String trimmed = sourceName.trim();
        String alias = aliases.get(trimmed.toLowerCase(Locale.ENGLISH));
        return alias != null ? alias : trimmed;
    }
}
