package com.agentscope.brain.runtime.internal.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.model.transport.HttpRequest;
import io.agentscope.core.model.transport.HttpResponse;
import io.agentscope.core.model.transport.HttpTransport;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** One cancellable HTTP stream per run; no non-streaming fallback or automatic resend. */
public final class TextStreamingTransport implements HttpTransport {
    private static final Logger LOG = LoggerFactory.getLogger(TextStreamingTransport.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE = new ParameterizedTypeReference<>() { };
    private final WebClient client = WebClient.create();
    private final AtomicBoolean requested = new AtomicBoolean();

    @Override public Flux<String> stream(HttpRequest request) {
        return Flux.defer(() -> {
            if (!requested.compareAndSet(false, true)) {
                LOG.warn("Model stream rejected: code=MODEL_RETRY_FORBIDDEN endpoint={}", endpoint(request));
                return Flux.error(new IllegalStateException("MODEL_RETRY_FORBIDDEN"));
            }
            var done = new AtomicBoolean();
            return client.method(HttpMethod.valueOf(request.getMethod())).uri(request.getUrl())
                    .headers(headers -> request.getHeaders().forEach(headers::set))
                    .bodyValue(request.getBody()).exchangeToFlux(response -> {
                        var contentType = response.headers().contentType().orElse(MediaType.APPLICATION_OCTET_STREAM);
                        if (!response.statusCode().is2xxSuccessful() || !MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType))
                            return response.bodyToMono(String.class).defaultIfEmpty("").onErrorReturn("")
                                    .flatMapMany(body -> {
                                        LOG.warn("Model stream rejected: code=MODEL_STREAM_UNAVAILABLE status={} contentType={} endpoint={} body={}",
                                                response.statusCode().value(), contentType, endpoint(request), brief(body));
                                        return Flux.error(new IllegalStateException("MODEL_STREAM_UNAVAILABLE"));
                                    });
                        return response.bodyToFlux(SSE);
                    })
                    .filter(event -> event.data() != null)
                    .map(ServerSentEvent::data)
                    .<String>handle((data, sink) -> {
                        if ("[DONE]".equals(data)) { done.set(true); sink.next(data); return; }
                        try {
                            var choices = JSON.readTree(data).path("choices");
                            if (!choices.isArray()) throw new IllegalArgumentException("choices is not an array");
                            for (var choice : choices) {
                                var delta = choice.path("delta");
                                if (delta.hasNonNull("tool_calls") || delta.hasNonNull("function_call")
                                        || "tool_calls".equals(choice.path("finish_reason").asText())) {
                                    LOG.warn("Model stream rejected: code=MODEL_TOOL_CALL_FORBIDDEN endpoint={} finishReason={}",
                                            endpoint(request), choice.path("finish_reason").asText(""));
                                    sink.error(new IllegalStateException("MODEL_TOOL_CALL_FORBIDDEN")); return;
                                }
                                if ("length".equals(choice.path("finish_reason").asText())) {
                                    LOG.warn("Model stream rejected: code=MODEL_OUTPUT_TRUNCATED endpoint={}", endpoint(request));
                                    sink.error(new IllegalStateException("MODEL_OUTPUT_TRUNCATED")); return;
                                }
                            }
                            sink.next(data);
                        } catch (Exception invalid) {
                            LOG.warn("Model stream rejected: code=MODEL_STREAM_INVALID endpoint={} cause={} payload={}",
                                    endpoint(request), invalid.toString(), brief(data));
                            sink.error(new IllegalStateException("MODEL_STREAM_INVALID", invalid));
                        }
                    })
                    .concatWith(Mono.defer(() -> {
                        if (done.get()) return Mono.empty();
                        LOG.warn("Model stream rejected: code=MODEL_STREAM_INCOMPLETE endpoint={}", endpoint(request));
                        return Mono.error(new IllegalStateException("MODEL_STREAM_INCOMPLETE"));
                    }));
        });
    }
    private static String endpoint(HttpRequest request) {
        var url = request.getUrl();
        if (url == null) return "";
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
    private static String brief(String value) {
        if (value == null || value.isBlank()) return "";
        var compact = value.replaceAll("\\s+", " ")
                .replaceAll("(?i)(bearer\\s+)\\S+", "$1[redacted]")
                .replaceAll("sk-[A-Za-z0-9._\\-]{8,}", "sk-[redacted]").trim();
        return compact.length() <= 400 ? compact : compact.substring(0, 400) + "...";
    }
    @Override public HttpResponse execute(HttpRequest request) { throw new UnsupportedOperationException("Streaming required"); }
    @Override public void close() { }
}
