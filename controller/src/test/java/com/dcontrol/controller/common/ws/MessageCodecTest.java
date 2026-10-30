package com.dcontrol.controller.common.ws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dcontrol.controller.common.error.ErrorCode;
import com.dcontrol.controller.device.DeviceProtocol;
import jakarta.validation.Validation;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MessageCodecTest {

    private final MessageCodec codec = new MessageCodec(JsonMapper.builder().build(),
            Validation.buildDefaultValidatorFactory().getValidator());

    @Test
    void decodesAndValidatesHello() {
        Envelope envelope = codec.decode("""
                {"v":1,"type":"device.hello","unknownField":true,"payload":{
                  "appVersion":"1.0.0","manufacturer":"Google","model":"Pixel 9","androidVersion":"17",
                  "sdkInt":37,"battery":{"level":87,"charging":false}}}""");
        DeviceProtocol.Hello hello = codec.payload(envelope, DeviceProtocol.Hello.class);
        assertThat(hello.battery().level()).isEqualTo(87);
        assertThat(hello.info().model()).isEqualTo("Pixel 9");
    }

    @Test
    void rejectsWrongVersionMissingTypeAndMalformedJson() {
        assertThatThrownBy(() -> codec.decode("{\"v\":2,\"type\":\"x\"}")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> codec.decode("{\"v\":1}")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> codec.decode("[1,2")).isInstanceOf(ProtocolException.class);
        assertThatThrownBy(() -> codec.decode("{\"v\":1,\"type\":\"x\",\"requestId\":\"nope\"}"))
                .isInstanceOf(ProtocolException.class);
    }

    @Test
    void reportsTheFirstInvalidField() {
        UUID requestId = UUID.randomUUID();
        Envelope envelope = codec.decode("""
                {"v":1,"type":"device.heartbeat","requestId":"%s","payload":{"battery":{"level":101,"charging":true}}}"""
                .formatted(requestId));
        assertThatThrownBy(() -> codec.payload(envelope, DeviceProtocol.Heartbeat.class))
                .isInstanceOfSatisfying(ProtocolException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION);
                    assertThat(e.getMessage()).contains("battery.level");
                    assertThat(e.requestId()).isEqualTo(requestId);
                });
    }

    @Test
    void encodesWithoutNullFields() {
        String text = codec.message(MessageType.DEVICE_PING, UUID.fromString("01929a3e-6f7c-7d1e-9a0b-3c4d5e6f7a8b"), null);
        assertThat(text).isEqualTo("{\"v\":1,\"type\":\"device.ping\",\"requestId\":\"01929a3e-6f7c-7d1e-9a0b-3c4d5e6f7a8b\"}");
        assertThat(codec.error(null, ErrorCode.TIMEOUT, "late"))
                .isEqualTo("{\"v\":1,\"type\":\"error\",\"error\":{\"code\":\"TIMEOUT\",\"message\":\"late\"}}");
    }
}
