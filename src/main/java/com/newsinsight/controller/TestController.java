package com.newsinsight.controller;

import com.newsinsight.service.NewsSystemService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/test")
public class TestController {

    private final NewsSystemService newsSystemService;

    public TestController(NewsSystemService newsSystemService) {
        this.newsSystemService = newsSystemService;
    }

    @PostMapping("/direct")
    public String testDirectGemini(@RequestBody Map<String, String> payload) {
        String prompt = payload.getOrDefault("prompt", "Hello, can you confirm you are online?");
        String model = payload.getOrDefault("model", "gemini-2.5-flash-lite");
        
        System.out.println("TEST-API: Testing model '" + model + "' with prompt: " + prompt);
        
        return newsSystemService.testRawGemini(prompt, model);
    }
}
