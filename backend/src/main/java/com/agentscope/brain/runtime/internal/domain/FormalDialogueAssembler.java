package com.agentscope.brain.runtime.internal.domain;

import com.agentscope.brain.conversation.api.FormalContentBlock;
import com.agentscope.brain.conversation.api.FormalMessage;
import com.agentscope.brain.conversation.api.FormalSender;
import com.agentscope.brain.media.api.MediaKind;
import io.agentscope.core.message.DataBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.URLSource;
import java.util.ArrayList;
import java.util.List;

/** Assembles phase-one working-context messages (text + IMAGE locators only). */
public final class FormalDialogueAssembler {
    public List<Msg> assemble(List<FormalMessage> formalMessages) {
        List<Msg> out = new ArrayList<>();
        if (formalMessages == null) {
            return out;
        }
        for (FormalMessage message : formalMessages) {
            out.add(toMsg(message));
        }
        return out;
    }

    public Msg toMsg(FormalMessage message) {
        List<io.agentscope.core.message.ContentBlock> blocks = new ArrayList<>();
        for (FormalContentBlock block : message.blocks()) {
            if (block instanceof FormalContentBlock.Text text) {
                blocks.add(TextBlock.builder().text(text.text()).build());
            } else if (block instanceof FormalContentBlock.Media media
                    && media.kind() == MediaKind.IMAGE) {
                blocks.add(DataBlock.builder()
                        .id(media.mediaId())
                        .source(new URLSource(FormalMediaLocator.forMediaId(media.mediaId()), "image/*"))
                        .build());
            }
        }
        return Msg.builder()
                .id(message.messageId())
                .role(message.sender() == FormalSender.ASSISTANT ? MsgRole.ASSISTANT : MsgRole.USER)
                .content(blocks)
                .build();
    }
}
