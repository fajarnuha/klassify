package com.fajarnuha.klassify

import io.ktor.client.HttpClient

public class TypeSafeException(public val status: Int, public val responseBody: String) :
    RuntimeException("TypeSafe HTTP $status: $responseBody")

/** A client owns and closes its Ktor client, including one passed by the caller. */
public class TypeSafeClient public constructor(
    apiKey: String,
    httpClient: HttpClient = HttpClient(),
    endpoint: String = "https://api.typesafe.ai/v1/systemone",
) : ClassificationClient by SystemOneClient(
    apiKey, httpClient, endpoint, "jev-latest", "TypeSafe", ::TypeSafeException,
)
