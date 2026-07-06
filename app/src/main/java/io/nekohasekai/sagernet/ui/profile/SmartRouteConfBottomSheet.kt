/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  Exclave contributors                                   *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteConfigGenerator

class SmartRouteConfBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onSmartRouteConfDone(profileName: String, dnsServer: String): Boolean
        fun onSmartRouteConfFile(default: Boolean)
        fun onSmartRouteConfClipboard(default: Boolean)
        fun onSmartRouteConfEdit(default: Boolean)
        fun onSmartRouteConfClear(default: Boolean)
    }

    private lateinit var defaultStatus: TextView
    private lateinit var bypassStatus: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.layout_smart_route_conf_sheet, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val listener = parentFragment as? Listener ?: return
        val profileName = view.findViewById<TextInputEditText>(R.id.profile_name)
        val dnsServer = view.findViewById<TextInputEditText>(R.id.smart_route_dns_server)
        defaultStatus = view.findViewById(R.id.default_conf_status)
        bypassStatus = view.findViewById(R.id.bypass_conf_status)

        profileName.setText(requireArguments().getString(ARG_PROFILE_NAME).orEmpty())
        dnsServer.setText(requireArguments().getString(ARG_DNS_SERVER).orEmpty())
        updateConfStatus(
            defaultReady = requireArguments().getBoolean(ARG_DEFAULT_READY),
            bypassReady = requireArguments().getBoolean(ARG_BYPASS_READY),
            defaultLabel = requireArguments().getString(ARG_DEFAULT_LABEL).orEmpty(),
            bypassLabel = requireArguments().getString(ARG_BYPASS_LABEL).orEmpty(),
        )

        view.findViewById<Button>(R.id.default_from_file).setOnClickListener {
            listener.onSmartRouteConfFile(default = true)
        }
        view.findViewById<Button>(R.id.default_from_clipboard).setOnClickListener {
            listener.onSmartRouteConfClipboard(default = true)
        }
        view.findViewById<Button>(R.id.default_edit).setOnClickListener {
            listener.onSmartRouteConfEdit(default = true)
        }
        view.findViewById<Button>(R.id.default_clear).setOnClickListener {
            listener.onSmartRouteConfClear(default = true)
        }
        view.findViewById<Button>(R.id.bypass_from_file).setOnClickListener {
            listener.onSmartRouteConfFile(default = false)
        }
        view.findViewById<Button>(R.id.bypass_from_clipboard).setOnClickListener {
            listener.onSmartRouteConfClipboard(default = false)
        }
        view.findViewById<Button>(R.id.bypass_edit).setOnClickListener {
            listener.onSmartRouteConfEdit(default = false)
        }
        view.findViewById<Button>(R.id.bypass_clear).setOnClickListener {
            listener.onSmartRouteConfClear(default = false)
        }
        view.findViewById<Button>(R.id.conf_done).setOnClickListener {
            val canDismiss = listener.onSmartRouteConfDone(
                profileName.text?.toString().orEmpty(),
                dnsServer.text?.toString()?.takeIf { it.isNotBlank() }
                    ?: SmartRouteConfigGenerator.DEFAULT_DNS_SERVER,
            )
            if (canDismiss) dismissAllowingStateLoss()
        }
    }

    fun updateConfStatus(defaultReady: Boolean, bypassReady: Boolean, defaultLabel: String, bypassLabel: String) {
        if (!::defaultStatus.isInitialized || !::bypassStatus.isInitialized) return
        defaultStatus.text = if (defaultReady) {
            getString(
                R.string.smart_route_default_route_current,
                defaultLabel.ifBlank { getString(R.string.smart_route_conf_source_saved) },
            )
        } else {
            getString(R.string.smart_route_default_conf_missing)
        }
        bypassStatus.text = if (bypassReady) {
            getString(
                R.string.smart_route_bypass_route_current,
                bypassLabel.ifBlank { getString(R.string.smart_route_conf_source_saved) },
            )
        } else {
            getString(R.string.smart_route_bypass_conf_missing)
        }
    }

    companion object {
        private const val ARG_PROFILE_NAME = "profileName"
        private const val ARG_DNS_SERVER = "dnsServer"
        private const val ARG_DEFAULT_READY = "defaultReady"
        private const val ARG_BYPASS_READY = "bypassReady"
        private const val ARG_DEFAULT_LABEL = "defaultLabel"
        private const val ARG_BYPASS_LABEL = "bypassLabel"

        fun newInstance(
            profileName: String,
            dnsServer: String,
            defaultReady: Boolean,
            bypassReady: Boolean,
            defaultLabel: String,
            bypassLabel: String,
        ) = SmartRouteConfBottomSheet().apply {
            arguments = Bundle().apply {
                putString(ARG_PROFILE_NAME, profileName)
                putString(ARG_DNS_SERVER, dnsServer)
                putBoolean(ARG_DEFAULT_READY, defaultReady)
                putBoolean(ARG_BYPASS_READY, bypassReady)
                putString(ARG_DEFAULT_LABEL, defaultLabel)
                putString(ARG_BYPASS_LABEL, bypassLabel)
            }
        }
    }
}
