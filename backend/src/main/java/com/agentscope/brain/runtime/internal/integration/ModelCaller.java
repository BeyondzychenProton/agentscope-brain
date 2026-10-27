package com.agentscope.brain.runtime.internal.integration;

import io.agentscope.core.message.Msg;
import java.util.List;

/** Replaceable model invocation used by the working-context Run. */
public interface ModelCaller {
    ModelResponse call(ModelRequestCopy request);

    record ModelRequestCopy(List<Msg> messages) {
        public ModelRequestCopy {
            messages = List.copyOf(messages);
        }
    }

    record ModelResponse(String text, List<byte[]> imageBytesInBody, List<String> httpImageUrls) {
        public ModelResponse {
            text = text == null ? "" : text;
            imageBytesInBody = imageBytesInBody == null ? List.of() : List.copyOf(imageBytesInBody);
            httpImageUrls = httpImageUrls == null ? List.of() : List.copyOf(httpImageUrls);
        }

        public static ModelResponse textOnly(String text) {
            return new ModelResponse(text, List.of(), List.of());
        }
    }
}
