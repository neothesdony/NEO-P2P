package com.neop2p.networking

import io.ktor.client.*
import io.ktor.client.engine.darwin.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.KotlinxSerializersKit
import kotlinx.serialization.json.Json

actual fun createHttpClient(): HttpClient {
    return HttpClient(Darwin) {
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