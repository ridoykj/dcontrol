package com.dcontrol.controller.device;

import com.dcontrol.controller.common.ws.RelayedCommand;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Every {@link RelayedCommand} bean, by wire type. */
@Component
public class RelayedCommands {

    private final Map<String, RelayedCommand<?, ?>> byWire;

    public RelayedCommands(List<RelayedCommand<?, ?>> commands) {
        this.byWire = commands.stream()
                .collect(Collectors.toUnmodifiableMap(command -> command.type().wire(), Function.identity()));
    }

    public Optional<RelayedCommand<?, ?>> find(String wire) {
        return Optional.ofNullable(byWire.get(wire));
    }
}
