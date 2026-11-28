package com.cvdcms.controller;

import com.cvdcms.service.QueueWorkflowService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Controller
public class PublicQueueTvController {
    private static final int MAX_TTS_TEXT_LENGTH = 250;
    private static final int MAX_CACHE_ENTRIES = 200;

    private final QueueWorkflowService queueService;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final Map<String, byte[]> ttsCache = new ConcurrentHashMap<>();

    public PublicQueueTvController(QueueWorkflowService queueService) {
        this.queueService = queueService;
    }

    @GetMapping("/tv-display")
    public String tvDisplay(@RequestParam(required = false, defaultValue = "1") Long branchId, Model model) {
        model.addAttribute("branchId", branchId);
        model.addAttribute("board", queueService.publicBoard(branchId));
        return "reception/queue-tv";
    }

    @GetMapping("/public/queue/tv")
    public String publicAlias(@RequestParam(defaultValue = "1") Long branchId) {
        return "redirect:/tv-display?branchId=" + branchId;
    }

    /**
     * Backend Vietnamese TTS audio proxy (MP3) for TV browsers lacking a local OS vi-VN voice pack.
     */
    @GetMapping(value = "/reception/queue/tv/tts", produces = "audio/mpeg")
    @ResponseBody
    public ResponseEntity<byte[]> vietnameseTtsAudio(@RequestParam("text") String text) {
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        String normalized = text.trim();
        if (normalized.length() > MAX_TTS_TEXT_LENGTH) {
            normalized = normalized.substring(0, MAX_TTS_TEXT_LENGTH);
        }
        byte[] cached = ttsCache.get(normalized);
        if (cached != null && cached.length > 0) {
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mpeg"))
                    .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
                    .body(cached);
        }
        try {
            String encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
            URI uri = URI.create("https://translate.google.com/translate_tts?ie=UTF-8&tl=vi&client=tw-ob&q=" + encoded);
            HttpRequest req = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(6))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200 && resp.body() != null && resp.body().length > 0) {
                if (ttsCache.size() >= MAX_CACHE_ENTRIES) {
                    ttsCache.clear();
                }
                ttsCache.put(normalized, resp.body());
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType("audio/mpeg"))
                        .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS))
                        .body(resp.body());
            }
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        } catch (Exception ex) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
        }
    }
}
