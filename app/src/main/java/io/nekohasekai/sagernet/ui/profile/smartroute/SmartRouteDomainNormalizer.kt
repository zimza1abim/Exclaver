/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  Exclave contributors                                   *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui.profile.smartroute

import io.nekohasekai.sagernet.R
import java.net.URI

class SmartRouteInputException(
    val stringRes: Int,
    val detail: String? = null,
) : IllegalArgumentException(detail)

object SmartRouteDomainNormalizer {

    private val prefixedRule = Regex("^(domain|full|keyword|regexp|geosite):(.+)$", RegexOption.IGNORE_CASE)
    private val ascii = Regex("^[\\x00-\\x7F]+$")
    private val domainLike = Regex("^[A-Za-z0-9._*-]+$")

    fun normalizeLines(text: String): List<String> {
        val result = LinkedHashSet<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            result.add(normalize(line))
        }
        return result.toList()
    }

    fun normalize(input: String): String {
        var value = input.trim()
        if (value.isEmpty()) throw SmartRouteInputException(R.string.smart_route_error_invalid_domain, input)

        prefixedRule.matchEntire(value)?.let { match ->
            val type = match.groupValues[1].lowercase()
            val body = match.groupValues[2].trim()
            if (body.isEmpty()) throw SmartRouteInputException(R.string.smart_route_error_invalid_domain, input)
            return when (type) {
                "domain", "full" -> "$type:${normalizeDomain(body, input)}"
                "keyword" -> "$type:$body"
                "regexp" -> "$type:$body"
                "geosite" -> "$type:${body.lowercase()}"
                else -> throw SmartRouteInputException(R.string.smart_route_error_invalid_domain, input)
            }
        }

        if (value.contains("://")) {
            val uri = runCatching { URI(value) }.getOrNull()
                ?: throw SmartRouteInputException(R.string.smart_route_error_invalid_domain, input)
            value = uri.host ?: throw SmartRouteInputException(R.string.smart_route_error_invalid_domain, input)
        }

        if (value.startsWith("*.")) {
            value = value.removePrefix("*.")
        }

        return "domain:${normalizeDomain(value, input)}"
    }

    private fun normalizeDomain(domain: String, original: String): String {
        var value = domain.trim().trimEnd('.')
        if (value.startsWith("*.")) value = value.removePrefix("*.")
        if (value.isEmpty() || value.contains('/') || value.contains(':') || value.any { it.isWhitespace() }) {
            throw SmartRouteInputException(R.string.smart_route_error_invalid_domain, original)
        }
        if (!domainLike.matches(value)) {
            throw SmartRouteInputException(R.string.smart_route_error_invalid_domain, original)
        }
        if (value.contains("..") || value.split('.').any { it.isEmpty() }) {
            throw SmartRouteInputException(R.string.smart_route_error_invalid_domain, original)
        }
        if (ascii.matches(value)) value = value.lowercase()
        return value
    }
}
