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

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.R

data class SmartRouteGeneratedConfig(
    val json: String,
    val domains: List<String>,
    val warnings: List<Int>,
)

object SmartRouteConfigGenerator {

    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun generate(defaultConf: String, bypassConf: String, domainText: String): SmartRouteGeneratedConfig {
        val default = SmartRouteWireGuardParser.parse(defaultConf)
        val bypass = SmartRouteWireGuardParser.parse(bypassConf)
        val domains = SmartRouteDomainNormalizer.normalizeLines(domainText)
        if (domains.isEmpty()) throw SmartRouteInputException(R.string.smart_route_error_domain_empty)

        val root = JsonObject()
        root.add("dns", JsonObject().apply {
            add("servers", JsonArray().apply {
                add(JsonObject().apply { addProperty("address", "1.1.1.1") })
            })
        })
        root.add("log", JsonObject().apply { addProperty("loglevel", "warning") })
        root.add("outbounds", JsonArray().apply {
            add(wireGuardOutbound("smart-default", default))
            add(wireGuardOutbound("smart-bypass", bypass))
            add(JsonObject().apply {
                addProperty("tag", "dns-out")
                addProperty("protocol", "dns")
            })
        })
        root.add("routing", JsonObject().apply {
            addProperty("domainStrategy", "AsIs")
            add("rules", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("type", "field")
                    add("inboundTag", JsonArray().apply { add("dns-in") })
                    addProperty("outboundTag", "dns-out")
                })
                add(JsonObject().apply {
                    addProperty("type", "field")
                    add("domain", JsonArray().apply { domains.forEach { add(it) } })
                    addProperty("outboundTag", "smart-bypass")
                })
                add(JsonObject().apply {
                    addProperty("type", "field")
                    addProperty("network", "tcp,udp")
                    addProperty("outboundTag", "smart-default")
                })
            })
        })

        return SmartRouteGeneratedConfig(
            json = gson.toJson(root),
            domains = domains,
            warnings = default.warnings + bypass.warnings,
        )
    }

    private fun wireGuardOutbound(tag: String, config: SmartRouteWireGuardConfig): JsonObject {
        return JsonObject().apply {
            addProperty("tag", tag)
            addProperty("protocol", "wireguard")
            add("settings", JsonObject().apply {
                add("address", JsonArray().apply { config.address.forEach { add(it) } })
                addProperty("secretKey", config.privateKey)
                config.mtu?.let { addProperty("mtu", it) }
                config.reserved?.let { reserved ->
                    add("reserved", JsonArray().apply { reserved.forEach { add(it) } })
                }
                add("peers", JsonArray().apply {
                    add(JsonObject().apply {
                        addProperty("publicKey", config.peer.publicKey)
                        config.peer.preSharedKey?.let { addProperty("preSharedKey", it) }
                        addProperty("endpoint", config.peer.endpoint)
                        config.peer.keepAlive?.let { addProperty("keepAlive", it) }
                    })
                })
            })
        }
    }
}
