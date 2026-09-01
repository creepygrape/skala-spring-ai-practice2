package com.spring_ai.practice2.chatbot.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Slf4j
@Service
public class ChatService {
    private final ChatClient chatClient;
    private final MeterRegistry meterRegistry;

    public ChatService(ChatClient chatClient, MeterRegistry meterRegistry) {
        this.chatClient = chatClient;
        this.meterRegistry = meterRegistry;
    }

    public Flux<String> streamChat(String userId, String sessionId, String message) {
        Timer.Sample sample = Timer.start(meterRegistry);
        return chatClient
                .prompt()
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content()
                .doOnComplete(() -> {
                    sample.stop(Timer.builder("chatbot.response.duration")
                            .tag("user_id", userId).register(meterRegistry));
                    log.info("응답 완료: userId={}, sessionId={}", userId, sessionId);
                })
                .doOnError(e -> log.error("응답 오류: {}", e.getMessage()));
    }
}
