package com.neop2p.networking

import io.ktor.client.engine.okhttp.OkHttp

// Actual implementation for Android
actual class ActualHttpClientEngine(actual private val engine: OkHttp) : HttpClientEngine by engine

// We need to provide the actual createHttpClient function that returns an HttpClient with the Android engine.
actual fun createHttpClient(): io.ktor.client.HttpClient {
    return io.ktor.client.HttpClient(OkHttp) {
        // Install JSON feature (if not already installed in the common part? Note: common part installs JsonFeature)
        // But note: the common part already installs JsonFeature. However, we are creating a new instance here.
        // We can either rely on the common part's installation (if we use a factory pattern) or re-install.
        // Since the common part's createHttpClient is not used (we are providing an actual), we must install the features here.
        // Alternatively, we can change the common part to not install and then install in the actual, but let's stick to the current plan.

        // However, note: the common part's createHttpClient is marked as actual, so we are providing the body.
        // We must install the same features as in the common part's expected function.

        // Let's install JSON feature and set default request as in the common part's expected function.
        install(JsonFeature) {
            serializer = kotlinx.serialization.kotlinx.json.KotlinxSerializersKit
        }
        defaultRequest {
            accept(io.ktor.http.ApplicationJson)
            contentType(io.ktor.http.ApplicationJson)
        }
    }
}