package com.dcontrol.controller.device;

import com.dcontrol.controller.common.config.AppProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final DeviceSocketHandler deviceHandler;
    private final DeviceHandshakeInterceptor deviceInterceptor;
    private final BrowserSocketHandler browserHandler;
    private final WebHandshakeInterceptor webInterceptor;
    private final AppProperties properties;

    public WebSocketConfig(DeviceSocketHandler deviceHandler, DeviceHandshakeInterceptor deviceInterceptor,
                           BrowserSocketHandler browserHandler, WebHandshakeInterceptor webInterceptor,
                           AppProperties properties) {
        this.deviceHandler = deviceHandler;
        this.deviceInterceptor = deviceInterceptor;
        this.browserHandler = browserHandler;
        this.webInterceptor = webInterceptor;
        this.properties = properties;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Phones send no Origin header; same-origin checks don't apply to them.
        registry.addHandler(deviceHandler, "/ws/device").addInterceptors(deviceInterceptor);
        registry.addHandler(browserHandler, "/ws/web")
                .addInterceptors(webInterceptor)
                .setAllowedOriginPatterns(properties.ws().allowedOrigins().toArray(String[]::new));
    }

    /** Frames above the limit close the socket with 1009. */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.ws().maxMessageBytes());
        container.setMaxBinaryMessageBufferSize(1024);
        return container;
    }
}
