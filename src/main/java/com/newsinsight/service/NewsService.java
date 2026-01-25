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
    private static final String CNN_RSS_FEED = "http://rss.cnn.com/rss/edition.rss"; // Full edition feed

    public List<NewsItem> getTopNews() {
        return fetchNewsFromRss(BBC_RSS_FEED, 15);
    }

    public List<NewsItem> getCNNNews() {
        return fetchNewsFromRss(CNN_RSS_FEED, 40);
    }

    private List<NewsItem> fetchNewsFromRss(String feedUrlStr, int limit) {
        List<NewsItem> newsItems = new ArrayList<>();
        try {
            URL url = new URL(feedUrlStr);
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection();
            // Use a very standard, widely accepted User-Agent
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(true);

            // Use BufferedInputStream to ensure reliable stream reading
            try (java.io.InputStream is = new java.io.BufferedInputStream(connection.getInputStream())) {
                SyndFeedInput input = new SyndFeedInput();
                SyndFeed feed = input.build(new XmlReader(is));

                List<SyndEntry> entries = feed.getEntries();
                
                if (entries == null || entries.isEmpty()) {
                    System.err.println("Warning: RSS Feed parsed successfully but contains no entries: " + feedUrlStr);
                    newsItems.add(new NewsItem(
                        "No News Found",
                        "#",
                        new java.util.Date(),
                        "The RSS feed (" + feedUrlStr + ") was accessed but returned no articles. Parsing issue or empty feed."
                    ));
                } else {
                    System.out.println("Successfully parsed " + entries.size() + " entries from " + feedUrlStr);
                }

                if (entries != null) {
                    for (int i = 0; i < Math.min(entries.size(), limit); i++) {
                        SyndEntry entry = entries.get(i);
                        
                        // Filter for 2026 only
                        java.util.Date pubDate = entry.getPublishedDate();
                        if (pubDate != null) {
                            java.util.Calendar cal = java.util.Calendar.getInstance();
                            cal.setTime(pubDate);
                            if (cal.get(java.util.Calendar.YEAR) < 2026) {
                                continue; // Skip older articles
                            }
                        }
                        
                        String description = entry.getDescription() != null ? entry.getDescription().getValue() : "";
                        newsItems.add(new NewsItem(
                                entry.getTitle(),
                                entry.getLink(),
                                entry.getPublishedDate(),
                                description
                        ));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error fetching RSS feed (" + feedUrlStr + "): " + e.getMessage());
            e.printStackTrace();
            newsItems.add(new NewsItem(
                "System Error: Failed to fetch news",
                "#",
                new java.util.Date(),
                "Error details: " + e.toString() + " (" + feedUrlStr + ")"
            ));
        }
        return newsItems;
    }

    public NewsItem scrapeNewsItem(String url) {
        try {
            System.out.println("DEBUG: Scrape & Build NewsItem for: " + url);
            // Reuse connection logic similar to scrapeArticleContent but we need the Document
            // to get the Title as well.
            
             // Multiple User-Agent strings to rotate
            String[] userAgents = {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            };
            String userAgent = userAgents[(int) (System.currentTimeMillis() % userAgents.length)];

            Document doc = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(15000)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .get();

            String title = doc.title();
            // Try to find a better title if possible
            Elements h1 = doc.select("h1");
            if (!h1.isEmpty()) {
                title = h1.first().text();
            }

            // Clean up for content extraction
            doc.select("script, style, nav, footer, .advert, .sidebar, .related, .comments").remove();
            
            // Extract content (reuse extraction logic basics)
            String content = extractWithSelectors(doc, new String[]{
                "article p", "article div.article-body p", ".article-content p", 
                ".story-body p", ".post-content p", ".entry-content p", ".text p",
                "main p", "#content p", ".body p"
            });

            if (content == null || content.isEmpty()) {
                content = doc.body().text();
            }
            
            // Truncate if too long to fit in "summary" for NewsItem, but keep enough for analysis
            // The processAndClusterNews uses this field for analysis.
            if (content != null && content.length() > 5000) {
                content = content.substring(0, 5000) + "...";
            }
            
            if (content == null) content = "Content could not be scraped.";

            return new NewsItem(title, url, new java.util.Date(), content);

        } catch (Exception e) {
            System.err.println("Error scraping NewsItem (" + url + "): " + e.getMessage());
            return new NewsItem("Error Fetching: " + url, url, new java.util.Date(), "Failed to scrape: " + e.getMessage());
        }
    }

    public String scrapeArticleContent(String url) throws Exception {
        System.out.println("DEBUG: Attempting to scrape article content from: " + url);
        
        // Multiple User-Agent strings to rotate
        String[] userAgents = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        };
        
        String userAgent = userAgents[(int) (System.currentTimeMillis() % userAgents.length)];
        
        Document doc;
        try {
            // Fetch the HTML with increased timeout and better headers
            doc = Jsoup.connect(url)
                    .userAgent(userAgent)
                    .timeout(15000) // Increased from 5s to 15s
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.5")
                    .header("Accept-Encoding", "gzip, deflate, br")
                    .header("Connection", "keep-alive")
                    .header("Upgrade-Insecure-Requests", "1")
                    .header("Cache-Control", "max-age=0")
                    .get();
        } catch (Exception e) {
            System.err.println("ERROR: Failed to fetch URL " + url + ": " + e.getMessage());
            throw new Exception("Failed to fetch article content. The website may be blocking access or is temporarily unavailable.");
        }

        // Cleanup
        doc.select("script, style, nav, footer, .advert, .sidebar, .related, .comments, .social-share, .newsletter, .cookie-banner").remove();

        // Strategy 1: Try article-specific selectors first (most specific)
        String content = extractWithSelectors(doc, new String[]{
            "article p",
            "article div.article-body p",
            ".article-content p",
            ".story-body p",
            ".post-content p",
            ".entry-content p",
            ".text p"
        });
        
        // Strategy 2: If strategy 1 fails, try main content area selectors
        if (content == null || content.trim().isEmpty()) {
            System.out.println("DEBUG: Strategy 1 failed, trying strategy 2 for: " + url);
            content = extractWithSelectors(doc, new String[]{
                "main p",
                ".main-content p",
                "#content p",
                ".content p",
                ".body p",
                ".story p",
                ".post p"
            });
        }
        
        // Strategy 3: If strategy 2 fails, try body-wide paragraph extraction (original method)
        if (content == null || content.trim().isEmpty()) {
            System.out.println("DEBUG: Strategy 2 failed, trying strategy 3 for: " + url);
            Elements paragraphs = doc.select("p");
            StringBuilder textContent = new StringBuilder();
            paragraphs.forEach(p -> textContent.append(p.text()).append(" "));
            content = textContent.toString().trim();
        }
        
        // Strategy 4: If all else fails, extract text from the entire body
        if (content == null || content.trim().isEmpty()) {
            System.out.println("DEBUG: Strategy 3 failed, trying strategy 4 for: " + url);
            content = doc.body().text();
        }
        
        // Clean up the content
        if (content != null) {
            content = content.trim()
                .replaceAll("\\s+", " ")  // Collapse multiple spaces
                .replaceAll("\\n+", "\n") // Collapse multiple newlines
                .replaceAll("\\t+", " "); // Replace tabs with spaces
        }
        
        // Check if we got meaningful content
        if (content == null || content.trim().isEmpty() || content.length() < 50) {
            throw new Exception("Article content not found or too short. The website structure may have changed.");
        }
        
        // Truncate to avoid huge payloads (approx 20k chars)
        if (content.length() > 20000) {
            content = content.substring(0, 20000);
            System.out.println("DEBUG: Content truncated to 20000 characters for: " + url);
        }
        
        System.out.println("DEBUG: Successfully scraped " + content.length() + " characters from: " + url);
        return content;
    }
    
    private String extractWithSelectors(Document doc, String[] selectors) {
        for (String selector : selectors) {
            try {
                Elements elements = doc.select(selector);
                if (elements != null && !elements.isEmpty()) {
                    StringBuilder content = new StringBuilder();
                    elements.forEach(el -> {
                        String text = el.text().trim();
                        if (!text.isEmpty()) {
                            content.append(text).append(" ");
                        }
                    });
                    
                    String result = content.toString().trim();
                    if (result.length() > 100) { // Reasonable minimum length
                        System.out.println("DEBUG: Found content using selector: " + selector + " (" + result.length() + " chars)");
                        return result;
                    }
                }
            } catch (Exception e) {
                // Continue to next selector
                System.err.println("WARN: Selector " + selector + " failed: " + e.getMessage());
            }
        }
        return null;
    }
}
