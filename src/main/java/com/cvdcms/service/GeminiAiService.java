package com.cvdcms.service;

import com.cvdcms.DTO.AiParsePlanRequestDTO;
import com.cvdcms.DTO.AiParsePlanResponseDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
public class GeminiAiService {

    @Value("${gemini.api.key:}")
    private String apiKey;

    @Value("${gemini.api.model:gemini-3.5-flash}")
    private String modelName;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    public GeminiAiService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(15).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(45).toMillis());
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * Bóc tách quy trình kỹ thuật RHM hoặc ghi chú lâm sàng thành Kế hoạch điều trị nhiều giai đoạn
     */
    public AiParsePlanResponseDTO parseTreatmentPlan(AiParsePlanRequestDTO request) {
        String url = String.format("https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                modelName, apiKey);

        String systemPrompt = """
                Bạn là chuyên gia cố vấn giải pháp phần mềm nha khoa chuẩn DentalSoft / Bộ Y Tế.
                Nhiệm vụ của bạn: Hãy phân tích văn bản kỹ thuật răng hàm mặt hoặc ảnh chụp trang sách/ghi chú lâm sàng sau đây và bóc tách thành Kế hoạch điều trị nhiều giai đoạn (Multi-stage Treatment Plan) chuẩn y khoa.
                
                Yêu cầu cấu trúc JSON trả về:
                {
                  "planTitle": "Tên phác đồ điều trị rõ ràng, chuyên nghiệp",
                  "procedureCode": "Mã kỹ thuật gợi ý (VD: PT-IMP-01, ORTHO-02)",
                  "totalEstimatedDays": 180,
                  "stages": [
                    {
                      "stageOrder": 1,
                      "stageName": "Tên giai đoạn (VD: Giai đoạn 1: Chuẩn bị & Phẫu thuật cấy Implant)",
                      "estimatedDaysFromStart": 0,
                      "description": "Mục tiêu và tóm tắt giai đoạn",
                      "steps": [
                        {
                          "stepOrder": 1,
                          "stepName": "Tên bước cụ thể",
                          "actionType": "SURGERY / EXAMINATION / MEDICATION / PROSTHETIC",
                          "notes": "Lưu ý chuyên môn hoặc thao tác lâm sàng",
                          "suggestedServiceCode": "Mã dịch vụ nếu xác định được",
                          "suggestedTooth": "Vị trí răng FDI nếu có nhắc đến"
                        }
                      ]
                    }
                  ],
                  "postOpAdvice": "Lời dặn sau điều trị và chăm sóc vệ sinh",
                  "contraindications": "Chống chỉ định hoặc lưu ý an toàn y khoa"
                }
                
                Nếu văn bản ngắn, hãy tự động bổ sung các bước y khoa chuẩn mực (Ví dụ: Khám & Gây tê -> Thực hiện -> Theo dõi/Cắt chỉ/Lấy dấu).
                """;

        try {
            List<Map<String, Object>> parts = new ArrayList<>();

            // 1. Nếu có ảnh chụp tài liệu (Base64)
            if (request.getImageBase64() != null && !request.getImageBase64().trim().isEmpty()) {
                String cleanBase64 = request.getImageBase64();
                if (cleanBase64.contains(",")) {
                    cleanBase64 = cleanBase64.substring(cleanBase64.indexOf(",") + 1);
                }
                String mimeType = (request.getMimeType() != null && !request.getMimeType().isEmpty()) 
                        ? request.getMimeType() : "image/jpeg";

                Map<String, Object> inlineData = new HashMap<>();
                inlineData.put("mime_type", mimeType);
                inlineData.put("data", cleanBase64.trim());

                Map<String, Object> imagePart = new HashMap<>();
                imagePart.put("inline_data", inlineData);
                parts.add(imagePart);
            }

            // 2. Nội dung văn bản
            StringBuilder textContent = new StringBuilder(systemPrompt);
            if (request.getText() != null && !request.getText().trim().isEmpty()) {
                textContent.append("\n\nVăn bản kỹ thuật / Ghi chú lâm sàng cần bóc tách:\n").append(request.getText().trim());
            } else if (parts.isEmpty()) {
                throw new IllegalArgumentException("Vui lòng nhập văn bản hoặc tải ảnh tài liệu kỹ thuật để phân tích.");
            }

            Map<String, Object> textPart = new HashMap<>();
            textPart.put("text", textContent.toString());
            parts.add(textPart);

            // Xây dựng Payload
            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("parts", parts);

            Map<String, Object> genConfig = new HashMap<>();
            genConfig.put("response_mime_type", "application/json");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", Collections.singletonList(contentMap));
            requestBody.put("generationConfig", genConfig);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("Sending request to Gemini model: {}", modelName);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                JsonNode candidate = rootNode.path("candidates").path(0);
                JsonNode textNode = candidate.path("content").path("parts").path(0).path("text");
                String jsonOutput = textNode.asText();

                log.info("Gemini raw response: {}", jsonOutput);
                return objectMapper.readValue(jsonOutput, AiParsePlanResponseDTO.class);
            } else {
                throw new IllegalStateException("Gemini API trả về mã lỗi: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("Lỗi khi gọi Gemini AI: ", e);
            throw new RuntimeException("Không thể bóc tách kế hoạch điều trị qua AI: " + e.getMessage(), e);
        }
    }

    /**
     * Bóc tách quy trình kỹ thuật Răng Hàm Mặt từ tài liệu QĐ 3207/QĐ-BYT hoặc ảnh chụp thành các bước 1, 2, 3...
     */
    public com.cvdcms.DTO.WorkflowTemplateResponseDTO parseWorkflowProcedure(String text, String imageBase64, String mimeType) {
        String url = String.format("https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                modelName, apiKey);

        String systemPrompt = """
                Bạn là chuyên gia thẩm định Quy trình Kỹ thuật Khám bệnh, Chữa bệnh chuyên ngành Răng Hàm Mặt theo Quyết định số 3207/QĐ-BYT của Bộ Y Tế.
                Nhiệm vụ: Hãy phân tích văn bản quy trình kỹ thuật hoặc ảnh chụp trang sách từ tài liệu Bộ Y Tế và bóc tách thành Quy trình kỹ thuật lâm sàng chuẩn hoá gồm các bước thực hiện tuần tự: Bước 1, Bước 2, Bước 3...
                
                Yêu cầu cấu trúc JSON trả về:
                {
                  "templateName": "Tên quy trình kỹ thuật rõ ràng, chuẩn mực (VD: Quy trình kỹ thuật Lấy cao răng và đánh bóng)",
                  "templateCode": "Mã viết tắt gợi ý duy nhất (VD: QTKT-CAOVOI, QTKT-TRAM-RANG, QTKT-TUY, QTKT-NHORANG, QTKT-IMPLANT)",
                  "description": "Tóm tắt đại cương, chỉ định, chống chỉ định và nguyên tắc kỹ thuật",
                  "steps": [
                    {
                      "sequenceOrder": 1,
                      "stepCode": "Mã bước (VD: B1, B2, B3...)",
                      "stepName": "Bước 1: Tên ngắn gọn của bước (VD: Bước 1: Thăm khám và giải thích)",
                      "stepDescription": "Mô tả chi tiết kỹ thuật thao tác lâm sàng (dụng cụ, tư thế, cách thực hiện)",
                      "completionCriteria": "Tiêu chí nghiệm thu hoàn thành bước lâm sàng",
                      "estimatedDurationMinutes": 10,
                      "stepType": "CLINICAL"
                    }
                  ]
                }
                
                Quy tắc bắt buộc:
                1. Giá trị 'stepType' chỉ được chọn trong các từ: CLINICAL, TREATMENT, FOLLOW_UP, PARACLINICAL, DECISION.
                2. Phải bóc tách thành các bước tuần tự từ Bước 1 đến bước cuối cùng (sequenceOrder: 1, 2, 3...).
                3. Nội dung kỹ thuật và tiêu chí hoàn thành phải sát thực tế y khoa chuyên ngành Răng Hàm Mặt.
                """;

        try {
            List<Map<String, Object>> parts = new ArrayList<>();

            // 1. Ảnh nếu có
            if (imageBase64 != null && !imageBase64.trim().isEmpty()) {
                String cleanBase64 = imageBase64;
                if (cleanBase64.contains(",")) {
                    cleanBase64 = cleanBase64.substring(cleanBase64.indexOf(",") + 1);
                }
                String safeMime = (mimeType != null && !mimeType.isEmpty()) ? mimeType : "image/jpeg";

                Map<String, Object> inlineData = new HashMap<>();
                inlineData.put("mime_type", safeMime);
                inlineData.put("data", cleanBase64.trim());

                Map<String, Object> imagePart = new HashMap<>();
                imagePart.put("inline_data", inlineData);
                parts.add(imagePart);
            }

            // 2. Nội dung văn bản
            StringBuilder textContent = new StringBuilder(systemPrompt);
            if (text != null && !text.trim().isEmpty()) {
                textContent.append("\n\nVăn bản quy trình kỹ thuật Bộ Y Tế cần bóc tách:\n").append(text.trim());
            } else if (parts.isEmpty()) {
                throw new IllegalArgumentException("Vui lòng nhập văn bản quy trình hoặc tải ảnh tài liệu kỹ thuật để phân tích.");
            }

            Map<String, Object> textPart = new HashMap<>();
            textPart.put("text", textContent.toString());
            parts.add(textPart);

            Map<String, Object> contentMap = new HashMap<>();
            contentMap.put("parts", parts);

            Map<String, Object> genConfig = new HashMap<>();
            genConfig.put("response_mime_type", "application/json");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("contents", Collections.singletonList(contentMap));
            requestBody.put("generationConfig", genConfig);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            log.info("Sending workflow parsing request to Gemini model: {}", modelName);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                JsonNode candidate = rootNode.path("candidates").path(0);
                JsonNode textNode = candidate.path("content").path("parts").path(0).path("text");
                String jsonOutput = textNode.asText();

                log.info("Gemini workflow extraction response: {}", jsonOutput);
                return objectMapper.readValue(jsonOutput, com.cvdcms.DTO.WorkflowTemplateResponseDTO.class);
            } else {
                throw new IllegalStateException("Gemini API trả về mã lỗi: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("Lỗi khi bóc tách quy trình kỹ thuật qua Gemini AI: ", e);
            throw new RuntimeException("Không thể bóc tách quy trình kỹ thuật qua AI: " + e.getMessage(), e);
        }
    }
}
