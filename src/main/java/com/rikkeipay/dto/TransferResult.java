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
