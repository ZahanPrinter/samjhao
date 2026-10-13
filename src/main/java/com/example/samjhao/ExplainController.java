package com.example.samjhao;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

@RestController
public class ExplainController {

    // Ollama runs on your own laptop. Nothing leaves your machine.
    private static final String OLLAMA_URL = "http://localhost:11434/api/chat";

    // Change to "gemma4:e2b" if your laptop is slow, or "gemma4:26b" if you have a strong GPU.
    private static final String MODEL = "gemma4:e4b";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // The shape we force the model to answer in.
    private static final String SCHEMA = """
        {
          "type": "object",
          "properties": {
            "document_type":     { "type": "string" },
            "summary":           { "type": "string" },
            "what_you_must_do":  { "type": "array", "items": { "type": "string" } },
            "deadlines":         { "type": "array", "items": { "type": "string" } },
            "warnings":          { "type": "array", "items": { "type": "string" } }
          },
          "required": ["document_type", "summary", "what_you_must_do", "deadlines", "warnings"]
        }
        """;

    @PostMapping(value = "/explain", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> explain(@RequestParam("file") MultipartFile file,
                                          @RequestParam(value = "language", defaultValue = "English") String language) {
        try {
            // 1. Shrink the photo so the model answers faster.
            byte[] image = shrink(file.getBytes());
            String base64 = Base64.getEncoder().encodeToString(image);

            // 2. Build the request for Ollama.
            ObjectNode body = mapper.createObjectNode();
            body.put("model", MODEL);
            body.put("stream", false);
            body.put("think", false); // thinking mode off = much faster
            body.set("format", mapper.readTree(SCHEMA));
            body.putObject("options").put("temperature", 0.2);

            ArrayNode messages = body.putArray("messages");

            messages.addObject()
                    .put("role", "system")
                    .put("content", "You help people understand official documents such as letters, bills, "
                            + "medicine labels, visa notices and rental notices."
                            + "Only use facts visible in the document. Never invent dates, "
                            + "amounts or names. If something is unreadable, say so.");

            ObjectNode user = messages.addObject();
            user.put("role", "user");
            user.put("content", "Read this document photo. Write ALL values in " + language + ", using that language's own script (not English letters). "
                    + "document_type: what kind of document this is, in a few words. "
                    + "summary: 3 to 4 long paragraphs detailing the following: What this is / Key details (amounts, dates, names) / What you need to do and by when / Be careful about. "
                    + "what_you_must_do: the actions the reader needs to take, Short but descriptive "
                    + "deadlines: every date or time limit, with what it is for. "
                    + "warnings: penalties, costs, risks or anything the reader should be careful about. "
                    + "Use an empty list if there are none.");
            user.putArray("images").add(base64);

            // 3. Call Ollama.
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(OLLAMA_URL))
                    .timeout(Duration.ofMinutes(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return error("Ollama returned " + response.statusCode() + ": " + response.body());
            }

            // 4. Pull the model's JSON answer out of Ollama's reply.
            JsonNode root = mapper.readTree(response.body());
            String content = root.path("message").path("content").asText();
            JsonNode parsed = mapper.readTree(content); // makes sure it is valid JSON

            return ResponseEntity.ok(mapper.writeValueAsString(parsed));

        } catch (java.net.ConnectException e) {
            return error("Cannot reach Ollama. Is it running? Try: ollama serve");
        } catch (Exception e) {
            return error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ResponseEntity<String> error(String message) {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("error", message);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(mapper.writeValueAsString(node));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\":\"unknown\"}");
        }
    }

    // Scale the longest side down to 1600 px and save as JPEG.
    private byte[] shrink(byte[] raw) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
            if (src == null) return raw; // format Java can't read: send as-is
            double scale = Math.min(1.0, 1600.0 / Math.max(src.getWidth(), src.getHeight()));
            int w = Math.max(1, (int) (src.getWidth() * scale));
            int h = Math.max(1, (int) (src.getHeight() * scale));
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            return raw;
        }
    }
}
