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

    public List<NewsItem> getTopNews() {
        List<NewsItem> newsItems = new ArrayList<>();
        try {
            URL feedUrl = new URL(BBC_RSS_FEED);
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(new XmlReader(feedUrl));

            List<SyndEntry> entries = feed.getEntries();
            // Get top 10
            for (int i = 0; i < Math.min(entries.size(), 10); i++) {
                SyndEntry entry = entries.get(i);
                newsItems.add(new NewsItem(
                        entry.getTitle(),
                        entry.getLink(),
                        entry.getPublishedDate(),
                        entry.getDescription().getValue()
                ));
            }
        } catch (Exception e) {
            e.printStackTrace();
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
