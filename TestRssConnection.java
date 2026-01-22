import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public class TestRssConnection {
    public static void main(String[] args) {
        testUrl("https://feeds.bbci.co.uk/news/rss.xml");
        testUrl("http://rss.cnn.com/rss/cnn_topstories.rss");
    }

    private static void testUrl(String urlStr) {
        System.out.println("Testing: " + urlStr);
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            int status = conn.getResponseCode();
            System.out.println("Status Code: " + status);
            
            if (status == 200) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                String inputLine;
                int lineCount = 0;
                while ((inputLine = in.readLine()) != null && lineCount < 5) {
                    System.out.println("Line " + lineCount + ": " + inputLine);
                    lineCount++;
                }
                in.close();
                System.out.println("Success!");
            } else {
                System.out.println("Failed with status: " + status);
            }
        } catch (Exception e) {
            System.out.println("Exception: " + e.getMessage());
            e.printStackTrace();
        }
        System.out.println("--------------------------------------------------");
    }
}
