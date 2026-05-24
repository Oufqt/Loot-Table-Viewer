package com.loottableviewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class OsrsWikiClient
{
    private static final String API_BASE = "https://oldschool.runescape.wiki/api.php";
    private static final String PAGE_BASE = "https://oldschool.runescape.wiki/w/";
    private static final String WIKI_HOST = "oldschool.runescape.wiki";
    private static final String DEFAULT_USER_AGENT = "RuneLite Loot-Table-Viewer";
    private static final int MAX_RESPONSE_BYTES = 2_000_000;
    private static final int MAX_USER_AGENT_LENGTH = 128;

    private final LootTableViewerConfig config;

    @Inject
    public OsrsWikiClient(LootTableViewerConfig config)
    {
        this.config = config;
    }

    public WikiPageData findPage(String sourceName) throws IOException
    {
        String title = searchForTitle(sourceName);
        if (title == null || title.isEmpty())
        {
            return null;
        }

        String wikitext = loadWikitext(title);
        return new WikiPageData(title, PAGE_BASE + encode(title.replace(' ', '_')).replace("+", "%20"), wikitext);
    }

    private String searchForTitle(String query) throws IOException
    {
        String url = API_BASE
                + "?action=query"
                + "&list=search"
                + "&srsearch=" + encode(query)
                + "&srlimit=10"
                + "&format=json"
                + "&utf8=1";

        JsonObject json = getJson(url);
        JsonObject queryObj = json.getAsJsonObject("query");
        if (queryObj == null)
        {
            return null;
        }

        JsonArray search = queryObj.getAsJsonArray("search");
        if (search == null || search.size() == 0)
        {
            return null;
        }

        String normalizedQuery = normalizeTitle(query);
        String fallback = null;

        for (JsonElement element : search)
        {
            JsonObject obj = element.getAsJsonObject();
            JsonElement titleEl = obj.get("title");
            if (titleEl == null)
            {
                continue;
            }

            String title = titleEl.getAsString();
            if (fallback == null)
            {
                fallback = title;
            }

            if (normalizeTitle(title).equals(normalizedQuery))
            {
                return title;
            }
        }

        return fallback;
    }

    private String loadWikitext(String title) throws IOException
    {
        String url = API_BASE
                + "?action=parse"
                + "&page=" + encode(title)
                + "&prop=wikitext"
                + "&format=json"
                + "&redirects=1"
                + "&utf8=1";

        JsonObject json = getJson(url);
        JsonObject parse = json.getAsJsonObject("parse");
        if (parse == null)
        {
            return "";
        }

        JsonObject wikitext = parse.getAsJsonObject("wikitext");
        if (wikitext == null)
        {
            return "";
        }

        JsonElement text = wikitext.get("*");
        return text == null ? "" : text.getAsString();
    }

    private JsonObject getJson(String url) throws IOException
    {
        URL requestUrl = new URL(url);
        if (!"https".equalsIgnoreCase(requestUrl.getProtocol()) || !WIKI_HOST.equalsIgnoreCase(requestUrl.getHost()))
        {
            throw new IOException("Blocked non-OSRS Wiki request.");
        }

        HttpURLConnection connection = (HttpURLConnection) requestUrl.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", safeUserAgent());

        int status = connection.getResponseCode();
        if (status < 200 || status >= 300)
        {
            throw new IOException("OSRS Wiki returned HTTP " + status);
        }

        try (InputStream inputStream = connection.getInputStream())
        {
            String body = readAll(inputStream);
            return new JsonParser().parse(body).getAsJsonObject();
        }
    }

    private static String readAll(InputStream inputStream) throws IOException
    {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        int totalRead = 0;
        while ((read = inputStream.read(buffer)) != -1)
        {
            totalRead += read;
            if (totalRead > MAX_RESPONSE_BYTES)
            {
                throw new IOException("OSRS Wiki response was too large.");
            }

            outputStream.write(buffer, 0, read);
        }
        return new String(outputStream.toByteArray(), StandardCharsets.UTF_8);
    }

    private String safeUserAgent()
    {
        String userAgent = config == null ? "" : config.wikiUserAgent();
        if (userAgent == null || userAgent.isBlank())
        {
            return DEFAULT_USER_AGENT;
        }

        String sanitized = userAgent
            .replaceAll("[\\r\\n\\p{Cntrl}]", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (sanitized.isBlank())
        {
            return DEFAULT_USER_AGENT;
        }

        return sanitized.length() > MAX_USER_AGENT_LENGTH
            ? sanitized.substring(0, MAX_USER_AGENT_LENGTH)
            : sanitized;
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String normalizeTitle(String value)
    {
        return value == null ? "" : value.trim().replace('_', ' ').toLowerCase();
    }

    public static final class WikiPageData
    {
        private final String title;
        private final String url;
        private final String wikitext;

        public WikiPageData(String title, String url, String wikitext)
        {
            this.title = title;
            this.url = url;
            this.wikitext = wikitext;
        }

        public String getTitle()
        {
            return title;
        }

        public String getUrl()
        {
            return url;
        }

        public String getWikitext()
        {
            return wikitext;
        }
    }
}
