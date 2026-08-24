package com.rikkeipay.dto;

/**
 * TransferRequest - dữ liệu đầu vào từ khách hàng.
 *
 * userInput: câu lệnh tự nhiên, VD: "chuyển 500k cho 0123456789".
 */
public record TransferRequest(String userInput) {
}
