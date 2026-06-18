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

package io.nekohasekai.sagernet.ui.profile

import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ui.ToolbarFragment
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteConfigGenerator
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteDomainNormalizer
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteInputException
import java.io.OutputStreamWriter

class SmartRouteFragment : ToolbarFragment(R.layout.layout_smart_route_settings) {

    private enum class ImportTarget {
        DefaultConf,
        BypassConf,
        Domains,
    }

    private var importTarget = ImportTarget.DefaultConf
    private var defaultConf = ""
    private var bypassConf = ""
    private var generatedJson = ""
    private var managedProfileId = 0L
    private var setupExpanded = true
    private var domainToolsExpanded = false
    private var jsonToolsExpanded = false

    private val domainItems = ArrayList<String>()
    private val selectedDomains = LinkedHashSet<String>()

    private lateinit var profileName: TextInputEditText
    private lateinit var domainInput: TextInputEditText
    private lateinit var domainSearch: TextInputEditText
    private lateinit var domainCount: TextView
    private lateinit var domainEmpty: TextView
    private lateinit var domainListView: LinearLayout
    private lateinit var status: TextView
    private lateinit var defaultConfStatus: TextView
    private lateinit var bypassConfStatus: TextView
    private lateinit var setupSection: View
    private lateinit var defaultConfActions: View
    private lateinit var bypassConfActions: View
    private lateinit var domainToolsSection: View
    private lateinit var jsonToolsSection: View
    private lateinit var toggleSetup: Button
    private lateinit var toggleDomainTools: Button
    private lateinit var toggleJsonTools: Button

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val text = readText(uri) ?: return@registerForActivityResult
        when (importTarget) {
            ImportTarget.DefaultConf -> {
                defaultConf = text
                showMessage(R.string.smart_route_default_loaded)
                if (hasCompleteConf()) setupExpanded = false
            }
            ImportTarget.BypassConf -> {
                bypassConf = text
                showMessage(R.string.smart_route_bypass_loaded)
                if (hasCompleteConf()) setupExpanded = false
            }
            ImportTarget.Domains -> {
                try {
                    setDomains(SmartRouteDomainNormalizer.normalizeLines(text))
                } catch (e: SmartRouteInputException) {
                    showError(formatInputError(e))
                }
            }
        }
        generatedJson = ""
        applyResponsiveState()
    }

    private val exportDomains = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            requireContext().contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream).use { writer ->
                    writer.write(domainItems.joinToString("\n"))
                }
            }
        }.onFailure {
            showError(getString(R.string.action_export_err))
        }.onSuccess {
            showMessage(R.string.action_export_msg)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.smart_route)

        bindViews(view)
        profileName.setText(getString(R.string.smart_route_default_profile_name))
        setDomains(emptyList(), resetSelection = true)
        loadManagedSmartRoute()
        setupExpanded = !hasCompleteConf()

        wireActions()
        applyResponsiveState()
    }

    private fun bindViews(view: View) {
        profileName = view.findViewById(R.id.profile_name)
        domainInput = view.findViewById(R.id.domain_input)
        domainSearch = view.findViewById(R.id.domain_search)
        domainCount = view.findViewById(R.id.domain_count)
        domainEmpty = view.findViewById(R.id.domain_empty)
        domainListView = view.findViewById(R.id.domain_checked_list)
        status = view.findViewById(R.id.smart_route_status)
        defaultConfStatus = view.findViewById(R.id.default_conf_status)
        bypassConfStatus = view.findViewById(R.id.bypass_conf_status)
        setupSection = view.findViewById(R.id.setup_section)
        defaultConfActions = view.findViewById(R.id.default_conf_actions)
        bypassConfActions = view.findViewById(R.id.bypass_conf_actions)
        domainToolsSection = view.findViewById(R.id.domain_tools_section)
        jsonToolsSection = view.findViewById(R.id.json_tools_section)
        toggleSetup = view.findViewById(R.id.toggle_setup)
        toggleDomainTools = view.findViewById(R.id.toggle_domain_tools)
        toggleJsonTools = view.findViewById(R.id.toggle_json_tools)
    }

    private fun wireActions() {
        requireView().findViewById<Button>(R.id.default_from_file).setOnClickListener {
            importTarget = ImportTarget.DefaultConf
            startFilesForResult(importFile, "*/*")
        }
        requireView().findViewById<Button>(R.id.bypass_from_file).setOnClickListener {
            importTarget = ImportTarget.BypassConf
            startFilesForResult(importFile, "*/*")
        }
        requireView().findViewById<Button>(R.id.domains_from_file).setOnClickListener {
            importTarget = ImportTarget.Domains
            startFilesForResult(importFile, "text/*")
        }
        requireView().findViewById<Button>(R.id.domains_export_file).setOnClickListener {
            exportDomains.launch("smart-route-domains.txt")
        }

        requireView().findViewById<Button>(R.id.default_from_clipboard).setOnClickListener { pasteConf(default = true) }
        requireView().findViewById<Button>(R.id.bypass_from_clipboard).setOnClickListener { pasteConf(default = false) }
        requireView().findViewById<Button>(R.id.default_edit).setOnClickListener {
            editTextDialog(R.string.smart_route_default_conf, defaultConf) {
                defaultConf = it
                generatedJson = ""
                if (hasCompleteConf()) setupExpanded = false
                applyResponsiveState()
            }
        }
        requireView().findViewById<Button>(R.id.bypass_edit).setOnClickListener {
            editTextDialog(R.string.smart_route_bypass_conf, bypassConf) {
                bypassConf = it
                generatedJson = ""
                if (hasCompleteConf()) setupExpanded = false
                applyResponsiveState()
            }
        }
        requireView().findViewById<Button>(R.id.default_clear).setOnClickListener {
            defaultConf = ""
            generatedJson = ""
            setupExpanded = true
            showMessage(R.string.smart_route_cleared)
            applyResponsiveState()
        }
        requireView().findViewById<Button>(R.id.bypass_clear).setOnClickListener {
            bypassConf = ""
            generatedJson = ""
            setupExpanded = true
            showMessage(R.string.smart_route_cleared)
            applyResponsiveState()
        }

        requireView().findViewById<Button>(R.id.domain_add).setOnClickListener { addDomainsFromInput() }
        requireView().findViewById<Button>(R.id.domains_select_visible).setOnClickListener { selectVisibleDomains() }
        requireView().findViewById<Button>(R.id.domains_clear_selection).setOnClickListener {
            selectedDomains.clear()
            renderDomainList()
        }
        requireView().findViewById<Button>(R.id.domains_remove_checked).setOnClickListener { removeCheckedDomains() }
        requireView().findViewById<Button>(R.id.domains_advanced_edit).setOnClickListener { editDomainsAdvanced() }
        requireView().findViewById<Button>(R.id.domains_normalize).setOnClickListener { normalizeDomains(sort = false) }
        requireView().findViewById<Button>(R.id.domains_sort).setOnClickListener { normalizeDomains(sort = true) }
        requireView().findViewById<Button>(R.id.domains_clear).setOnClickListener {
            setDomains(emptyList(), resetSelection = true)
            generatedJson = ""
        }

        requireView().findViewById<Button>(R.id.validate_config).setOnClickListener {
            generateOrShow(updateDomainText = true)?.let { result ->
                val warning = result.warnings.distinct().joinToString("\n") { getString(it) }
                showInfo(
                    getString(R.string.smart_route_validate),
                    listOf(getString(R.string.smart_route_validation_ok), warning).filter { it.isNotBlank() }.joinToString("\n\n"),
                )
            }
        }
        requireView().findViewById<Button>(R.id.preview_json).setOnClickListener {
            generateOrShow(updateDomainText = true)?.let {
                showInfo(getString(R.string.smart_route_preview_json), it.json)
            }
        }
        requireView().findViewById<Button>(R.id.copy_json).setOnClickListener {
            val json = generateOrShow(updateDomainText = true)?.json ?: return@setOnClickListener
            SagerNet.trySetPrimaryClip(json)
            showMessage(R.string.smart_route_json_copied)
        }
        requireView().findViewById<Button>(R.id.save_profile).setOnClickListener { saveProfile() }

        toggleSetup.setOnClickListener {
            setupExpanded = !setupExpanded
            applyResponsiveState()
        }
        toggleDomainTools.setOnClickListener {
            domainToolsExpanded = !domainToolsExpanded
            applyResponsiveState()
        }
        toggleJsonTools.setOnClickListener {
            jsonToolsExpanded = !jsonToolsExpanded
            applyResponsiveState()
        }

        domainInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addDomainsFromInput()
                true
            } else {
                false
            }
        }
        domainSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderDomainList()
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun pasteConf(default: Boolean) {
        val text = SagerNet.getClipboardText()
        if (text.isEmpty()) {
            showMessage(R.string.clipboard_empty)
            return
        }
        if (default) {
            defaultConf = text
            showMessage(R.string.smart_route_default_loaded)
        } else {
            bypassConf = text
            showMessage(R.string.smart_route_bypass_loaded)
        }
        generatedJson = ""
        if (hasCompleteConf()) setupExpanded = false
        applyResponsiveState()
    }

    private fun normalizeDomains(sort: Boolean) {
        try {
            val normalized = SmartRouteDomainNormalizer.normalizeLines(
                domainItems.joinToString("\n"),
            ).let { if (sort) it.sorted() else it }
            setDomains(normalized)
            generatedJson = ""
        } catch (e: SmartRouteInputException) {
            showError(formatInputError(e))
        }
    }

    private fun addDomainsFromInput() {
        try {
            val current = SmartRouteDomainNormalizer.normalizeLines(domainItems.joinToString("\n"))
            val incoming = SmartRouteDomainNormalizer.normalizeLines(domainInput.text?.toString().orEmpty())
            if (incoming.isEmpty()) throw SmartRouteInputException(R.string.smart_route_error_domain_empty)
            val merged = (current + incoming).distinct()
            val added = merged.size - current.size
            setDomains(merged)
            domainInput.setText("")
            generatedJson = ""
            showMessage(getString(R.string.smart_route_domains_added, added))
        } catch (e: SmartRouteInputException) {
            showError(formatInputError(e))
        }
    }

    private fun selectVisibleDomains() {
        selectedDomains.addAll(filteredDomains())
        renderDomainList()
    }

    private fun removeCheckedDomains() {
        if (selectedDomains.isEmpty()) {
            showMessage(R.string.smart_route_no_selected_domains)
            return
        }
        setDomains(domainItems.filterNot { it in selectedDomains }, resetSelection = true)
        generatedJson = ""
    }

    private fun setDomains(values: List<String>, resetSelection: Boolean = false) {
        domainItems.clear()
        domainItems.addAll(values.distinct())
        if (resetSelection) {
            selectedDomains.clear()
        } else {
            selectedDomains.retainAll(domainItems.toSet())
        }
        renderDomainList()
    }

    private fun renderDomainList() {
        val visibleDomains = filteredDomains()
        domainListView.removeAllViews()
        visibleDomains.forEach { domain ->
            domainListView.addView(MaterialCheckBox(requireContext()).apply {
                text = domain
                isSingleLine = false
                isChecked = domain in selectedDomains
                setPadding(0, 4, 0, 4)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedDomains.add(domain) else selectedDomains.remove(domain)
                    updateDomainCount()
                }
                setOnLongClickListener {
                    editDomain(domain)
                    true
                }
            })
        }
        domainEmpty.visibility = if (visibleDomains.isEmpty()) View.VISIBLE else View.GONE
        updateDomainCount()
    }

    private fun updateDomainCount() {
        val visible = filteredDomains().size
        domainCount.text = if (domainFilter().isBlank()) {
            getString(R.string.smart_route_domains_count_selected, domainItems.size, selectedDomains.size)
        } else {
            getString(R.string.smart_route_domains_filtered_count, visible, domainItems.size, selectedDomains.size)
        }
    }

    private fun filteredDomains(): List<String> {
        val filter = domainFilter()
        return if (filter.isBlank()) {
            domainItems
        } else {
            domainItems.filter { it.contains(filter, ignoreCase = true) }
        }
    }

    private fun domainFilter() = domainSearch.text?.toString()?.trim().orEmpty()

    private fun editDomain(domain: String) {
        val edit = EditText(requireContext()).apply {
            setText(domain)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.smart_route_edit_domain)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                try {
                    val replacement = SmartRouteDomainNormalizer.normalizeLines(edit.text.toString())
                    if (replacement.isEmpty()) throw SmartRouteInputException(R.string.smart_route_error_domain_empty)
                    val updated = domainItems.flatMap { if (it == domain) replacement else listOf(it) }.distinct()
                    selectedDomains.remove(domain)
                    setDomains(updated)
                    generatedJson = ""
                } catch (e: SmartRouteInputException) {
                    showError(formatInputError(e))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateOrShow(updateDomainText: Boolean) = try {
        val result = SmartRouteConfigGenerator.generate(
            defaultConf = defaultConf,
            bypassConf = bypassConf,
            domainText = domainItems.joinToString("\n"),
        )
        generatedJson = result.json
        if (updateDomainText) setDomains(result.domains)
        result
    } catch (e: SmartRouteInputException) {
        showError(formatInputError(e))
        null
    } catch (_: Exception) {
        showError(getString(R.string.smart_route_error_invalid_wireguard_conf))
        null
    }

    private fun saveProfile() {
        val result = generateOrShow(updateDomainText = true) ?: return
        val name = profileName.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.smart_route_default_profile_name)
        runOnDefaultDispatcher {
            val groupId = DataStore.selectedGroupForImport()
            if (DataStore.selectedGroup != groupId) DataStore.selectedGroup = groupId
            val bean = ConfigBean().apply {
                this.name = name
                type = "v2ray"
                content = result.json
                serverAddresses = ""
            }
            val profile = ProfileManager.getProfile(managedProfileId)?.apply {
                putBean(bean)
            }?.also {
                ProfileManager.updateProfile(it)
            } ?: ProfileManager.createProfile(groupId, bean)
            managedProfileId = profile.id
            DataStore.selectedProxy = profile.id
            DataStore.selectedGroup = profile.groupId
            smartRoutePrefs().edit()
                .putLong("manager.profileId", profile.id)
                .putString("${profile.id}.name", name)
                .putString("${profile.id}.defaultConf", defaultConf)
                .putString("${profile.id}.bypassConf", bypassConf)
                .putString("${profile.id}.domains", result.domains.joinToString("\n"))
                .putString("${profile.id}.json", result.json)
                .apply()
            onMainDispatcher {
                setupExpanded = false
                showMessage(R.string.smart_route_saved)
                applyResponsiveState()
            }
        }
    }

    private fun loadManagedSmartRoute() {
        val prefs = smartRoutePrefs()
        managedProfileId = prefs.getLong("manager.profileId", 0L)
        if (managedProfileId <= 0L || ProfileManager.getProfile(managedProfileId) == null) {
            managedProfileId = prefs.all.keys
                .mapNotNull { key -> key.substringBefore(".json").toLongOrNull() }
                .firstOrNull { ProfileManager.getProfile(it) != null }
                ?: 0L
        }
        if (managedProfileId <= 0L) return
        prefs.getString("${managedProfileId}.name", null)?.let { profileName.setText(it) }
        defaultConf = prefs.getString("${managedProfileId}.defaultConf", "").orEmpty()
        bypassConf = prefs.getString("${managedProfileId}.bypassConf", "").orEmpty()
        generatedJson = prefs.getString("${managedProfileId}.json", "").orEmpty()
        val savedDomains = prefs.getString("${managedProfileId}.domains", "").orEmpty()
        if (savedDomains.isNotBlank()) {
            setDomains(SmartRouteDomainNormalizer.normalizeLines(savedDomains), resetSelection = true)
        }
    }

    private fun applyResponsiveState() {
        val configured = hasCompleteConf() && managedProfileId > 0L && ProfileManager.getProfile(managedProfileId) != null
        status.setText(if (configured) R.string.smart_route_configured_summary else R.string.smart_route_not_configured)
        defaultConfStatus.setText(if (defaultConf.isBlank()) R.string.smart_route_default_conf_missing else R.string.smart_route_default_conf_ready)
        bypassConfStatus.setText(if (bypassConf.isBlank()) R.string.smart_route_bypass_conf_missing else R.string.smart_route_bypass_conf_ready)

        setupSection.visibility = if (setupExpanded || !hasCompleteConf()) View.VISIBLE else View.GONE
        domainToolsSection.visibility = if (domainToolsExpanded) View.VISIBLE else View.GONE
        jsonToolsSection.visibility = if (jsonToolsExpanded) View.VISIBLE else View.GONE
        defaultConfActions.visibility = if (setupExpanded || defaultConf.isBlank()) View.VISIBLE else View.GONE
        bypassConfActions.visibility = if (setupExpanded || bypassConf.isBlank()) View.VISIBLE else View.GONE

        toggleSetup.setText(if (setupSection.visibility == View.VISIBLE) R.string.smart_route_hide_setup else R.string.smart_route_show_setup)
        toggleDomainTools.setText(if (domainToolsExpanded) R.string.smart_route_hide_domain_tools else R.string.smart_route_show_domain_tools)
        toggleJsonTools.setText(if (jsonToolsExpanded) R.string.smart_route_hide_json_tools else R.string.smart_route_show_json_tools)
    }

    private fun hasCompleteConf() = defaultConf.isNotBlank() && bypassConf.isNotBlank()

    private fun smartRoutePrefs() = requireContext().getSharedPreferences("smart_route_profiles", android.content.Context.MODE_PRIVATE)

    private fun editDomainsAdvanced() {
        editTextDialog(R.string.smart_route_advanced_domain_edit, domainItems.joinToString("\n")) {
            try {
                setDomains(SmartRouteDomainNormalizer.normalizeLines(it), resetSelection = true)
                generatedJson = ""
            } catch (e: SmartRouteInputException) {
                showError(formatInputError(e))
            }
        }
    }

    private fun editTextDialog(titleRes: Int, value: String, onSave: (String) -> Unit) {
        val edit = EditText(requireContext()).apply {
            setText(value)
            minLines = 10
            maxLines = 20
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ -> onSave(edit.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showInfo(title: String, message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showMessage(resId: Int) {
        snackbar(resId).show()
    }

    private fun showMessage(message: String) {
        snackbar(message).show()
    }

    private fun formatInputError(error: SmartRouteInputException): String {
        val base = getString(error.stringRes)
        return error.detail?.let { "$base\n$it" } ?: base
    }

    private fun readText(uri: Uri): String? {
        return runCatching {
            requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.onFailure {
            showError(getString(R.string.action_import_err))
        }.getOrNull()
    }
}
