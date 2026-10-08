package com.fantamomo.slack.approver.data

import com.google.gson.Gson
import com.slack.api.util.json.GsonFactory
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.websocket.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

object SharedData {
    val json = Json { ignoreUnknownKeys = true }
    val gson: Gson = GsonFactory.createSnakeCase()

    val httpClient = HttpClient(OkHttp) {
        install(WebSockets)
        install(ContentNegotiation) {
            json(json)
        }
    }
}