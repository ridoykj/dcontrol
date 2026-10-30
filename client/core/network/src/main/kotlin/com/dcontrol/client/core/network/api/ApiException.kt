package com.dcontrol.client.core.network.api

import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** A failed controller call. [code] is the problem-details `code`, or [NETWORK] / [INVALID_RESPONSE]. */
class ApiException(
    val status: Int?,
    val code: String?,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        const val NETWORK = "NETWORK"
        const val INVALID_RESPONSE = "INVALID_RESPONSE"
    }
}

@Serializable
internal data class ProblemDetail(
    val title: String? = null,
    val status: Int? = null,
    val detail: String? = null,
    val code: String? = null,
)

suspend fun <T> apiCall(json: Json, block: suspend () -> T): T = try {
    block()
} catch (e: HttpException) {
    throw e.toApiException(json)
} catch (e: IOException) {
    throw ApiException(null, ApiException.NETWORK, e.message ?: "Network error", e)
} catch (e: SerializationException) {
    throw ApiException(null, ApiException.INVALID_RESPONSE, e.message ?: "Invalid response", e)
}

internal fun HttpException.toApiException(json: Json): ApiException {
    val body = response()?.errorBody()?.string()
    val problem = body?.let {
        try {
            json.decodeFromString(ProblemDetail.serializer(), it)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
    val message = problem?.detail ?: problem?.title ?: message()
    return ApiException(code(), problem?.code, message, this)
}
