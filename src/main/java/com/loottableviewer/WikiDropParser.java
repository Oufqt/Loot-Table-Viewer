package com.loottableviewer;

import com.loottableviewer.model.DropEntry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class WikiDropParser
{
    private static final Pattern DROPS_TABLE_HEAD_PATTERN = Pattern.compile("\\{\\{\\s*DropsTableHead\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_LINE_CLUE_PATTERN = Pattern.compile("\\{\\{\\s*DropsLineClue\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_LINE_PATTERN = Pattern.compile("\\{\\{\\s*(?:DropsLine|DropLine)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ONE_OVER_PATTERN = Pattern.compile("\\b1\\s*/\\s*[0-9,]+(?:\\.[0-9]+)?\\b");
    private static final Pattern FRACTION_PATTERN = Pattern.compile("\\b[0-9,]+\\s*/\\s*[0-9,]+(?:\\.[0-9]+)?\\b");
    private static final Pattern DICE_PATTERN = Pattern.compile("\\b1\\s+in\\s+[0-9,]+\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERCENT_PATTERN = Pattern.compile("\\b[0-9]+(?:\\.[0-9]+)?%\\b");
    private static final Pattern ALWAYS_PATTERN = Pattern.compile("\\b(always|guaranteed|common|uncommon|rare|very rare)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIKI_HEADING_PATTERN = Pattern.compile("(?m)^\\s*(={2,6})\\s*(.*?)\\s*\\1\\s*$");
    private static final Pattern WIKI_EXPR_RATE_PATTERN = Pattern.compile("1\\s*/\\s*\\{\\{\\s*#expr\\s*:\\s*1\\s*/\\s*\\((.*?)\\)\\s*round\\s*1\\s*\\}\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern WIKI_EXPR_TEMPLATE_PATTERN = Pattern.compile("\\{\\{\\s*#expr\\b.*?\\}\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern RARITY_TEMPLATE_DENOMINATOR_PATTERN = Pattern.compile("\\{\\{\\s*[^{}|]*rarity\\s*\\|\\s*([0-9,]+(?:\\.[0-9]+)?)\\s*(?:\\|[^{}]*)?\\}\\}", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXACT_FRACTION_PATTERN = Pattern.compile("([0-9,]+)\\s*/\\s*([0-9,]+(?:\\.[0-9]+)?)");

    private final LootTableViewerConfig config;

    @Inject
    public WikiDropParser(LootTableViewerConfig config)
    {
        this.config = config;
    }

    public List<DropEntry> parse(String wikitext)
    {
        List<DropEntry> entries = new ArrayList<>();
        if (wikitext == null || wikitext.isBlank())
        {
            return entries;
        }

        parseTemplateStyleDrops(wikitext, entries);
        if (entries.isEmpty())
        {
            parseTableFallback(wikitext, entries);
        }

        return entries;
    }

    private void parseTemplateStyleDrops(String wikitext, List<DropEntry> entries)
    {
        String currentCategory = "Other";
        String activeHeadingCategory = "";
        int index = 0;

        while (index < wikitext.length() && entries.size() < config.maxDropRows())
        {
            int headStart = findNextDropsTableHeadStart(wikitext, index);
            int lineStart = findNextDropLineStart(wikitext, index);

            if (headStart < 0 && lineStart < 0)
            {
                break;
            }

            int nextStart = headStart >= 0 && (lineStart < 0 || headStart < lineStart) ? headStart : lineStart;
            String headingCategory = lastWikiHeading(wikitext, index, nextStart);
            if (!headingCategory.isBlank())
            {
                activeHeadingCategory = headingCategory;
            }

            if (headStart >= 0 && (lineStart < 0 || headStart < lineStart))
            {
                int headEnd = findTemplateEnd(wikitext, headStart);
                if (headEnd < 0)
                {
                    break;
                }

                String category = extractDropsTableCategory(wikitext.substring(headStart, headEnd));
                currentCategory = resolveCategory(activeHeadingCategory, category);
                index = headEnd;
                continue;
            }

            if (!headingCategory.isBlank())
            {
                currentCategory = resolveCategory(activeHeadingCategory, "");
            }

            int lineEnd = findTemplateEnd(wikitext, lineStart);
            if (lineEnd < 0)
            {
                break;
            }

            String block = wikitext.substring(lineStart, lineEnd);
            addEntryFromBlock(block, currentCategory, entries);
            index = lineEnd;
        }
    }

    private void parseTableFallback(String wikitext, List<DropEntry> entries)
    {
        String[] lines = wikitext.split("\\R");
        String currentCategory = "Other";
        boolean inDropSection = false;

        for (String rawLine : lines)
        {
            if (entries.size() >= config.maxDropRows())
            {
                break;
            }

            String line = rawLine.trim();
            String lower = line.toLowerCase(Locale.ENGLISH);

            if (lower.startsWith("==") && (lower.contains("drop") || lower.contains("loot") || lower.contains("reward")))
            {
                inDropSection = true;
                currentCategory = cleanWikiHeading(line);
                continue;
            }

            if (inDropSection && isWikiHeading(line))
            {
                currentCategory = cleanWikiHeading(line);
                continue;
            }

            if (inDropSection && (line.startsWith("{{DropsLine") || line.startsWith("{{DropLine")))
            {
                int end = findTemplateEnd(line, 0);
                String block = end > 0 ? line.substring(0, end) : line;
                addEntryFromBlock(block, currentCategory, entries);
            }
        }
    }

    private void addEntryFromBlock(String block, String currentCategory, List<DropEntry> entries)
    {
        String name = clean(extractParam(block, "name", "item", "drop", "reward"));
        String itemIdRaw = clean(extractParam(block, "id", "itemid"));
        String type = clean(extractParam(block, "type"));
        String quantity = normalizeQuantity(
            extractParam(block, "quantity", "qty", "amount"),
            extractParam(block, "quantitylow", "quantitymin", "minquantity"),
            extractParam(block, "quantityhigh", "quantitymax", "maxquantity")
        );
        String rawRarity = extractParam(block, "rarity", "chance");
        String rawNotes = extractParam(block, "raritynotes", "notes", "text");
        String rarityRaw = clean(rawRarity);
        String notes = clean(rawNotes);

        if (name.isEmpty() && DROP_LINE_CLUE_PATTERN.matcher(block).find())
        {
            name = clueScrollName(type);
        }

        if (name.isEmpty() && itemIdRaw.isEmpty())
        {
            return;
        }

        String dropRate = extractRate(rawRarity, rawNotes);
        String rarityLabel = buildRarityLabel(rarityRaw, dropRate);

        if (!config.includeAlwaysDrops() && isAlwaysDrop(rawRarity, rawNotes))
        {
            return;
        }

        int itemId = parseIntSafely(itemIdRaw);
        entries.add(new DropEntry(itemId, name.isEmpty() ? itemIdRaw : name, currentCategory, dropRate, rarityLabel, notes, quantity));
    }

    private static String clueScrollName(String type)
    {
        String cleanType = type == null ? "" : type.toLowerCase(Locale.ENGLISH).trim();
        if (cleanType.isEmpty())
        {
            return "Clue scroll";
        }

        return "Clue scroll (" + cleanType + ")";
    }

    private static String extractParam(String block, String... keys)
    {
        if (block == null || block.isBlank())
        {
            return "";
        }

        for (String rawPart : splitTopLevelParameters(templateBody(block)))
        {
            int equalsIndex = rawPart.indexOf('=');
            if (equalsIndex < 0)
            {
                continue;
            }

            String key = rawPart.substring(0, equalsIndex).trim().toLowerCase(Locale.ENGLISH);
            for (String expectedKey : keys)
            {
                if (key.equals(expectedKey))
                {
                    return rawPart.substring(equalsIndex + 1).trim();
                }
            }
        }

        return "";
    }

    private static String templateBody(String block)
    {
        String body = block == null ? "" : block.trim();
        if (body.startsWith("{{"))
        {
            body = body.substring(2);
        }

        if (body.endsWith("}}"))
        {
            body = body.substring(0, body.length() - 2);
        }

        return body;
    }

    private static List<String> splitTopLevelParameters(String value)
    {
        List<String> parts = new ArrayList<>();
        if (value == null || value.isEmpty())
        {
            return parts;
        }

        StringBuilder part = new StringBuilder();
        int templateDepth = 0;
        int linkDepth = 0;

        for (int i = 0; i < value.length(); i++)
        {
            char current = value.charAt(i);
            char next = i + 1 < value.length() ? value.charAt(i + 1) : '\0';

            if (current == '{' && next == '{')
            {
                templateDepth++;
                part.append(current).append(next);
                i++;
                continue;
            }

            if (current == '}' && next == '}' && templateDepth > 0)
            {
                templateDepth--;
                part.append(current).append(next);
                i++;
                continue;
            }

            if (current == '[' && next == '[')
            {
                linkDepth++;
                part.append(current).append(next);
                i++;
                continue;
            }

            if (current == ']' && next == ']' && linkDepth > 0)
            {
                linkDepth--;
                part.append(current).append(next);
                i++;
                continue;
            }

            if (current == '|' && templateDepth == 0 && linkDepth == 0)
            {
                parts.add(part.toString());
                part.setLength(0);
                continue;
            }

            part.append(current);
        }

        parts.add(part.toString());
        return parts;
    }

    private static String lastWikiHeading(String text, int fromIndex, int toIndex)
    {
        if (text == null || fromIndex >= toIndex)
        {
            return "";
        }

        int safeFrom = Math.max(0, fromIndex);
        int safeTo = Math.min(text.length(), toIndex);
        if (safeFrom >= safeTo)
        {
            return "";
        }

        Matcher matcher = WIKI_HEADING_PATTERN.matcher(text.substring(safeFrom, safeTo));
        String heading = "";
        while (matcher.find())
        {
            heading = clean(matcher.group(2));
        }
        return heading;
    }

    private static boolean isWikiHeading(String line)
    {
        return line != null && WIKI_HEADING_PATTERN.matcher(line).matches();
    }

    private static String cleanWikiHeading(String line)
    {
        if (line == null)
        {
            return "";
        }

        Matcher matcher = WIKI_HEADING_PATTERN.matcher(line);
        if (matcher.matches())
        {
            return clean(matcher.group(2));
        }

        return clean(line.replace("=", " "));
    }

    private static String normalizeQuantity(String rawQuantity, String rawLowQuantity, String rawHighQuantity)
    {
        String quantity = clean(rawQuantity);
        String lowQuantity = clean(rawLowQuantity);
        String highQuantity = clean(rawHighQuantity);

        if (quantity.isBlank() && !lowQuantity.isBlank() && !highQuantity.isBlank())
        {
            quantity = lowQuantity + "-" + highQuantity;
        }

        if (quantity.isBlank())
        {
            return "";
        }

        return quantity
            .replace('\u2013', '-')
            .replace('\u2014', '-')
            .replaceAll("(?i)\\s+to\\s+", "-")
            .replaceAll("\\s*-\\s*", "-")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private boolean isAlwaysDrop(String rarityRaw, String notes)
    {
        String combined = (rarityRaw == null ? "" : rarityRaw) + " " + (notes == null ? "" : notes);
        return ALWAYS_PATTERN.matcher(combined).find();
    }

    private static String extractRate(String rarity, String notes)
    {
        String expressionRate = findExpressionRate(rarity);
        if (expressionRate != null)
        {
            return expressionRate;
        }

        String fromRarity = findRate(rarity);
        if (fromRarity != null)
        {
            return normalizeRate(fromRarity);
        }

        expressionRate = findExpressionRate(notes);
        if (expressionRate != null)
        {
            return expressionRate;
        }

        String fromNotes = findRate(notes);
        if (fromNotes != null)
        {
            return normalizeRate(fromNotes);
        }

        return clean(rarity);
    }

    private static String findRate(String value)
    {
        if (value == null || value.isEmpty())
        {
            return null;
        }

        String templateRate = findRarityTemplateRate(value);
        if (templateRate != null)
        {
            return templateRate;
        }

        String searchable = WIKI_EXPR_TEMPLATE_PATTERN.matcher(value).replaceAll(" ");

        Matcher oneOverMatcher = ONE_OVER_PATTERN.matcher(searchable);
        if (oneOverMatcher.find())
        {
            return oneOverMatcher.group();
        }

        Matcher fractionMatcher = FRACTION_PATTERN.matcher(searchable);
        if (fractionMatcher.find())
        {
            return fractionMatcher.group();
        }

        Matcher diceMatcher = DICE_PATTERN.matcher(searchable);
        if (diceMatcher.find())
        {
            return diceMatcher.group();
        }

        Matcher percentMatcher = PERCENT_PATTERN.matcher(searchable);
        if (percentMatcher.find())
        {
            return percentMatcher.group();
        }

        return null;
    }

    private static String findRarityTemplateRate(String value)
    {
        Matcher matcher = RARITY_TEMPLATE_DENOMINATOR_PATTERN.matcher(value);
        if (!matcher.find())
        {
            return null;
        }

        return "1/" + matcher.group(1).replace(",", "");
    }

    private static String normalizeRate(String value)
    {
        if (value == null)
        {
            return "";
        }

        Matcher fractionMatcher = EXACT_FRACTION_PATTERN.matcher(value.replaceAll("\\s+", ""));
        if (!fractionMatcher.matches())
        {
            return value.replaceAll("\\s+", "");
        }

        try
        {
            long numerator = Long.parseLong(fractionMatcher.group(1).replace(",", ""));
            BigDecimal denominator = new BigDecimal(fractionMatcher.group(2).replace(",", ""));
            if (numerator <= 1 || denominator.compareTo(BigDecimal.valueOf(numerator)) <= 0)
            {
                return value.replaceAll("\\s+", "");
            }

            BigDecimal simplifiedDenominator = denominator.divide(BigDecimal.valueOf(numerator), 2, RoundingMode.HALF_UP).stripTrailingZeros();
            return "1/" + simplifiedDenominator.toPlainString();
        }
        catch (NumberFormatException | ArithmeticException ex)
        {
            return value.replaceAll("\\s+", "");
        }
    }

    private static String findExpressionRate(String value)
    {
        if (value == null || value.isEmpty())
        {
            return null;
        }

        Matcher matcher = WIKI_EXPR_RATE_PATTERN.matcher(value);
        if (!matcher.find())
        {
            return null;
        }

        long denominator = roundedExpressionDenominator(matcher.group(1));
        return denominator <= 0 ? null : "1/" + denominator;
    }

    private static long roundedExpressionDenominator(String expression)
    {
        if (expression == null || expression.isBlank())
        {
            return 0;
        }

        double probability = 1.0;
        for (String rawFactor : expression.split("\\*"))
        {
            Matcher matcher = EXACT_FRACTION_PATTERN.matcher(rawFactor.trim());
            if (!matcher.matches())
            {
                return 0;
            }

            double numerator = Double.parseDouble(matcher.group(1).replace(",", ""));
            double denominator = Double.parseDouble(matcher.group(2).replace(",", ""));
            if (numerator <= 0 || denominator <= 0)
            {
                return 0;
            }

            probability *= numerator / denominator;
        }

        return probability <= 0 ? 0 : Math.round(1.0 / probability);
    }

    private static String buildRarityLabel(String rarity, String dropRate)
    {
        if (rarity == null || rarity.isEmpty())
        {
            return "";
        }

        if (dropRate != null && !dropRate.isEmpty() && rarity.contains(dropRate))
        {
            return "";
        }

        return rarity;
    }

    private static String extractDropsTableCategory(String value)
    {
        if (value == null || value.isBlank())
        {
            return "Other";
        }

        value = dropsTableHeadBody(value);

        String positionalCategory = "";
        String namedCategory = "";
        String dropVersion = "";

        for (String rawPart : splitTopLevelParameters(value))
        {
            String part = clean(rawPart);
            if (part.isBlank())
            {
                continue;
            }

            int equalsIndex = part.indexOf('=');
            if (equalsIndex < 0)
            {
                if (positionalCategory.isBlank())
                {
                    positionalCategory = part;
                }
                continue;
            }

            String key = part.substring(0, equalsIndex).trim().toLowerCase(Locale.ENGLISH);
            String namedValue = clean(part.substring(equalsIndex + 1));

            if (key.equals("dropversion"))
            {
                dropVersion = namedValue;
            }
            else if ((key.equals("name") || key.equals("category") || key.equals("table")) && namedCategory.isBlank())
            {
                namedCategory = namedValue;
            }
        }

        if (!dropVersion.isBlank())
        {
            return formatDropVersion(dropVersion);
        }

        if (!namedCategory.isBlank())
        {
            return namedCategory;
        }

        return positionalCategory.isBlank() ? "Other" : positionalCategory;
    }

    private static String resolveCategory(String headingCategory, String tableCategory)
    {
        String heading = clean(headingCategory);
        String table = clean(tableCategory);

        if (!heading.isBlank() && !isGenericCategory(heading))
        {
            return heading;
        }

        if (!table.isBlank() && !isGenericCategory(table))
        {
            return table;
        }

        if (!heading.isBlank())
        {
            return heading;
        }

        return table.isBlank() ? "Other" : table;
    }

    private static boolean isGenericCategory(String category)
    {
        String lower = category == null ? "" : category.toLowerCase(Locale.ENGLISH).trim();
        return lower.equals("drops")
            || lower.equals("drop table")
            || lower.equals("loot")
            || lower.equals("loot table")
            || lower.equals("rewards")
            || lower.equals("reward");
    }

    private static String dropsTableHeadBody(String value)
    {
        String body = value.trim();
        Matcher matcher = DROPS_TABLE_HEAD_PATTERN.matcher(body);
        if (matcher.find() && matcher.start() == 0)
        {
            body = body.substring(matcher.end()).trim();
        }

        while (body.endsWith("}}"))
        {
            body = body.substring(0, body.length() - 2).trim();
        }

        if (body.startsWith("|"))
        {
            body = body.substring(1).trim();
        }

        return body;
    }

    private static String formatDropVersion(String dropVersion)
    {
        String cleanVersion = clean(dropVersion);
        if (cleanVersion.isBlank())
        {
            return "Drop table";
        }

        String lower = cleanVersion.toLowerCase(Locale.ENGLISH);
        if (lower.startsWith("drop table"))
        {
            return cleanVersion;
        }

        return "Drop table " + cleanVersion;
    }

    private static String clean(String value)
    {
        if (value == null)
        {
            return "";
        }

        String cleaned = value
                .replace("[[", "")
                .replace("]]", "")
                .replace("{{!}}", "|")
                .replaceAll("<.*?>", "")
                .replaceAll("\\s+", " ")
                .trim();

        while (cleaned.startsWith("{{"))
        {
            cleaned = cleaned.substring(2).trim();
        }

        while (cleaned.endsWith("}}"))
        {
            cleaned = cleaned.substring(0, cleaned.length() - 2).trim();
        }

        return cleaned;
    }

    private static int parseIntSafely(String value)
    {
        if (value == null || value.isBlank())
        {
            return 0;
        }

        try
        {
            return Integer.parseInt(value.replaceAll("[^0-9-]", ""));
        }
        catch (NumberFormatException ex)
        {
            return 0;
        }
    }

    private static int findNextDropLineStart(String text, int fromIndex)
    {
        int dropsLine = indexOfIgnoreCase(text, "{{DropsLine", fromIndex);
        int dropLine = indexOfIgnoreCase(text, "{{DropLine", fromIndex);

        if (dropsLine < 0)
        {
            return dropLine;
        }
        if (dropLine < 0)
        {
            return dropsLine;
        }
        return Math.min(dropsLine, dropLine);
    }

    private static int findNextDropsTableHeadStart(String text, int fromIndex)
    {
        Matcher matcher = DROPS_TABLE_HEAD_PATTERN.matcher(text);
        return matcher.find(Math.max(0, fromIndex)) ? matcher.start() : -1;
    }

    private static int indexOfIgnoreCase(String text, String needle, int fromIndex)
    {
        return text.toLowerCase(Locale.ENGLISH).indexOf(needle.toLowerCase(Locale.ENGLISH), fromIndex);
    }

    private static int findTemplateEnd(String text, int start)
    {
        int depth = 0;

        for (int i = start; i < text.length() - 1; i++)
        {
            char c1 = text.charAt(i);
            char c2 = text.charAt(i + 1);

            if (c1 == '{' && c2 == '{')
            {
                depth++;
                i++;
                continue;
            }

            if (c1 == '}' && c2 == '}')
            {
                depth--;
                i++;
                if (depth == 0)
                {
                    return i + 1;
                }
            }
        }

        return -1;
    }
}
