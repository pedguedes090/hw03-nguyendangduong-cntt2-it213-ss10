package com.rikkeipay.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rikkeipay.dto.TransferRequest;
import com.rikkeipay.dto.TransferResult;
import io.langfuse.client.LangfuseClient;
import io.langfuse.client.model.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * PromptService - minh họa truy xuất Prompt Registry và binding tham số động.
 *
 * Luồng xử lý:
 *   1. langfuseClient.prompt("transfer-prompt", "production")
 *        -> lấy nội dung prompt (version production) từ Langfuse Prompt Registry.
 *   2. Binding các biến động: sender_name, current_balance, user_input
 *        -> dùng PromptTemplate của Spring AI.
 *   3. Gọi ChatClient với kỹ thuật Structured Output (ephemeral System Prompt)
 *        -> ép LLM chỉ trả về JSON hợp lệ tương ứng record TransferResult.
 */
@Service
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);

    private final LangfuseClient langfuseClient;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PromptService(LangfuseClient langfuseClient, ChatClient chatClient) {
        this.langfuseClient = langfuseClient;
        this.chatClient = chatClient;
    }

    /**
     * Lấy prompt từ Prompt Registry theo (name, label) rồi bind biến động,
     * sau đó gọi ChatClient để trích xuất thông tin chuyển khoản dạng JSON.
     */
    public TransferResult extractTransferInfo(TransferRequest request) {
        // ---- 1. Truy xuất prompt từ Registry theo name + label ----
        // Trong Langfuse Java SDK: prompt(name, version) lấy theo version,
        // còn label (vd "production") có thể lấy qua getPrompt(name, label)
        // hoặc phiên bản tương ứng. Dưới đây minh họa cú pháp chính thức của SDK.
        Prompt prompt = langfuseClient.prompt("transfer-prompt", "production");
        String template = prompt.getPrompt();
        log.info("Đã lấy prompt từ registry: name=transfer-prompt label=production, version={}",
                prompt.getVersion() != null ? prompt.getVersion() : "latest");

        // ---- 2. Binding các biến động vào prompt ----
        // Dữ liệu này thường đến từ hệ thống core banking / session đăng nhập
        String senderName = "Nguyen Van A";
        double currentBalance = 5_000_000.0; // số dư khả dụng, đơn vị VND
        String userInput = request.userInput() != null ? request.userInput() : "";

        PromptTemplate promptTemplate = new PromptTemplate(template);
        String renderedPrompt = promptTemplate.render(Map.of(
                "sender_name", senderName,
                "current_balance", currentBalance,
                "user_input", userInput));

        log.info("Prompt sau khi binding: {}", renderedPrompt);

        // ---- 3. Gọi ChatClient với Structured Output ----
        // ephemeral System Prompt ép LLM trả JSON thuần túy khớp record TransferResult
        String structuredOutputInstruction = """
                You are an assistant that ALWAYS answers with a single JSON object only.
                The JSON object MUST match exactly this structure, with no markdown, no extra text:
                {"to": "<string|null>", "amount": <number|null>, "bank": "<string|null>", "isFraud": <boolean>, "reason": "<string|null>"}
                """;

        String response = chatClient.prompt()
                .system(structuredOutputInstruction)
                .user(renderedPrompt)
                .call()
                .content();

        log.info("Phản hồi từ LLM: {}", response);

        // ---- 4. Parse JSON về TransferResult (kèm fallback an toàn) ----
        try {
            return objectMapper.readValue(response, TransferResult.class);
        } catch (Exception e) {
            log.error("LLM trả về JSON không hợp lệ: {}", response, e);
            return new TransferResult(null, null, null, false,
                    "Hệ thống không thể hiểu được yêu cầu, vui lòng thử lại.");
        }
    }
}
