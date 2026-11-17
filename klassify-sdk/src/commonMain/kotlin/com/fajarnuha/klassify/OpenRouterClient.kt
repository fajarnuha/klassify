package com.fajarnuha.klassify

import io.ktor.client.HttpClient

public class OpenRouterException(public val status: Int, public val responseBody: String) :
    RuntimeException("OpenRouter HTTP $status: $responseBody")

/** OpenRouter's Decisions API with Jev as the default. Owns and closes the supplied Ktor client. */
public class OpenRouterClient public constructor(
    apiKey: String,
    httpClient: HttpClient = HttpClient(),
    endpoint: String = "https://openrouter.ai/api/alpha/decisions",
) : ClassificationClient by SystemOneClient(
    apiKey, httpClient, endpoint, "typesafe/jev-1.13", "OpenRouter", ::OpenRouterException,
)
