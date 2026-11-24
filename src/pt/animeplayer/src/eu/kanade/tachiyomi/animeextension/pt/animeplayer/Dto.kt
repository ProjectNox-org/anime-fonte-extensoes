package eu.kanade.tachiyomi.animeextension.pt.animeplayer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class VerifyRequestDto(
    @SerialName("turnstile_token") val turnstileToken: String,
    @SerialName("player_keys") val playerKeys: List<String>,
)

@Serializable
class VerifyServerDto(
    val id: String,
    val label: String,
    val index: Int,
)

@Serializable
class VerifyResponseDto(
    val success: Boolean,
    val ticket: String? = null,
    val servers: List<VerifyServerDto> = emptyList(),
)

@Serializable
class LoadRequestDto(
    val ticket: String,
    @SerialName("player_key") val playerKey: String,
)

@Serializable
class LoadResponseDto(
    val success: Boolean,
    val url: String? = null,
    val message: String? = null,
)

@Serializable
class ApiErrorDto(
    val code: String? = null,
    val message: String? = null,
)

@Serializable
class GuardBridgePayloadDto(
    val ticket: String? = null,
    val servers: List<VerifyServerDto> = emptyList(),
    val urls: List<String> = emptyList(),
    val stage: String? = null,
    val turnstileLoaded: Boolean = false,
    val turnstileRendered: Boolean = false,
    val verifyStatus: Int? = null,
    val loadStatus: Int? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val turnstileToken: String? = null,
    val playerKeys: List<String> = emptyList(),
)
