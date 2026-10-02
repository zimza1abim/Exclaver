/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  Exclave contributors                                   *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.ui.profile

import android.content.DialogInterface
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteConfigGenerator

class SmartRouteConfBottomSheet : BottomSheetDialogFragment() {

    interface Listener {
        fun onSmartRouteConfDone(profileName: String, dnsServer: String): Boolean
        fun onSmartRouteConfDismissed(profileName: String, dnsServer: String)
        fun onSmartRouteConfFile(default: Boolean)
        fun onSmartRouteConfClipboard(default: Boolean)
        fun onSmartRouteConfEdit(default: Boolean)
        fun onSmartRouteConfClear(default: Boolean)
    }

    /** One route conf card (default or bypass) inside the sheet. */
    private inner class ConfCard(root: View, private val default: Boolean, listener: Listener) {
        private val badge = root.findViewById<TextView>(R.id.conf_badge)
        private val source = root.findViewById<TextView>(R.id.conf_source)
        private val edit = root.findViewById<MaterialButton>(R.id.conf_edit)
        private val clear = root.findViewById<View>(R.id.conf_clear)

        init {
            val primary = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
            root.findViewById<ImageView>(R.id.conf_icon).apply {
                setImageResource(if (default) R.drawable.baseline_public_24 else R.drawable.ic_baseline_compare_arrows_24)
                imageTintList = ColorStateList.valueOf(primary)
                backgroundTintList = ColorStateList.valueOf(primary).withAlpha(0x22)
            }
            root.findViewById<TextView>(R.id.conf_title).setText(
                if (default) R.string.smart_route_default_route_title else R.string.smart_route_bypass_route_title,
            )
            root.findViewById<TextView>(R.id.conf_summary).setText(
                if (default) R.string.smart_route_default_route_summary else R.string.smart_route_bypass_route_summary,
            )
            root.findViewById<View>(R.id.conf_from_file).setOnClickListener { listener.onSmartRouteConfFile(default) }
            root.findViewById<View>(R.id.conf_from_clipboard).setOnClickListener { listener.onSmartRouteConfClipboard(default) }
            edit.setOnClickListener { listener.onSmartRouteConfEdit(default) }
            clear.setOnClickListener { listener.onSmartRouteConfClear(default) }
        }

        fun update(ready: Boolean, label: String) {
            val context = badge.context
            badge.setText(if (ready) R.string.smart_route_badge_ready else R.string.smart_route_badge_missing)
            badge.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(context, if (ready) R.color.smart_route_status_ok else R.color.smart_route_status_error),
            )
            source.text = if (ready) {
                label.ifBlank { getString(R.string.smart_route_conf_source_saved) }
            } else {
                getString(R.string.smart_route_conf_empty)
            }
            source.alpha = if (ready) 1f else 0.6f
            edit.setText(if (ready) R.string.smart_route_action_edit else R.string.smart_route_action_write)
            clear.visibility = if (ready) View.VISIBLE else View.GONE
        }
    }

    private var defaultCard: ConfCard? = null
    private var bypassCard: ConfCard? = null
    private lateinit var profileNameInput: TextInputEditText
    private lateinit var dnsServerInput: TextInputEditText

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.layout_smart_route_conf_sheet, container, false)
    }

    override fun onStart() {
        super.onStart()
        // Open fully: both route cards and the Done button should be visible without dragging.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val listener = parentFragment as? Listener ?: return
        profileNameInput = view.findViewById(R.id.profile_name)
        dnsServerInput = view.findViewById(R.id.smart_route_dns_server)
        defaultCard = ConfCard(view.findViewById(R.id.default_card), default = true, listener)
        bypassCard = ConfCard(view.findViewById(R.id.bypass_card), default = false, listener)

        profileNameInput.setText(requireArguments().getString(ARG_PROFILE_NAME).orEmpty())
        dnsServerInput.setText(requireArguments().getString(ARG_DNS_SERVER).orEmpty())
        updateConfStatus(
            defaultReady = requireArguments().getBoolean(ARG_DEFAULT_READY),
            bypassReady = requireArguments().getBoolean(ARG_BYPASS_READY),
            defaultLabel = requireArguments().getString(ARG_DEFAULT_LABEL).orEmpty(),
            bypassLabel = requireArguments().getString(ARG_BYPASS_LABEL).orEmpty(),
        )

        view.findViewById<View>(R.id.conf_done).setOnClickListener {
            val canDismiss = listener.onSmartRouteConfDone(currentProfileName(), currentDnsServer())
            if (canDismiss) dismissAllowingStateLoss()
        }
    }

    // Swipe-down / back / outside tap: keep what was typed instead of silently dropping it.
    override fun onCancel(dialog: DialogInterface) {
        super.onCancel(dialog)
        if (!::profileNameInput.isInitialized) return
        (parentFragment as? Listener)?.onSmartRouteConfDismissed(currentProfileName(), currentDnsServer())
    }

    private fun currentProfileName() = profileNameInput.text?.toString().orEmpty()

    private fun currentDnsServer() = dnsServerInput.text?.toString()?.takeIf { it.isNotBlank() }
        ?: SmartRouteConfigGenerator.DEFAULT_DNS_SERVER

    fun updateConfStatus(defaultReady: Boolean, bypassReady: Boolean, defaultLabel: String, bypassLabel: String) {
        defaultCard?.update(defaultReady, defaultLabel)
        bypassCard?.update(bypassReady, bypassLabel)
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
