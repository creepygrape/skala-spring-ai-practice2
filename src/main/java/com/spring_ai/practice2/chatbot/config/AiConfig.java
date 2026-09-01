package com.spring_ai.practice2.chatbot.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {
    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .maxMessages(20) // 최근 20건만 유지한다
                .build();
    }
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory memory) {
        return builder
                .defaultSystem("""
                    친절하고 전문적인 AI 어시스턴트이다.
                    질문에 명확하고 도움이 되는 답변을 제공한다.
                    모르는 내용은 모른다고 답한다.
                    """)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build())
                .build();
    }
}
