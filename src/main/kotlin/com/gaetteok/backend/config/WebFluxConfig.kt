package com.gaetteok.backend.config

import com.gaetteok.backend.realtime.RoomWebSocketHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.reactive.CorsWebFilter
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter

@Configuration
class WebFluxConfig(
    private val roomWebSocketHandler: RoomWebSocketHandler,
    @param:Value("\${gaetteok.realtime.websocket-path:/ws/rooms}")
    private val websocketPath: String,
    @param:Value("\${gaetteok.realtime.allowed-origins:*}")
    private val allowedOrigins: String,
) {
    @Bean
    fun webSocketHandlerMapping(): HandlerMapping {
        return SimpleUrlHandlerMapping(
            mapOf(websocketPath to roomWebSocketHandler),
            -1,
        )
    }

    @Bean
    fun webSocketHandlerAdapter(): WebSocketHandlerAdapter {
        return WebSocketHandlerAdapter()
    }

    @Bean
    fun corsWebFilter(): CorsWebFilter {
        val config = CorsConfiguration()
        config.allowedOrigins = allowedOrigins.split(",").map(String::trim).filter(String::isNotBlank)
        config.allowedMethods = listOf("GET", "POST", "OPTIONS")
        config.allowedHeaders = listOf("*")
        config.allowCredentials = false

        val source = UrlBasedCorsConfigurationSource()
        source.registerCorsConfiguration("/**", config)
        return CorsWebFilter(source)
    }
}
