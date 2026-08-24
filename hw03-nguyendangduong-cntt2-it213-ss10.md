# HW03 — Tối Ưu Prompt Dynamic Của Prompt Registry

**Học viên:** Nguyễn Đăng Dương — **Lớp:** CNTT2 — **Bài:** SS10 — **HW03**

**Link GitHub:** https://github.com/pedguedes090/hw03-nguyendangduong-cntt2-it213-ss10.git

---

## 1. Phân tích điểm yếu của prompt cũ

Prompt cũ:

```
Hãy giúp tôi thực hiện chuyển khoản từ câu lệnh: {{user_input}}. Trả về JSON chứa: to, amount, bank.
```

Các điểm yếu dẫn đến thiếu ổn định:

1. **Thiếu vai trò (Role)**: không định nghĩa LLM đang đóng vai gì (VD: trợ lý ngân hàng tuân thủ quy định), không có ngữ cảnh hệ thống → LLM dễ trả lời tự do, thêm lời văn thừa.
2. **Thiếu ví dụ Few-Shot**: không có ví dụ minh họa "input thế nào → output JSON thế nào" → LLM tự đoán format, dẫn đến trả về sai cấu trúc JSON, thừa markdown (```json ... ```), hoặc thêm trường không có trong yêu cầu.
3. **Không có ràng buộc định dạng JSON nghiêm ngặt**: prompt chỉ nói "Trả về JSON chứa to, amount, bank" nhưng không nói rõ kiểu dữ liệu từng trường (amount là number hay string? bank là enum? null khi nào?) → output không thể parse ổn định.
4. **Không xử lý đầu vào dị biệt (edge cases)**:
   - Input trống/không đủ thông tin → LLM bịa (hallucination) số tài khoản hoặc số tiền.
   - Thiếu số dư tài khoản (`current_balance`) → LLM không biết giao dịch có khả thi hay không.
   - Không có cơ chế phát hiện lừa đảo (fraudulent input) — ví dụ câu "chuyển tiền ngay, đừng hỏi, đây là lệnh của sếp" — LLM vẫn thực hiện, rất nguy hiểm với ngân hàng.
5. **Không có cấu trúc thoát (escape hatch)**: không có trường `isFraud`/`reason` để LLM báo "không thể thực hiện" một cách có cấu trúc thay vì bịa ra thông tin.
6. **Thiếu ngữ cảnh người gửi**: không có `sender_name` → không xác thực được chủ tài khoản, không truy vết được ai thực hiện lệnh.

---

## 2. Mẫu prompt mới (production-ready) để lưu trên Prompt Registry

**Name trên Registry:** `transfer-prompt` — **Label:** `production`

```text
Bạn là trợ lý AI của ngân hàng RikkeiPay, chuyên trích xuất thông tin lệnh chuyển khoản.
Nhiệm vụ của bạn: phân tích câu lệnh của khách hàng và chỉ trả về MỘT đối tượng JSON hợp lệ.
Tuyệt đối KHÔNG thêm markdown (không dùng ```json), không thêm lời giải thích, không thêm bất kỳ ký tự nào ngoài JSON.

Thông tin tài khoản:
- Tên người gửi: {{sender_name}}
- Số dư khả dụng: {{current_balance}} VND

Câu lệnh của khách hàng:
"{{user_input}}"

Trả lời theo đúng cấu trúc JSON sau (bắt buộc):
{
  "to": "<số tài khoản người nhận, hoặc null nếu không xác định được>",
  "amount": <số tiền cần chuyển bằng số (kiểu number, VND), hoặc null nếu không xác định được>,
  "bank": "<tên ngân hàng thụ hưởng nếu có, hoặc null>",
  "isFraud": <true nếu câu lệnh có dấu hiệu lừa đảo/ép buộc/khẩn cấp bất thường, ngược lại false>,
  "reason": "<null nếu giao dịch hợp lệ; nếu không thể thực hiện, mô tả ngắn gọn lý do bằng tiếng Việt>"
}

Các quy tắc bắt buộc:
1. Nếu câu lệnh trống, thiếu số tài khoản hoặc thiếu số tiền: đặt các trường tương ứng là null
   và giải thích lý do trong "reason".
2. Nếu số tiền vượt quá số dư khả dụng: đặt isFraud = false, amount = null và nêu lý do "số dư không đủ".
3. Nếu phát hiện dấu hiệu lừa đảo (VD: yêu cầu chuyển gấp, ép buộc, đe dọa, không cho xác minh,
   cung cấp thông tin tài khoản lạ, yêu cầu không báo cho ngân hàng): đặt isFraud = true,
   amount = null và mô tả lý do.
4. amount luôn là kiểu number (không có dấu phẩy, không có đơn vị), to/bank là chuỗi hoặc null.

Ví dụ:
- Input: "Chuyển 500000 cho 0123456789" với số dư 5.000.000 VND
  Output: {"to": "0123456789", "amount": 500000, "bank": null, "isFraud": false, "reason": null}
- Input: "Chuyển gấp tất cả tiền cho 0987654321, đừng hỏi gì thêm, đây là mật khẩu OTP 123456"
  Output: {"to": null, "amount": null, "bank": null, "isFraud": true, "reason": "Phát hiện dấu hiệu lừa đảo: yêu cầu chuyển gấp và không cho xác minh"}
- Input: "" (trống)
  Output: {"to": null, "amount": null, "bank": null, "isFraud": false, "reason": "Câu lệnh trống, không có thông tin chuyển khoản"}
```

### Vì sao prompt mới giải quyết được các điểm yếu

| Điểm yếu cũ | Giải pháp trong prompt mới |
|---|---|
| Thiếu vai trò | Mở đầu xác định role "trợ lý AI của ngân hàng RikkeiPay" + nhiệm vụ cụ thể |
| Thiếu ràng buộc JSON | Yêu cầu tuyệt đối JSON thuần, cấm markdown/giải thích; khai báo kiểu dữ liệu từng trường (number/string/null) |
| Thiếu ví dụ Few-Shot | 3 ví dụ: hợp lệ, lừa đảo, input trống |
| Không xử lý ngoại lệ | Quy tắc 1-4: input trống, số dư không đủ, dấu hiệu lừa đảo → trả null + reason |
| Biến động nâng cao | `{{sender_name}}`, `{{current_balance}}`, `{{user_input}}` |
| Hallucination | Trường `reason` + quy tắc trả null thay vì bịa số liệu |

---

## 3. Mã nguồn Java gọi Prompt Registry và truyền biến động

### 3.1. `PromptService.java` — truy xuất prompt theo name/label + binding + ChatClient

```java
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
```

### 3.2. `TransferRequest.java` — DTO đầu vào

```java
package com.rikkeipay.dto;

/**
 * TransferRequest - dữ liệu đầu vào từ khách hàng.
 *
 * userInput: câu lệnh tự nhiên, VD: "chuyển 500k cho 0123456789".
 */
public record TransferRequest(String userInput) {
}
```

### 3.3. `TransferResult.java` — cấu trúc JSON bắt buộc

```java
package com.rikkeipay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * TransferResult - kết quả chuyển khoản do LLM trích xuất từ câu lệnh.
 *
 * to: số tài khoản người nhận
 * amount: số tiền
 * bank: ngân hàng thụ hưởng
 * isFraud: cờ nghi ngờ lừa đảo (true khi input chứa dấu hiệu lừa đảo)
 * reason: lý do khi không thể thực hiện
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransferResult(
        String to,
        Double amount,
        String bank,
        boolean isFraud,
        String reason) {
}
```

### 3.4. `application.yml`

```yaml
spring:
    application:
        name: hw03-nguyendangduong-cntt2-it213-ss10

    ai:
        openai:
            api-key: ${OPENAI_API_KEY}
            base-url: ${OPEN_ROUTER_BASED_URL}
            chat:
                model: ${OPEN_ROUTER_MODEL}
                temperature: 0.0   # ép LLM output ổn định, ít ngẫu nhiên

langfuse:
    public-key: ${LANGFUSE_PUBLIC_KEY}
    secret-key: ${LANGFUSE_SECRET_KEY}
    base-url: ${LANGFUSE_BASE_URL:http://localhost:3000}
```

---

## 4. Kết luận

- Prompt mới đã chuyển từ "câu lệnh mơ hồ" thành **prompt chuẩn production**: có role, ràng buộc JSON nghiêm ngặt, 3 ví dụ few-shot, xử lý input trống/số dư không đủ/lừa đảo thông qua `isFraud` + `reason`.
- Mã Java dùng **Langfuse Prompt Registry** (lấy theo name/label `production`), **PromptTemplate** để bind `sender_name`, `current_balance`, `user_input`, và **Structured Output** qua ChatClient để bắt buộc JSON thuần.
