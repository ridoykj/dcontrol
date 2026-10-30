package com.dcontrol.controller.clipboard;

import com.dcontrol.controller.common.ws.EmptyResult;
import com.dcontrol.controller.common.ws.MessageCodec;
import com.dcontrol.controller.common.ws.MessageType;
import com.dcontrol.controller.common.ws.RelayedCommand;
import com.dcontrol.controller.common.ws.WebSessionRegistry;
import com.dcontrol.controller.device.DeviceEventHandler;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Clipboard text in both directions (ws-v1.md, Phase 2). Text only; never stored. */
@Configuration(proxyBeanMethods = false)
public class ClipboardConfig {

    public static final int MAX_TEXT = 16_000;

    public record SetCommand(@NotNull UUID deviceId, @NotEmpty @Size(max = MAX_TEXT) String text)
            implements RelayedCommand.Targeted {

        @Override
        public Object toDevice() {
            return Map.of("text", text);
        }
    }

    public record Changed(@NotEmpty @Size(max = MAX_TEXT) String text) {
    }

    public record ChangedForWeb(UUID deviceId, String text, Instant receivedAt) {
    }

    @Bean
    RelayedCommand<SetCommand, EmptyResult> clipboardSet() {
        return RelayedCommand.of(MessageType.CLIPBOARD_SET, SetCommand.class, EmptyResult.class);
    }

    @Bean
    DeviceEventHandler clipboardChanged(MessageCodec codec, WebSessionRegistry web, Clock clock) {
        return new DeviceEventHandler(MessageType.CLIPBOARD_CHANGED, (source, envelope) -> {
            Changed changed = codec.payload(envelope, Changed.class);
            web.broadcast(source.accountKey(), codec.message(MessageType.CLIPBOARD_CHANGED, null,
                    new ChangedForWeb(source.deviceKey(), changed.text(), clock.instant())));
        });
    }
}
