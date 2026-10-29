package com.agentscope.brain.experiment;

import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import java.util.concurrent.atomic.AtomicBoolean;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Nonblocking model transport: cancelling the subscription closes the HTTP stream. */
final class StreamingTransport implements HttpTransport {
    private final WebClient client = WebClient.create();
    private final AtomicBoolean requested = new AtomicBoolean();
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE =
            new ParameterizedTypeReference<>() {};
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public Flux<String> stream(HttpRequest request) {
        return Flux.defer(() -> {
            if (!requested.compareAndSet(false, true)) return Flux.error(new IllegalStateException("Only one model request is allowed per run"));
            AtomicBoolean done = new AtomicBoolean();
            AtomicBoolean hasText = new AtomicBoolean();
            return client.method(HttpMethod.valueOf(request.getMethod())).uri(request.getUrl())
                .headers(headers -> request.getHeaders().forEach(headers::set))
                .bodyValue(request.getBody()).retrieve().bodyToFlux(SSE)
                .filter(event -> event.data() != null)
                .map(ServerSentEvent::data)
                .<String>handle((data, sink) -> {
                    if ("[DONE]".equals(data)) {
                        done.set(true);
                        if (!hasText.get()) { sink.error(new IllegalStateException("Model returned no text")); return; }
                    } else {
                        try {
                            var choices = JSON.readTree(data).path("choices");
                            if (choices.isArray()) for (var choice : choices) {
                                if (!choice.path("delta").path("content").asText("").isEmpty()) hasText.set(true);
                            }
                        } catch (Exception error) {
                            sink.error(new IllegalStateException("Invalid model stream event")); return;
                        }
                    }
                    sink.next(data);
                })
                .concatWith(Mono.defer(() -> done.get() ? Mono.empty() :
                        Mono.error(new IllegalStateException("Model stream ended without completion marker"))));
        });
    }

    @Override
    public HttpResponse execute(HttpRequest request) {
        throw new UnsupportedOperationException("Only streaming model calls are supported");
    }

    @Override public void close() { }
}
