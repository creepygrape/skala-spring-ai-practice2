package com.spring_ai.practice2.chatbot.controller;

import com.spring_ai.practice2.chatbot.domain.ChatRequest;
import com.spring_ai.practice2.chatbot.service.ChatService;
import com.spring_ai.practice2.chatbot.service.QuotaService;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final QuotaService quotaService;

    public ChatController(ChatService chatService, QuotaService quotaService) {
        this.chatService = chatService;
        this.quotaService = quotaService;
    }

    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(
            @AuthenticationPrincipal UserDetails user,
            @RequestBody ChatRequest request) {
        quotaService.checkAndDecrease(user.getUsername());
        return chatService
                .streamChat(user.getUsername(), request.sessionId(),
                        request.message())
                .map(chunk -> ServerSentEvent.<String>builder()
                        .event("message").data(chunk).build())
                .onErrorResume(QuotaExceededException.class, e ->
                        Flux.just(ServerSentEvent.<String>builder()
                                .event("error").data("일일 사용량을 초과했다").build()))
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .event("done").data("[DONE]").build()));
    }

//    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
//    public Flux<ServerSentEvent<String>> streamChat(
//            @AuthenticationPrincipal UserDetails user,
//            @RequestBody ChatRequest request) {
//        quotaService.checkAndDecrease(user.getUsername());
//        return chatService
//                .streamChat(user.getUsername(), request.sessionId(), request.message() )
//                .map(chunk -> ServerSentEvent.<String>builder()
//                                .event("message")
//                                .data(chunk)
//                                .build())
//                .concatWith( Flux.just( ServerSentEvent.<String>builder()
//                                .event("done")
//                                .data("[DONE]")
//                                .build()));
//    })
//            .onErrorResume(
//            QuotaExceededException.class,
//            e -> Flux.just(
//    ServerSentEvent.<String>builder()
//                            .event("error")
//                            .data("일일 사용량을 초과했다")
//                            .build()
//            )
//                    );
}
