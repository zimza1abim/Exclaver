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

import android.util.Base64
import io.nekohasekai.sagernet.R

data class SmartRouteWireGuardPeer(
    val publicKey: String,
    val preSharedKey: String?,
    val endpointHost: String,
    val endpointPort: Int,
    val keepAlive: Int?,
) {
    val endpoint: String
        get() = if (endpointHost.contains(':')) "[$endpointHost]:$endpointPort" else "$endpointHost:$endpointPort"
}

data class SmartRouteWireGuardConfig(
    val privateKey: String,
    val address: List<String>,
    val dns: List<String>,
    val mtu: Int?,
    val reserved: List<Int>?,
    val peer: SmartRouteWireGuardPeer,
    val warnings: List<Int>,
)

object SmartRouteWireGuardParser {

    fun parse(conf: String): SmartRouteWireGuardConfig {
        val sections = parseSections(conf)
        val iface = sections.firstOrNull { it.name.equals("Interface", true) }
            ?: throw SmartRouteInputException(R.string.smart_route_error_invalid_wireguard_conf)
        val peers = sections.filter { it.name.equals("Peer", true) }
        if (peers.isEmpty()) throw SmartRouteInputException(R.string.smart_route_error_peer_public_key_missing)

        val privateKey = iface.first("privatekey")
            ?: throw SmartRouteInputException(R.string.smart_route_error_private_key_missing)
        val address = iface.all("address").flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        if (address.isEmpty()) throw SmartRouteInputException(R.string.smart_route_error_address_missing)
        val mtu = iface.first("mtu")?.toIntOrNull()?.takeIf { it > 0 }
        val dns = iface.all("dns").flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        val reserved = iface.first("reserved")?.let { parseReserved(it) }

        var selectedPeer: SmartRouteWireGuardPeer? = null
        for (peerSection in peers) {
            val publicKey = peerSection.first("publickey") ?: continue
            val endpointValue = peerSection.first("endpoint") ?: continue
            val endpoint = parseEndpoint(endpointValue)
            selectedPeer = SmartRouteWireGuardPeer(
                publicKey = publicKey,
                preSharedKey = peerSection.first("presharedkey")?.takeIf { it.isNotEmpty() },
                endpointHost = endpoint.first,
                endpointPort = endpoint.second,
                keepAlive = peerSection.first("persistentkeepalive")?.toIntOrNull()?.takeIf { it > 0 },
            )
            break
        }

        val peer = selectedPeer ?: run {
            val firstPeer = peers.first()
            when {
                firstPeer.first("publickey") == null -> throw SmartRouteInputException(R.string.smart_route_error_peer_public_key_missing)
                firstPeer.first("endpoint") == null -> throw SmartRouteInputException(R.string.smart_route_error_endpoint_missing)
                else -> throw SmartRouteInputException(R.string.smart_route_error_invalid_wireguard_conf)
            }
        }

        return SmartRouteWireGuardConfig(
            privateKey = privateKey,
            address = address,
            dns = dns,
            mtu = mtu,
            reserved = reserved,
            peer = peer,
            warnings = if (peers.size > 1) listOf(R.string.smart_route_warning_multiple_peers) else emptyList(),
        )
    }

    private data class Section(val name: String, val values: MutableMap<String, MutableList<String>> = linkedMapOf()) {
        fun all(key: String): List<String> = values[key.lowercase()].orEmpty()
        fun first(key: String): String? = all(key).firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun parseSections(conf: String): List<Section> {
        val sections = mutableListOf<Section>()
        var current: Section? = null
        try {
            conf.lineSequence().forEach { rawLine ->
                val line = rawLine.substringBefore('#').substringBefore(';').trim()
                if (line.isEmpty()) return@forEach
                if (line.startsWith("[") && line.endsWith("]")) {
                    current = Section(line.removePrefix("[").removeSuffix("]").trim()).also { sections.add(it) }
                    return@forEach
                }
                val section = current ?: throw SmartRouteInputException(R.string.smart_route_error_invalid_wireguard_conf)
                val separator = line.indexOf('=')
                if (separator <= 0) throw SmartRouteInputException(R.string.smart_route_error_invalid_wireguard_conf)
                val key = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                section.values.getOrPut(key) { mutableListOf() }.add(value)
            }
        } catch (e: SmartRouteInputException) {
            throw e
        } catch (_: Exception) {
            throw SmartRouteInputException(R.string.smart_route_error_invalid_wireguard_conf)
        }
        return sections
    }

    private fun parseEndpoint(value: String): Pair<String, Int> {
        val endpoint = value.trim()
        val host: String
        val portText: String
        if (endpoint.startsWith("[")) {
            val end = endpoint.indexOf(']')
            if (end <= 1 || endpoint.getOrNull(end + 1) != ':') {
                throw SmartRouteInputException(R.string.smart_route_error_endpoint_missing)
            }
            host = endpoint.substring(1, end)
            portText = endpoint.substring(end + 2)
        } else {
            val split = endpoint.lastIndexOf(':')
            if (split <= 0) throw SmartRouteInputException(R.string.smart_route_error_endpoint_missing)
            host = endpoint.substring(0, split)
            portText = endpoint.substring(split + 1)
        }
        val port = portText.toIntOrNull()?.takeIf { it in 1..65535 }
            ?: throw SmartRouteInputException(R.string.smart_route_error_endpoint_port_invalid)
        if (host.isBlank()) throw SmartRouteInputException(R.string.smart_route_error_endpoint_missing)
        return host to port
    }

    private fun parseReserved(value: String): List<Int>? {
        val parts = value.split(',', ' ', '\t').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size == 3) {
            val numbers = parts.map { it.toIntOrNull() ?: return null }
            if (numbers.all { it in 0..255 }) return numbers
        }
        val decoded = runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull()
        if (decoded?.size == 3) return decoded.map { it.toUByte().toInt() }
        return null
    }
}
