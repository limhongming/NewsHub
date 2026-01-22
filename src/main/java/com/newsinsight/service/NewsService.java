package com.newsinsight.service;

import com.newsinsight.model.NewsItem;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

@Service
public class NewsService {

    private static final String BBC_RSS_FEED = "https://feeds.bbci.co.uk/news/rss.xml";
    private static final String CNN_RSS_FEED = "http://rss.cnn.com/rss/cnn_topstories.rss";

    public List<NewsItem> getTopNews() {
        return fetchNewsFromRss(BBC_RSS_FEED, 10);
    }

    public List<NewsItem> getCNNNews() {
        return fetchNewsFromRss(CNN_RSS_FEED, 20);
    }

    private List<NewsItem> fetchNewsFromRss(String feedUrlStr, int limit) {
        List<NewsItem> newsItems = new ArrayList<>();
        try {
            URL url = new URL(feedUrlStr);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);

            // Use BufferedInputStream to ensure reliable stream reading
            try (java.io.InputStream is = new java.io.BufferedInputStream(connection.getInputStream())) {
                SyndFeedInput input = new SyndFeedInput();
                SyndFeed feed = input.build(new XmlReader(is));

                List<SyndEntry> entries = feed.getEntries();
                if (entries == null || entries.isEmpty()) {
                     System.err.println("Warning: RSS Feed parsed successfully but contains no entries: " + feedUrlStr);
                } else {
                     System.out.println("Successfully parsed " + entries.size() + " entries from " + feedUrlStr);
                }

                for (int i = 0; i < Math.min(entries.size(), limit); i++) {
                    SyndEntry entry = entries.get(i);
                    String description = entry.getDescription() != null ? entry.getDescription().getValue() : "";
                    newsItems.add(new NewsItem(
                            entry.getTitle(),
                            entry.getLink(),
                            entry.getPublishedDate(),
                            description
                    ));
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching RSS feed (" + feedUrlStr + "): " + e.getMessage());
            e.printStackTrace();
            newsItems.add(new NewsItem(
                "System Error: Failed to fetch news",
                "#",
                new java.util.Date(),
                "Error details: " + e.toString() + ". Try refreshing or checking server logs."
            ));
        }
        return newsItems;
    }

    public String scrapeArticleContent(String url) throws Exception {
        // Fetch the HTML
        Document doc = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                .timeout(5000)
                .get();

        // Cleanup
        doc.select("script, style, nav, footer, .advert").remove();

        // Basic extraction strategy: Paragraphs
        Elements paragraphs = doc.select("p");
        
        StringBuilder textContent = new StringBuilder();
        paragraphs.forEach(p -> textContent.append(p.text()).append(" "));

        String content = textContent.toString().trim();
        
        // Truncate to avoid huge payloads (approx 15k chars)
        if (content.length() > 15000) {
            content = content.substring(0, 15000);
        }
        
        return content;
    }
}
