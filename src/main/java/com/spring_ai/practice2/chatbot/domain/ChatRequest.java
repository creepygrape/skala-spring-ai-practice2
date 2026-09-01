package com.spring_ai.practice2.chatbot.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChatRequest(
        @NotBlank(message = "세션 아이디가 없습니다")
        String sessionId,
        @NotBlank(message = "대화 내용이 비어 있습니다")
        @Size(max = 12000, message = "대화 내용이 너무 깁니다. 12,000자 이내로 잘라서 보냅니다")
        String message
) {
}
