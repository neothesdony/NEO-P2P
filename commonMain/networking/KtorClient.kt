package com.neop2p.networking

import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.KotlinxSerializersKit
import kotlinx.serialization.json.Json

// Note: The actual engine (Android, iOS) will be provided in the platform-specific modules.
expect class ActualHttpClientEngine : HttpClientEngine

actual fun createHttpClient(): HttpClient {
    return HttpClient(ActualHttpClientEngine) {
        install(JsonFeature) {
            serializer = KotlinxSerializersKit
        }
        defaultRequest {
            accept(application/json)
            contentType(application/json)
            // You can set baseUrl, headers, etc. here or in the platform-specific setup.
        }
        // Optional: logging
        // install(Logging) {
        //     logger = Logger.DEFAULT
        //     level = LogLevel.BODY
        // }
    }
}