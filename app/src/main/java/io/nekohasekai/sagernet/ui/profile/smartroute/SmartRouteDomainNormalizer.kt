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

data class SmartRouteDomainParseResult(
    val domains: List<String>,
    val invalid: List<String>,
)

object SmartRouteDomainNormalizer {

    private val prefixedRule = Regex("^(domain|full|keyword|regexp|geosite):(.+)$", RegexOption.IGNORE_CASE)
    private val ascii = Regex("^[\\x00-\\x7F]+$")
    private val domainLike = Regex("^[A-Za-z0-9._*-]+$")
    private val separators = Regex("[\\s,;]+")
    private val portSuffix = Regex(":\\d{1,5}$")

    fun normalizeLines(text: String): List<String> {
        val result = LinkedHashSet<String>()
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            result.add(normalize(line))
        }
        return result.toList()
    }

    /**
     * Lenient variant for user input: accepts comma/space separated entries
     * and collects invalid entries instead of failing the whole batch.
     */
    fun parseLenient(text: String): SmartRouteDomainParseResult {
        val result = LinkedHashSet<String>()
        val invalid = ArrayList<String>()
        tokenize(text).forEach { token ->
            runCatching { normalize(token) }
                .onSuccess { result.add(it) }
                .onFailure { invalid.add(token) }
        }
        return SmartRouteDomainParseResult(result.toList(), invalid)
    }

    private fun tokenize(text: String): List<String> {
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .flatMap { line ->
                // keyword/regexp bodies may legitimately contain separators.
                if (line.startsWith("regexp:", ignoreCase = true) || line.startsWith("keyword:", ignoreCase = true)) {
                    sequenceOf(line)
                } else {
                    line.split(separators).asSequence().filter { it.isNotEmpty() }
                }
            }
            .toList()
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
        } else {
            // Scheme-less URL such as example.com/path?q=1 or example.com:443
            value = value.substringBefore('/').substringBefore('?').substringBefore('#')
            value = value.replace(portSuffix, "")
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
