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

import android.content.Context
import android.content.res.ColorStateList
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.MenuCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.snackbar
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ToolbarFragment
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteConfigGenerator
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteDomainNormalizer
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteGeneratedConfig
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteInputException
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteWireGuardParser
import java.io.OutputStreamWriter

class SmartRouteFragment : ToolbarFragment(R.layout.layout_smart_route_settings),
    Toolbar.OnMenuItemClickListener,
    SmartRouteConfBottomSheet.Listener {

    private enum class ImportTarget {
        DefaultConf,
        BypassConf,
        Domains,
    }

    private var importTarget = ImportTarget.DefaultConf
    private var defaultConf = ""
    private var bypassConf = ""
    private var defaultConfLabel = ""
    private var bypassConfLabel = ""
    private var dnsServer = SmartRouteConfigGenerator.DEFAULT_DNS_SERVER
    private var generatedJson = ""
    private var managedProfileId = 0L
    private var hasUnsavedChanges = false
    private var loadingState = false
    private var profileNameValue = ""
    private var domainFilter = ""
    private var profileSaveInFlight = false
    private var profileSaveQueued = false
    private var profileSaveQueuedSilent = true
    private var profileStateVersion = 0L

    /** Message of the last failed (silent) auto-save, or null when the editor matches the database. */
    private var saveError: String? = null
    private var toolbarInSelectionMode: Boolean? = null

    private val domainItems = ArrayList<String>()
    private val selectedDomains = LinkedHashSet<String>()

    private data class RestoredSmartRouteState(
        val defaultConf: String,
        val bypassConf: String,
        val domains: List<String>,
        val dnsServer: String,
    )

    private data class SaveSnapshot(
        val targetProfileId: Long,
        val stateVersion: Long,
        val profileName: String,
        val defaultConf: String,
        val bypassConf: String,
        val defaultConfLabel: String,
        val bypassConfLabel: String,
        val dnsServer: String,
        val domains: List<String>,
        val json: String,
    )

    private data class SaveResult(
        val profileId: Long,
        val groupId: Long,
        val contentChanged: Boolean,
    )

    private data class ProfileChoice(
        val profile: ProxyEntity,
        val domainCount: Int,
    )

    private lateinit var domainInput: EditText
    private lateinit var domainPreview: TextView
    private lateinit var domainAdd: View
    private lateinit var domainListView: RecyclerView
    private lateinit var filterBar: TextView
    private lateinit var emptyState: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptySummary: TextView
    private lateinit var emptyAction: Button
    private lateinit var status: TextView
    private lateinit var statusRow: View
    private lateinit var statusDot: View
    private lateinit var actionButton: Button
    private lateinit var settingsButton: View
    private lateinit var defaultValue: TextView
    private lateinit var bypassValue: TextView
    private lateinit var proxyStatus: TextView
    private lateinit var profileSelector: TextView
    private lateinit var domainAdapter: DomainAdapter

    private val selectionBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = clearSelection()
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val text = readText(uri) ?: return@registerForActivityResult
        when (importTarget) {
            ImportTarget.DefaultConf -> setConf(
                default = true,
                text = text,
                label = displayName(uri) ?: getString(R.string.smart_route_conf_source_file),
            )
            ImportTarget.BypassConf -> setConf(
                default = false,
                text = text,
                label = displayName(uri) ?: getString(R.string.smart_route_conf_source_file),
            )
            ImportTarget.Domains -> importDomains(text)
        }
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
        toolbar.setOnMenuItemClickListener(this)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, selectionBackCallback)

        bindViews(view)
        setupDomainList()
        loadingState = true
        profileNameValue = getString(R.string.smart_route_default_profile_name)
        setDomains(emptyList(), resetSelection = true)
        loadManagedSmartRoute()
        loadingState = false
        hasUnsavedChanges = false

        wireActions()
        updateDomainPreview()
        applyResponsiveState()
        if (!hasCompleteConf()) showConfSheet()
    }

    override fun onResume() {
        super.onResume()
        if (!SagerNet.started) clearPendingApply(requireContext())
        syncSelectedConfigurationProfile()
        applyResponsiveState()
    }

    /** Called by [MainActivity] whenever the proxy service changes state. */
    fun onServiceStateChanged() {
        if (view == null) return
        applyResponsiveState()
    }

    private fun bindViews(view: View) {
        domainInput = view.findViewById(R.id.domain_input)
        domainPreview = view.findViewById(R.id.domain_preview)
        domainAdd = view.findViewById(R.id.domain_add)
        domainListView = view.findViewById(R.id.domain_list)
        filterBar = view.findViewById(R.id.domain_filter_bar)
        emptyState = view.findViewById(R.id.empty_state)
        emptyTitle = emptyState.findViewById(R.id.empty_title)
        emptySummary = emptyState.findViewById(R.id.empty_summary)
        emptyAction = emptyState.findViewById(R.id.empty_action)
        status = view.findViewById(R.id.smart_route_status)
        statusRow = view.findViewById(R.id.smart_route_status_row)
        statusDot = view.findViewById(R.id.smart_route_status_dot)
        actionButton = view.findViewById(R.id.smart_route_action)
        settingsButton = view.findViewById(R.id.smart_route_settings_button)
        defaultValue = view.findViewById(R.id.smart_route_default_value)
        bypassValue = view.findViewById(R.id.smart_route_bypass_value)
        proxyStatus = view.findViewById(R.id.smart_route_proxy_status)
        profileSelector = view.findViewById(R.id.smart_route_profile_selector)
    }

    private fun setupDomainList() {
        domainAdapter = DomainAdapter(
            onClick = { domain ->
                if (selectedDomains.isEmpty()) editDomain(domain) else toggleSelected(domain)
            },
            onLongClick = { domain ->
                toggleSelected(domain)
                true
            },
            onDelete = { domain ->
                removeDomain(domain)
            },
        )
        domainListView.layoutManager = LinearLayoutManager(requireContext())
        domainListView.adapter = domainAdapter
    }

    private fun wireActions() {
        domainAdd.setOnClickListener { addDomainsFromInput() }
        domainInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                addDomainsFromInput()
                true
            } else {
                false
            }
        }
        domainInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updateDomainPreview()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        profileSelector.setOnClickListener { showProfilePicker() }
        settingsButton.setOnClickListener { showConfSheet() }
        requireView().findViewById<View>(R.id.smart_route_default_row).setOnClickListener { showConfSheet() }
        requireView().findViewById<View>(R.id.smart_route_bypass_row).setOnClickListener { showConfSheet() }
        requireView().findViewById<View>(R.id.smart_route_proxy_row).setOnClickListener { openProxySettings() }
        statusRow.setOnClickListener { onStatusClicked() }
        actionButton.setOnClickListener { onActionClicked() }
        filterBar.setOnClickListener {
            domainFilter = ""
            renderDomainList()
        }
        emptyAction.setOnClickListener { showConfSheet() }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_smart_route_select_profile -> showProfilePicker()
            R.id.action_smart_route_new_profile -> startNewProfile()
            R.id.action_smart_route_duplicate_profile -> duplicateCurrentProfile()
            R.id.action_smart_route_rename_profile -> renameCurrentProfile()
            R.id.action_smart_route_delete_profile -> deleteCurrentProfile()
            R.id.action_smart_route_setup -> showConfSheet()
            R.id.action_smart_route_search -> showSearchDialog()
            R.id.action_smart_route_select_visible -> selectVisible()
            R.id.action_smart_route_delete_selected -> removeSelectedDomains()
            R.id.action_smart_route_import -> {
                importTarget = ImportTarget.Domains
                startFilesForResult(importFile, "text/*")
            }
            R.id.action_smart_route_export -> exportDomains.launch("smart-route-domains.txt")
            R.id.action_smart_route_advanced_edit -> editDomainsAdvanced()
            R.id.action_smart_route_normalize -> normalizeDomains(sort = false)
            R.id.action_smart_route_sort -> normalizeDomains(sort = true)
            R.id.action_smart_route_validate -> {
                generateOrShow(updateDomainText = true)?.let { result ->
                    val warning = result.warnings.distinct().joinToString("\n") { getString(it) }
                    showInfo(
                        getString(R.string.smart_route_validate),
                        listOf(getString(R.string.smart_route_validation_ok), warning).filter { it.isNotBlank() }.joinToString("\n\n"),
                    )
                }
            }
            R.id.action_smart_route_preview_json -> {
                generateOrShow(updateDomainText = true)?.let {
                    showJsonPreview(it.json)
                }
            }
            R.id.action_smart_route_copy_json -> {
                val json = generateOrShow(updateDomainText = true)?.json ?: return true
                SagerNet.trySetPrimaryClip(json)
                showMessage(R.string.smart_route_json_copied)
            }
            R.id.action_smart_route_proxy_settings -> openProxySettings()
            R.id.action_smart_route_clear_all -> confirmClearAllDomains()
            R.id.action_smart_route_advanced -> return false
            else -> return false
        }
        return true
    }

    private fun openProxySettings() {
        (requireActivity() as MainActivity).displayFragmentWithId(R.id.nav_settings)
    }

    // ---- Route conf sheet ------------------------------------------------------------------

    override fun onSmartRouteConfDone(profileName: String, dnsServer: String): Boolean {
        applySheetFields(profileName, dnsServer)
        return if (hasCompleteConf()) {
            true
        } else {
            showMessage(R.string.smart_route_save_state_missing_setup)
            false
        }
    }

    override fun onSmartRouteConfDismissed(profileName: String, dnsServer: String) {
        applySheetFields(profileName, dnsServer)
    }

    private fun applySheetFields(profileName: String, dnsServer: String) {
        val newName = profileName.trim().ifBlank { getString(R.string.smart_route_default_profile_name) }
        val newDns = dnsServer.trim().ifBlank { SmartRouteConfigGenerator.DEFAULT_DNS_SERVER }
        if (newName == profileNameValue && newDns == this.dnsServer) return
        profileNameValue = newName
        this.dnsServer = newDns
        markChanged()
    }

    override fun onSmartRouteConfFile(default: Boolean) {
        importTarget = if (default) ImportTarget.DefaultConf else ImportTarget.BypassConf
        startFilesForResult(importFile, "*/*")
    }

    override fun onSmartRouteConfClipboard(default: Boolean) {
        val text = SagerNet.getClipboardText()
        if (text.isEmpty()) {
            showMessage(R.string.clipboard_empty)
            return
        }
        setConf(default, text, getString(R.string.smart_route_conf_source_clipboard))
    }

    override fun onSmartRouteConfEdit(default: Boolean) {
        editConf(default, if (default) defaultConf else bypassConf)
    }

    private fun editConf(default: Boolean, value: String) {
        editTextDialog(
            if (default) R.string.smart_route_default_conf else R.string.smart_route_bypass_conf,
            value,
        ) { text ->
            if (text.isBlank()) {
                onSmartRouteConfClear(default)
                return@editTextDialog
            }
            val error = validateConf(text)
            if (error != null) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.error_title)
                    .setMessage(error + "\n\n" + getString(R.string.smart_route_conf_not_loaded))
                    .setPositiveButton(R.string.smart_route_invalid_lines_edit_again) { _, _ -> editConf(default, text) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                return@editTextDialog
            }
            setConf(default, text, getString(R.string.smart_route_conf_source_manual))
        }
    }

    /** Validates a WireGuard conf up-front so a broken conf never reaches the (silent) auto-save. */
    private fun setConf(default: Boolean, text: String, label: String) {
        val error = validateConf(text)
        if (error != null) {
            showError(error + "\n\n" + getString(R.string.smart_route_conf_not_loaded))
            return
        }
        if (default) {
            defaultConf = text
            defaultConfLabel = label
        } else {
            bypassConf = text
            bypassConfLabel = label
        }
        generatedJson = ""
        markChanged()
        refreshConfSheet()
        showMessage(if (default) R.string.smart_route_default_loaded else R.string.smart_route_bypass_loaded)
    }

    private fun validateConf(text: String): String? = try {
        SmartRouteWireGuardParser.parse(text)
        null
    } catch (e: SmartRouteInputException) {
        formatInputError(e)
    } catch (_: Exception) {
        getString(R.string.smart_route_error_invalid_wireguard_conf)
    }

    override fun onSmartRouteConfClear(default: Boolean) {
        val previousConf = if (default) defaultConf else bypassConf
        val previousLabel = if (default) defaultConfLabel else bypassConfLabel
        if (previousConf.isBlank()) return
        fun apply(conf: String, label: String) {
            if (default) {
                defaultConf = conf
                defaultConfLabel = label
            } else {
                bypassConf = conf
                bypassConfLabel = label
            }
            generatedJson = ""
            markChanged()
            refreshConfSheet()
        }
        apply("", "")
        snackbar(if (default) R.string.smart_route_default_cleared else R.string.smart_route_bypass_cleared)
            .setAction(R.string.smart_route_undo) { apply(previousConf, previousLabel) }
            .show()
    }

    private fun showConfSheet() {
        if (childFragmentManager.findFragmentByTag("smart_route_conf") != null) return
        SmartRouteConfBottomSheet.newInstance(
            profileName = profileNameValue,
            dnsServer = dnsServer,
            defaultReady = defaultConf.isNotBlank(),
            bypassReady = bypassConf.isNotBlank(),
            defaultLabel = defaultConfLabel,
            bypassLabel = bypassConfLabel,
        ).show(childFragmentManager, "smart_route_conf")
    }

    private fun refreshConfSheet() {
        (childFragmentManager.findFragmentByTag("smart_route_conf") as? SmartRouteConfBottomSheet)
            ?.updateConfStatus(
                defaultReady = defaultConf.isNotBlank(),
                bypassReady = bypassConf.isNotBlank(),
                defaultLabel = defaultConfLabel,
                bypassLabel = bypassConfLabel,
            )
    }

    // ---- Domains ---------------------------------------------------------------------------

    private fun showSearchDialog() {
        val edit = EditText(requireContext()).apply {
            setText(domainFilter)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.smart_route_search_title)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                domainFilter = edit.text.toString().trim()
                renderDomainList()
            }
            .setNeutralButton(R.string.smart_route_clear) { _, _ ->
                domainFilter = ""
                renderDomainList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun normalizeDomains(sort: Boolean) {
        try {
            val normalized = SmartRouteDomainNormalizer.normalizeLines(
                domainItems.joinToString("\n"),
            ).let { if (sort) it.sorted() else it }
            setDomains(normalized)
            generatedJson = ""
            markChanged()
        } catch (e: SmartRouteInputException) {
            showError(formatInputError(e))
        }
    }

    private fun addDomainsFromInput() {
        val parsed = SmartRouteDomainNormalizer.parseLenient(domainInput.text?.toString().orEmpty())
        if (parsed.domains.isEmpty()) {
            if (parsed.invalid.isEmpty()) {
                showError(getString(R.string.smart_route_error_domain_empty))
            } else {
                showError(getString(R.string.smart_route_error_invalid_domain) + "\n" + parsed.invalid.joinToString("\n"))
            }
            return
        }
        val current = domainItems.toList()
        val merged = (current + parsed.domains).distinct()
        val added = merged.size - current.size
        setDomains(merged)
        // Keep the unrecognized entries in the input so they can be fixed instead of silently lost.
        domainInput.setText(parsed.invalid.joinToString(" "))
        domainInput.setSelection(domainInput.text?.length ?: 0)
        if (added > 0 && domainFilter.isNotBlank()) {
            domainFilter = ""
            renderDomainList()
        }
        generatedJson = ""
        markChanged()
        val skipped = parsed.domains.size - added
        showMessage(
            when {
                parsed.invalid.isNotEmpty() -> getString(R.string.smart_route_domains_added_with_invalid, added, parsed.invalid.size)
                added == 0 -> getString(R.string.smart_route_domains_already_exists)
                skipped > 0 -> getString(R.string.smart_route_domains_added_with_duplicates, added, skipped)
                else -> getString(R.string.smart_route_domains_added, added)
            },
        )
    }

    private fun importDomains(text: String) {
        val parsed = SmartRouteDomainNormalizer.parseLenient(text)
        if (parsed.domains.isEmpty()) {
            showError(
                if (parsed.invalid.isEmpty()) {
                    getString(R.string.smart_route_error_domain_empty)
                } else {
                    getString(R.string.smart_route_error_invalid_domain) + "\n" + parsed.invalid.take(20).joinToString("\n")
                },
            )
            return
        }
        val before = domainItems.toList()
        val merged = (before + parsed.domains).distinct()
        replaceDomainsWithUndo(
            merged,
            getString(R.string.smart_route_domains_imported, merged.size - before.size, parsed.invalid.size),
        )
    }

    /** Applies a domain list change and offers an undo snackbar restoring the previous list. */
    private fun replaceDomainsWithUndo(newDomains: List<String>, message: String) {
        val before = domainItems.toList()
        val selectionBefore = selectedDomains.toList()
        setDomains(newDomains)
        generatedJson = ""
        markChanged()
        snackbar(message)
            .setAction(R.string.smart_route_undo) {
                setDomains(before, resetSelection = true)
                selectedDomains.addAll(selectionBefore.filter { it in before })
                generatedJson = ""
                markChanged()
                renderDomainList()
            }
            .show()
    }

    private fun toggleSelected(domain: String) {
        if (domain in selectedDomains) selectedDomains.remove(domain) else selectedDomains.add(domain)
        renderDomainList()
    }

    private fun selectVisible() {
        selectedDomains.addAll(filteredDomains())
        renderDomainList()
    }

    private fun clearSelection() {
        if (selectedDomains.isEmpty()) return
        selectedDomains.clear()
        renderDomainList()
    }

    private fun removeSelectedDomains() {
        if (selectedDomains.isEmpty()) {
            showMessage(R.string.smart_route_no_selected_domains)
            return
        }
        val removed = selectedDomains.toSet()
        selectedDomains.clear()
        replaceDomainsWithUndo(
            domainItems.filterNot { it in removed },
            getString(R.string.smart_route_domains_removed, removed.size),
        )
    }

    private fun removeDomain(domain: String) {
        if (domain !in domainItems) return
        selectedDomains.remove(domain)
        replaceDomainsWithUndo(
            domainItems.filterNot { it == domain },
            getString(R.string.smart_route_domain_removed, domain),
        )
    }

    private fun confirmClearAllDomains() {
        if (domainItems.isEmpty()) {
            showMessage(R.string.smart_route_no_domains)
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.smart_route_clear_all_title)
            .setMessage(getString(R.string.smart_route_clear_all_message, domainItems.size))
            .setPositiveButton(R.string.smart_route_delete_profile_confirm) { _, _ ->
                val count = domainItems.size
                selectedDomains.clear()
                replaceDomainsWithUndo(emptyList(), getString(R.string.smart_route_domains_removed, count))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setDomains(values: List<String>, resetSelection: Boolean = false) {
        domainItems.clear()
        domainItems.addAll(values.distinct())
        if (resetSelection) selectedDomains.clear() else selectedDomains.retainAll(domainItems.toSet())
        renderDomainList()
    }

    private fun renderDomainList() {
        if (!::domainAdapter.isInitialized) return
        val visibleDomains = filteredDomains()
        val selectionMode = selectedDomains.isNotEmpty()
        domainAdapter.submitList(visibleDomains.map { DomainRow(it, it in selectedDomains, selectionMode) })

        filterBar.visibility = if (domainFilter.isBlank()) View.GONE else View.VISIBLE
        if (domainFilter.isNotBlank()) {
            filterBar.text = getString(R.string.smart_route_filter_bar, domainFilter, visibleDomains.size, domainItems.size)
        }

        emptyState.visibility = if (visibleDomains.isEmpty()) View.VISIBLE else View.GONE
        emptyAction.visibility = View.GONE
        when {
            domainItems.isNotEmpty() -> {
                emptyTitle.setText(R.string.smart_route_search_empty_title)
                emptySummary.setText(R.string.smart_route_search_empty_summary)
            }
            !hasCompleteConf() -> {
                emptyTitle.setText(R.string.smart_route_empty_title)
                emptySummary.setText(R.string.smart_route_empty_summary_needs_conf)
                emptyAction.visibility = View.VISIBLE
            }
            else -> {
                emptyTitle.setText(R.string.smart_route_empty_title)
                emptySummary.setText(R.string.smart_route_empty_summary)
            }
        }
        applyResponsiveState()
    }

    private fun filteredDomains(): List<String> {
        return if (domainFilter.isBlank()) {
            domainItems.toList()
        } else {
            domainItems.filter { it.contains(domainFilter, ignoreCase = true) }
        }
    }

    private fun editDomain(domain: String, initial: String = domain) {
        val edit = EditText(requireContext()).apply {
            setText(initial)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.smart_route_edit_domain)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = edit.text.toString()
                val parsed = SmartRouteDomainNormalizer.parseLenient(text)
                if (parsed.invalid.isNotEmpty() || parsed.domains.isEmpty()) {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.error_title)
                        .setMessage(
                            if (parsed.invalid.isEmpty()) {
                                getString(R.string.smart_route_error_domain_empty)
                            } else {
                                getString(R.string.smart_route_error_invalid_domain) + "\n" + parsed.invalid.joinToString("\n")
                            },
                        )
                        .setPositiveButton(R.string.smart_route_invalid_lines_edit_again) { _, _ -> editDomain(domain, text) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    return@setPositiveButton
                }
                val updated = domainItems.flatMap { if (it == domain) parsed.domains else listOf(it) }.distinct()
                selectedDomains.remove(domain)
                setDomains(updated)
                generatedJson = ""
                markChanged()
            }
            .setNeutralButton(R.string.smart_route_domain_delete_inline) { _, _ -> removeDomain(domain) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun editDomainsAdvanced(initial: String = domainItems.joinToString("\n")) {
        editTextDialog(R.string.smart_route_advanced_domain_edit, initial) { text ->
            val parsed = SmartRouteDomainNormalizer.parseLenient(text)
            if (parsed.invalid.isEmpty()) {
                setDomains(parsed.domains, resetSelection = true)
                generatedJson = ""
                markChanged()
                return@editTextDialog
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.smart_route_invalid_lines_title)
                .setMessage(
                    getString(
                        R.string.smart_route_invalid_lines_message,
                        parsed.invalid.size,
                        parsed.invalid.take(20).joinToString("\n"),
                    ),
                )
                .setPositiveButton(R.string.smart_route_invalid_lines_edit_again) { _, _ -> editDomainsAdvanced(text) }
                .setNeutralButton(R.string.smart_route_invalid_lines_keep_valid) { _, _ ->
                    setDomains(parsed.domains, resetSelection = true)
                    generatedJson = ""
                    markChanged()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun updateDomainPreview() {
        val text = domainInput.text?.toString().orEmpty()
        if (text.isBlank()) {
            domainPreview.setText(R.string.smart_route_domain_preview_empty)
            return
        }
        val parsed = SmartRouteDomainNormalizer.parseLenient(text)
        domainPreview.text = when {
            parsed.domains.isEmpty() && parsed.invalid.isEmpty() -> getString(R.string.smart_route_domain_preview_empty)
            parsed.domains.isEmpty() -> getString(R.string.smart_route_domain_preview_invalid, parsed.invalid.first())
            parsed.invalid.isNotEmpty() -> getString(
                R.string.smart_route_domain_preview_mixed,
                parsed.domains.size,
                parsed.invalid.size,
                parsed.invalid.first(),
            )
            parsed.domains.size == 1 -> getString(R.string.smart_route_domain_preview_one, parsed.domains.first())
            else -> getString(R.string.smart_route_domain_preview_many, parsed.domains.size, parsed.domains.first())
        }
    }

    // ---- Generate / save -------------------------------------------------------------------

    private fun generateOrShow(updateDomainText: Boolean): SmartRouteGeneratedConfig? {
        return when (val outcome = generateSmartRoute(updateDomainText)) {
            is GenerateOutcome.Success -> outcome.config
            is GenerateOutcome.Failure -> {
                showError(outcome.message)
                null
            }
        }
    }

    private sealed class GenerateOutcome {
        class Success(val config: SmartRouteGeneratedConfig) : GenerateOutcome()
        class Failure(val message: String) : GenerateOutcome()
    }

    private fun generateSmartRoute(updateDomainText: Boolean): GenerateOutcome = try {
        val result = SmartRouteConfigGenerator.generate(
            defaultConf = defaultConf,
            bypassConf = bypassConf,
            domainText = domainItems.joinToString("\n"),
            dnsServer = dnsServer,
        )
        generatedJson = result.json
        if (updateDomainText) setDomains(result.domains)
        GenerateOutcome.Success(result)
    } catch (e: SmartRouteInputException) {
        GenerateOutcome.Failure(formatInputError(e))
    } catch (_: Exception) {
        GenerateOutcome.Failure(getString(R.string.smart_route_error_invalid_wireguard_conf))
    }

    /**
     * Saves the editor into its Configuration profile.
     *
     * Silent saves (auto-save while editing) only persist; they never change the profile in use
     * and never reload the service. Explicit saves select the profile and apply it to a running
     * connection.
     */
    private fun saveProfile(silent: Boolean) {
        if (profileSaveInFlight) {
            profileSaveQueued = true
            profileSaveQueuedSilent = profileSaveQueuedSilent && silent
            return
        }
        profileSaveQueuedSilent = true
        ensureSmartRouteRuntimeDefaults()
        val result = when (val outcome = generateSmartRoute(updateDomainText = true)) {
            is GenerateOutcome.Success -> outcome.config
            is GenerateOutcome.Failure -> {
                saveError = outcome.message
                if (!silent) showError(outcome.message)
                applyResponsiveState()
                return
            }
        }
        val snapshot = SaveSnapshot(
            targetProfileId = managedProfileId,
            stateVersion = profileStateVersion,
            profileName = profileNameValue,
            defaultConf = defaultConf,
            bypassConf = bypassConf,
            defaultConfLabel = defaultConfLabel,
            bypassConfLabel = bypassConfLabel,
            dnsServer = dnsServer,
            domains = result.domains,
            json = result.json,
        )
        val prefs = smartRoutePrefs()
        val smartRouteGroupName = getString(R.string.smart_route_group_name)
        val saveFailedMessage = getString(R.string.smart_route_save_failed)
        profileSaveInFlight = true
        runOnDefaultDispatcher {
            val saved = runCatching {
                val group = ensureSmartRouteGroup(prefs, smartRouteGroupName)
                val groupId = group.id
                val bean = ConfigBean().apply {
                    name = snapshot.profileName
                    type = "v2ray"
                    content = snapshot.json
                    serverAddresses = ""
                }
                val existingProfile = snapshot.targetProfileId
                    .takeIf { it > 0L }
                    ?.let { ProfileManager.getProfile(it) }
                val previousContent = existingProfile?.configBean?.content
                val profile = if (existingProfile != null) {
                    val oldGroupId = existingProfile.groupId
                    existingProfile.apply {
                        this.groupId = groupId
                        if (oldGroupId != groupId) {
                            userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
                        }
                        putBean(bean)
                    }.also {
                        ProfileManager.updateProfile(it)
                        if (oldGroupId != groupId) GroupManager.postReload(oldGroupId)
                    }
                } else {
                    ProfileManager.createProfile(groupId, bean)
                }
                GroupManager.postUpdate(group)
                val savedProfile = checkNotNull(ProfileManager.getProfile(profile.id)) {
                    "Smart Route profile was not created."
                }
                check(savedProfile.groupId == groupId) { "Smart Route profile was saved in the wrong group." }
                checkNotNull(SagerDatabase.groupDao.getById(groupId)) { "Smart Route group was not created." }
                prefs.edit()
                    .putLong("manager.groupId", groupId)
                    .putString("${profile.id}.name", snapshot.profileName)
                    .putString("${profile.id}.defaultConf", snapshot.defaultConf)
                    .putString("${profile.id}.bypassConf", snapshot.bypassConf)
                    .putString("${profile.id}.defaultConfLabel", snapshot.defaultConfLabel)
                    .putString("${profile.id}.bypassConfLabel", snapshot.bypassConfLabel)
                    .putString("${profile.id}.dnsServer", snapshot.dnsServer)
                    .putString("${profile.id}.domains", snapshot.domains.joinToString("\n"))
                    .putString("${profile.id}.json", snapshot.json)
                    .commit()
                SaveResult(profile.id, groupId, previousContent != snapshot.json)
            }
            onMainDispatcher {
                profileSaveInFlight = false
                saved.onFailure {
                    saveError = it.message?.let { detail -> "$saveFailedMessage\n$detail" } ?: saveFailedMessage
                    if (!silent) showError(saveFailedMessage)
                    if (view != null) applyResponsiveState()
                }.onSuccess { saveResult ->
                    if (snapshot.stateVersion != profileStateVersion) {
                        return@onSuccess
                    }
                    managedProfileId = saveResult.profileId
                    saveError = null
                    prefs.edit()
                        .putLong("manager.groupId", saveResult.groupId)
                        .putLong("manager.activeProfileId", saveResult.profileId)
                        .putLong("manager.profileId", saveResult.profileId)
                        .remove("draft.name")
                        .remove("draft.defaultConf")
                        .remove("draft.bypassConf")
                        .remove("draft.defaultConfLabel")
                        .remove("draft.bypassConfLabel")
                        .remove("draft.dnsServer")
                        .remove("draft.domains")
                        .remove("draft.json")
                        .commit()
                    hasUnsavedChanges = false
                    if (silent) {
                        if (saveResult.contentChanged && isRunningProfile(saveResult.profileId)) {
                            prefs.edit().putBoolean(pendingApplyKey(saveResult.profileId), true).commit()
                        }
                    } else {
                        DataStore.selectedProxy = saveResult.profileId
                        DataStore.selectedGroup = saveResult.groupId
                        markSelectedProxySeen()
                        clearPendingApply(requireContext())
                        if (SagerNet.started) {
                            SagerNet.reloadService()
                            showMessage(R.string.smart_route_saved_reloaded)
                        } else {
                            showMessage(R.string.smart_route_saved)
                        }
                    }
                    if (view != null) applyResponsiveState()
                }
                if (profileSaveQueued) {
                    val queuedSilent = profileSaveQueuedSilent
                    profileSaveQueued = false
                    profileSaveQueuedSilent = true
                    if (hasCompleteConf()) saveProfile(silent = queuedSilent)
                }
            }
        }
    }

    private fun onActionClicked() {
        if (!hasCompleteConf()) showConfSheet() else saveProfile(silent = false)
    }

    private fun onStatusClicked() {
        val error = saveError
        when {
            !hasCompleteConf() -> showConfSheet()
            error != null -> MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.smart_route_save_failed_title)
                .setMessage(error)
                .setPositiveButton(R.string.smart_route_button_retry_save) { _, _ -> saveProfile(silent = false) }
                .setNeutralButton(R.string.smart_route_menu_connection) { _, _ -> showConfSheet() }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            currentHeaderState().actionRes != null -> onActionClicked()
        }
    }

    // ---- Loading / restoring ---------------------------------------------------------------

    private fun loadManagedSmartRoute() {
        val prefs = smartRoutePrefs()
        val storedActiveProfileId = prefs.getLong("manager.activeProfileId", 0L)
        if (storedActiveProfileId <= 0L && loadStoredSmartRouteState("draft")) {
            markSelectedProxySeen()
            return
        }

        // Follow the profile picked in Configuration only when it changed since we last looked;
        // otherwise reopen the profile that was being edited.
        managedProfileId = selectedProxyChangedSinceSeen()
            ?: storedActiveProfileId.takeIf { it > 0L && ProfileManager.getProfile(it) != null }
            ?: DataStore.selectedProxy.takeIf { it > 0L && isSmartRouteProfile(it) }
            ?: prefs.getLong("manager.profileId", 0L)
        markSelectedProxySeen()
        if (managedProfileId <= 0L || ProfileManager.getProfile(managedProfileId) == null) {
            managedProfileId = prefs.all.keys
                .mapNotNull { key -> key.substringBefore(".json").toLongOrNull() }
                .firstOrNull { ProfileManager.getProfile(it) != null }
                ?: 0L
        }
        if (managedProfileId <= 0L) {
            if (loadStoredSmartRouteState("draft")) return
            loadRestoredSmartRouteProfile()
            return
        }
        val managedProfile = ProfileManager.getProfile(managedProfileId)
        profileNameValue = managedProfile?.displayName()?.takeIf { it.isNotBlank() }
            ?: prefs.getString("${managedProfileId}.name", null)
            ?: getString(R.string.smart_route_default_profile_name)
        if (managedProfile != null && loadProfileContentState(managedProfile)) return
        defaultConf = prefs.getString("${managedProfileId}.defaultConf", "").orEmpty()
        bypassConf = prefs.getString("${managedProfileId}.bypassConf", "").orEmpty()
        defaultConfLabel = prefs.getString("${managedProfileId}.defaultConfLabel", "").orEmpty()
            .ifBlank { if (defaultConf.isNotBlank()) getString(R.string.smart_route_conf_source_saved) else "" }
        bypassConfLabel = prefs.getString("${managedProfileId}.bypassConfLabel", "").orEmpty()
            .ifBlank { if (bypassConf.isNotBlank()) getString(R.string.smart_route_conf_source_saved) else "" }
        dnsServer = prefs.getString("${managedProfileId}.dnsServer", SmartRouteConfigGenerator.DEFAULT_DNS_SERVER)
            ?.takeIf { it.isNotBlank() }
            ?: SmartRouteConfigGenerator.DEFAULT_DNS_SERVER
        generatedJson = prefs.getString("${managedProfileId}.json", "").orEmpty()
        val savedDomains = prefs.getString("${managedProfileId}.domains", "").orEmpty()
        if (savedDomains.isNotBlank()) {
            setDomains(SmartRouteDomainNormalizer.parseLenient(savedDomains).domains, resetSelection = true)
        }
        if (!hasCompleteConf() || domainItems.isEmpty()) {
            extractSmartRouteState(managedProfile?.configBean?.content.orEmpty())?.let {
                applyRestoredSmartRouteState(it, generatedJson.ifBlank { managedProfile?.configBean?.content.orEmpty() })
                persistSmartRouteState()
            }
        }
    }

    private fun selectedProxyChangedSinceSeen(): Long? {
        val selected = DataStore.selectedProxy
        val seen = smartRoutePrefs().getLong("manager.lastSeenSelectedProxy", 0L)
        return selected.takeIf { it > 0L && it != seen && isSmartRouteProfile(it) }
    }

    private fun markSelectedProxySeen() {
        smartRoutePrefs().edit().putLong("manager.lastSeenSelectedProxy", DataStore.selectedProxy).commit()
    }

    private fun syncSelectedConfigurationProfile() {
        val selectedProfileId = selectedProxyChangedSinceSeen()
        markSelectedProxySeen()
        if (selectedProfileId == null || selectedProfileId == managedProfileId) return
        if (hasUnsavedDraft() || saveError != null) return
        val profile = ProfileManager.getProfile(selectedProfileId) ?: return
        loadProfileIntoEditor(profile)
    }

    private fun loadProfileContentState(profile: ProxyEntity): Boolean {
        val content = profile.configBean?.content.orEmpty()
        val restored = extractSmartRouteState(content) ?: return false
        applyRestoredSmartRouteState(restored, content)
        val prefs = smartRoutePrefs()
        defaultConfLabel = prefs.getString("${profile.id}.defaultConfLabel", "").orEmpty()
            .ifBlank { if (defaultConf.isNotBlank()) getString(R.string.smart_route_conf_source_saved) else "" }
        bypassConfLabel = prefs.getString("${profile.id}.bypassConfLabel", "").orEmpty()
            .ifBlank { if (bypassConf.isNotBlank()) getString(R.string.smart_route_conf_source_saved) else "" }
        persistSmartRouteState()
        return true
    }

    private fun loadRestoredSmartRouteProfile(): Boolean {
        val profile = SagerDatabase.proxyDao.getAll()
            .asSequence()
            .filter { it.type == ProxyEntity.TYPE_CONFIG }
            .mapNotNull { profile ->
                extractSmartRouteState(profile.configBean?.content.orEmpty())?.let { profile to it }
            }
            .firstOrNull()
            ?: return false
        val bean = profile.first.configBean ?: return false
        val restored = profile.second

        managedProfileId = profile.first.id
        profileNameValue = bean.displayName().ifBlank { getString(R.string.smart_route_default_profile_name) }
        applyRestoredSmartRouteState(restored, bean.content.orEmpty())
        persistSmartRouteState()
        return true
    }

    private fun loadStoredSmartRouteState(prefix: String, loadName: Boolean = true): Boolean {
        val prefs = smartRoutePrefs()
        val storedDefaultConf = prefs.getString("$prefix.defaultConf", "").orEmpty()
        val storedBypassConf = prefs.getString("$prefix.bypassConf", "").orEmpty()
        val storedDomains = prefs.getString("$prefix.domains", "").orEmpty()
        if (storedDefaultConf.isBlank() && storedBypassConf.isBlank() && storedDomains.isBlank()) return false

        if (loadName) {
            profileNameValue = prefs.getString("$prefix.name", null)
                ?: getString(R.string.smart_route_default_profile_name)
        }
        defaultConf = storedDefaultConf
        bypassConf = storedBypassConf
        defaultConfLabel = prefs.getString("$prefix.defaultConfLabel", "").orEmpty()
            .ifBlank { if (defaultConf.isNotBlank()) getString(R.string.smart_route_conf_source_saved) else "" }
        bypassConfLabel = prefs.getString("$prefix.bypassConfLabel", "").orEmpty()
            .ifBlank { if (bypassConf.isNotBlank()) getString(R.string.smart_route_conf_source_saved) else "" }
        dnsServer = prefs.getString("$prefix.dnsServer", SmartRouteConfigGenerator.DEFAULT_DNS_SERVER)
            ?.takeIf { it.isNotBlank() }
            ?: SmartRouteConfigGenerator.DEFAULT_DNS_SERVER
        generatedJson = prefs.getString("$prefix.json", "").orEmpty()
        setDomains(
            if (storedDomains.isBlank()) emptyList() else SmartRouteDomainNormalizer.parseLenient(storedDomains).domains,
            resetSelection = true,
        )
        return true
    }

    private fun applyRestoredSmartRouteState(restored: RestoredSmartRouteState, json: String) {
        generatedJson = json
        defaultConf = restored.defaultConf
        bypassConf = restored.bypassConf
        defaultConfLabel = getString(R.string.smart_route_conf_source_saved)
        bypassConfLabel = getString(R.string.smart_route_conf_source_saved)
        dnsServer = restored.dnsServer
        setDomains(restored.domains, resetSelection = true)
    }

    private fun extractSmartRouteState(json: String): RestoredSmartRouteState? {
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            val outbounds = root.getAsJsonArray("outbounds") ?: return null
            val defaultOutbound = outbounds.mapNotNull { it.asJsonObject }
                .firstOrNull { it.get("tag")?.asString == "smart-default" }
                ?: return null
            val bypassOutbound = outbounds.mapNotNull { it.asJsonObject }
                .firstOrNull { it.get("tag")?.asString == "smart-bypass" }
                ?: return null

            val rules = root.getAsJsonObject("routing")?.getAsJsonArray("rules") ?: return null
            val domains = rules.asSequence()
                .mapNotNull { it.asJsonObject }
                .firstOrNull { it.get("outboundTag")?.asString == "smart-bypass" }
                ?.getAsJsonArray("domain")
                ?.mapNotNull { it.takeIf { element -> element.isJsonPrimitive }?.asString }
                ?.let { SmartRouteDomainNormalizer.normalizeLines(it.joinToString("\n")) }
                ?: emptyList()

            RestoredSmartRouteState(
                defaultConf = restoreWireGuardConf(defaultOutbound) ?: return null,
                bypassConf = restoreWireGuardConf(bypassOutbound) ?: return null,
                domains = domains,
                dnsServer = root.getAsJsonObject("dns")
                    ?.getAsJsonArray("servers")
                    ?.firstOrNull()
                    ?.asJsonObject
                    ?.get("address")
                    ?.asString
                    ?.takeIf { it.isNotBlank() }
                    ?: SmartRouteConfigGenerator.DEFAULT_DNS_SERVER,
            )
        }.getOrNull()
    }

    private fun restoreWireGuardConf(outbound: JsonObject): String? {
        if (outbound.get("protocol")?.asString != "wireguard") return null
        val settings = outbound.getAsJsonObject("settings") ?: return null
        val peer = settings.getAsJsonArray("peers")?.firstOrNull()?.asJsonObject ?: return null
        val secretKey = settings.get("secretKey")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val publicKey = peer.get("publicKey")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val endpoint = peer.get("endpoint")?.asString?.takeIf { it.isNotBlank() } ?: return null
        val lines = ArrayList<String>()
        lines += "[Interface]"
        lines += "PrivateKey = $secretKey"
        val addresses = settings.getAsJsonArray("address")
            ?.mapNotNull { it.takeIf { element -> element.isJsonPrimitive }?.asString }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        if (addresses.isEmpty()) return null
        lines += "Address = ${addresses.joinToString(", ")}"
        settings.get("mtu")?.asInt?.takeIf { it > 0 }?.let { lines += "MTU = $it" }
        settings.getAsJsonArray("reserved")
            ?.mapNotNull { it.asInt }
            ?.takeIf { it.size == 3 }
            ?.let { lines += "Reserved = ${it.joinToString(", ")}" }
        lines += ""
        lines += "[Peer]"
        lines += "PublicKey = $publicKey"
        peer.get("preSharedKey")?.asString?.takeIf { it.isNotBlank() }?.let { lines += "PresharedKey = $it" }
        lines += "Endpoint = $endpoint"
        peer.get("keepAlive")?.asInt?.takeIf { it > 0 }?.let { lines += "PersistentKeepalive = $it" }
        return lines.joinToString("\n")
    }

    // ---- Header / toolbar state ------------------------------------------------------------

    private fun isRunningProfile(profileId: Long): Boolean {
        if (!SagerNet.started || profileId <= 0L) return false
        val running = DataStore.currentProfile.takeIf { it > 0L } ?: DataStore.selectedProxy
        return running == profileId
    }

    private fun needsApply(): Boolean {
        return managedProfileId > 0L &&
            isRunningProfile(managedProfileId) &&
            smartRoutePrefs().getBoolean(pendingApplyKey(managedProfileId), false)
    }

    private enum class HeaderState(
        val statusRes: Int,
        val actionRes: Int?,
        val colorRes: Int,
    ) {
        NotConfigured(R.string.smart_route_not_configured, R.string.smart_route_action_setup, R.color.smart_route_status_error),
        MissingDefault(R.string.smart_route_status_missing_default, R.string.smart_route_action_setup, R.color.smart_route_status_error),
        MissingBypass(R.string.smart_route_status_missing_bypass, R.string.smart_route_action_setup, R.color.smart_route_status_error),
        Draft(R.string.smart_route_draft_summary, R.string.smart_route_action_save, R.color.smart_route_status_pending),
        SaveFailed(R.string.smart_route_status_save_failed, R.string.smart_route_button_retry_save, R.color.smart_route_status_error),
        Unsaved(R.string.smart_route_save_state_unsaved, R.string.smart_route_action_save, R.color.smart_route_status_pending),
        PendingApply(R.string.smart_route_status_pending_apply, R.string.smart_route_action_apply, R.color.smart_route_status_pending),
        NotInUse(R.string.smart_route_status_not_selected, R.string.smart_route_action_use, R.color.smart_route_status_idle),
        Applied(R.string.smart_route_status_applied, null, R.color.smart_route_status_ok),
        SavedIdle(R.string.smart_route_status_saved_idle, null, R.color.smart_route_status_ok),
    }

    private fun currentHeaderState(): HeaderState {
        val saved = managedProfileId > 0L
        return when {
            defaultConf.isBlank() && bypassConf.isBlank() -> HeaderState.NotConfigured
            defaultConf.isBlank() -> HeaderState.MissingDefault
            bypassConf.isBlank() -> HeaderState.MissingBypass
            !saved -> HeaderState.Draft
            saveError != null -> HeaderState.SaveFailed
            hasUnsavedChanges && !profileSaveInFlight -> HeaderState.Unsaved
            needsApply() -> HeaderState.PendingApply
            DataStore.selectedProxy != managedProfileId -> HeaderState.NotInUse
            isRunningProfile(managedProfileId) -> HeaderState.Applied
            else -> HeaderState.SavedIdle
        }
    }

    private fun applyResponsiveState() {
        if (!::actionButton.isInitialized) return
        val state = currentHeaderState()
        status.setText(state.statusRes)
        statusDot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(requireContext(), state.colorRes))
        if (state.actionRes == null) {
            actionButton.visibility = View.GONE
        } else {
            actionButton.visibility = View.VISIBLE
            actionButton.setText(state.actionRes)
        }
        statusRow.isClickable = state != HeaderState.Applied && state != HeaderState.SavedIdle

        defaultValue.text = confChipLabel(defaultConf, defaultConfLabel)
        bypassValue.text = confChipLabel(bypassConf, bypassConfLabel)
        proxyStatus.text = currentProxyStatus()
        val profileName = profileNameValue.ifBlank { getString(R.string.smart_route_default_profile_name) }
        profileSelector.text = if (managedProfileId > 0L) {
            profileName
        } else {
            getString(R.string.smart_route_draft_profile_named, profileName)
        }

        updateToolbarMode()
    }

    private fun confChipLabel(conf: String, label: String): String {
        return if (conf.isBlank()) {
            getString(R.string.smart_route_conf_chip_missing)
        } else {
            label.ifBlank { getString(R.string.smart_route_conf_source_saved) }
        }
    }

    private fun updateToolbarMode() {
        val selecting = selectedDomains.isNotEmpty()
        selectionBackCallback.isEnabled = selecting
        if (toolbarInSelectionMode != selecting) {
            toolbarInSelectionMode = selecting
            toolbar.menu.clear()
            if (selecting) {
                toolbar.inflateMenu(R.menu.smart_route_selection_menu)
                toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
                toolbar.setNavigationOnClickListener { clearSelection() }
            } else {
                toolbar.inflateMenu(R.menu.smart_route_menu)
                MenuCompat.setGroupDividerEnabled(toolbar.menu, true)
                toolbar.setNavigationIcon(R.drawable.ic_navigation_menu)
                toolbar.setNavigationOnClickListener {
                    (activity as MainActivity).binding.drawerLayout.openDrawer(GravityCompat.START)
                }
            }
        }
        if (selecting) {
            toolbar.title = getString(R.string.smart_route_selection_count, selectedDomains.size)
        } else {
            toolbar.title = getString(R.string.smart_route)
            val saved = managedProfileId > 0L
            toolbar.menu.findItem(R.id.action_smart_route_duplicate_profile)?.isEnabled = saved && hasCompleteConf()
            toolbar.menu.findItem(R.id.action_smart_route_delete_profile)?.isEnabled = saved
            toolbar.menu.findItem(R.id.action_smart_route_clear_all)?.isEnabled = domainItems.isNotEmpty()
            toolbar.menu.findItem(R.id.action_smart_route_export)?.isEnabled = domainItems.isNotEmpty()
        }
    }

    private fun hasCompleteConf() = defaultConf.isNotBlank() && bypassConf.isNotBlank()

    private fun hasUnsavedDraft(): Boolean {
        return managedProfileId <= 0L && (
            defaultConf.isNotBlank() ||
                bypassConf.isNotBlank() ||
                domainItems.isNotEmpty()
            )
    }

    private fun ensureSmartRouteRuntimeDefaults() {
        if (DataStore.serviceMode != Key.MODE_PROXY) DataStore.serviceMode = Key.MODE_PROXY
        if (!DataStore.requireSocks) DataStore.requireSocks = true
        if (!DataStore.requireHttp) DataStore.requireHttp = true
        if (!DataStore.appendHttpProxy) DataStore.appendHttpProxy = true
        DataStore.socksPort = DataStore.socksPort
        DataStore.httpPort = DataStore.httpPort
    }

    private fun currentProxyStatus(): String {
        val listen = if (DataStore.allowAccess) "0.0.0.0" else "127.0.0.1"
        val socks = if (DataStore.requireSocks) {
            getString(R.string.smart_route_proxy_socks_enabled, listen, DataStore.socksPort)
        } else {
            getString(R.string.smart_route_proxy_socks_disabled)
        }
        val http = if (DataStore.requireHttp) {
            getString(R.string.smart_route_proxy_http_enabled, listen, DataStore.httpPort)
        } else {
            getString(R.string.smart_route_proxy_http_disabled)
        }
        return "$socks · $http"
    }

    private fun smartRoutePrefs() = smartRoutePrefs(requireContext())

    private fun markChanged() {
        if (!loadingState) {
            hasUnsavedChanges = true
            generatedJson = ""
            persistSmartRouteState()
            saveProfileIfReady()
            applyResponsiveState()
        }
    }

    private fun saveProfileIfReady() {
        if (managedProfileId > 0L && hasCompleteConf()) saveProfile(silent = true)
    }

    // ---- Profiles --------------------------------------------------------------------------

    private fun showProfilePicker() {
        runOnDefaultDispatcher {
            val choices = smartRouteProfiles().map { profile ->
                ProfileChoice(
                    profile = profile,
                    domainCount = extractSmartRouteState(profile.configBean?.content.orEmpty())?.domains?.size ?: 0,
                )
            }
            val inUseId = DataStore.selectedProxy
            onMainDispatcher {
                if (view == null) return@onMainDispatcher
                if (choices.isEmpty()) {
                    showMessage(R.string.smart_route_profile_empty)
                    showConfSheet()
                    return@onMainDispatcher
                }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.smart_route_select_profile_title)
                    .setAdapter(ProfileChoiceAdapter(choices, inUseId)) { _, which -> selectProfile(choices[which].profile.id) }
                    .setPositiveButton(R.string.smart_route_new_profile) { _, _ -> startNewProfile() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private inner class ProfileChoiceAdapter(
        private val choices: List<ProfileChoice>,
        private val inUseId: Long,
    ) : BaseAdapter() {
        override fun getCount() = choices.size
        override fun getItem(position: Int) = choices[position]
        override fun getItemId(position: Int) = choices[position].profile.id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val choice = choices[position]
            val badges = listOfNotNull(
                getString(R.string.smart_route_profile_in_use).takeIf { choice.profile.id == inUseId },
                getString(R.string.smart_route_profile_editing).takeIf { choice.profile.id == managedProfileId },
            )
            val marker = if (choice.profile.id == inUseId) getString(R.string.smart_route_profile_active_marker) else ""
            view.findViewById<TextView>(android.R.id.text1).text = marker + choice.profile.displayName()
            view.findViewById<TextView>(android.R.id.text2).text =
                (listOf(getString(R.string.smart_route_profile_summary_domains, choice.domainCount)) + badges)
                    .joinToString(" · ")
            return view
        }
    }

    /** Explicit switch from the picker: load into the editor and make it the profile in use. */
    private fun selectProfile(profileId: Long) {
        when {
            hasUnsavedDraft() -> confirmDiscard(
                R.string.smart_route_discard_draft_title,
                getString(R.string.smart_route_discard_draft_message),
            ) { switchToProfile(profileId) }
            saveError != null -> confirmDiscard(
                R.string.smart_route_save_failed_title,
                saveError.orEmpty(),
            ) { switchToProfile(profileId) }
            else -> switchToProfile(profileId)
        }
    }

    private fun switchToProfile(profileId: Long) {
        val profile = ProfileManager.getProfile(profileId)
        if (profile == null || !loadProfileIntoEditor(profile)) {
            showMessage(R.string.smart_route_profile_missing)
            return
        }
        val changed = DataStore.selectedProxy != profile.id
        DataStore.selectedProxy = profile.id
        DataStore.selectedGroup = profile.groupId
        markSelectedProxySeen()
        if (changed && SagerNet.started) {
            clearPendingApply(requireContext())
            SagerNet.reloadService()
            showMessage(getString(R.string.smart_route_profile_selected_reloaded, profile.displayName()))
        } else {
            showMessage(getString(R.string.smart_route_profile_selected, profile.displayName()))
        }
        applyResponsiveState()
    }

    /** Loads a profile into the editor without changing the profile in use. */
    private fun loadProfileIntoEditor(profile: ProxyEntity): Boolean {
        if (profile.type != ProxyEntity.TYPE_CONFIG || extractSmartRouteState(profile.configBean?.content.orEmpty()) == null) {
            return false
        }
        profileStateVersion += 1
        loadingState = true
        resetSmartRouteState(profile.displayName())
        managedProfileId = profile.id
        if (!loadProfileContentState(profile) && !loadStoredSmartRouteState(profile.id.toString(), loadName = false)) {
            loadingState = false
            return false
        }
        profileNameValue = profile.displayName().ifBlank { profileNameValue }
        loadingState = false
        hasUnsavedChanges = false
        saveError = null
        smartRoutePrefs().edit()
            .putLong("manager.groupId", profile.groupId)
            .putLong("manager.activeProfileId", profile.id)
            .putLong("manager.profileId", profile.id)
            .commit()
        renderDomainList()
        updateDomainPreview()
        applyResponsiveState()
        return true
    }

    private fun isSmartRouteProfile(profileId: Long): Boolean {
        val profile = ProfileManager.getProfile(profileId) ?: return false
        return profile.type == ProxyEntity.TYPE_CONFIG && extractSmartRouteState(profile.configBean?.content.orEmpty()) != null
    }

    private fun startNewProfile() {
        if (hasUnsavedDraft()) {
            confirmDiscard(
                R.string.smart_route_discard_draft_title,
                getString(R.string.smart_route_discard_draft_message),
            ) { resetNewProfile() }
            return
        }
        resetNewProfile()
    }

    private fun resetNewProfile() {
        profileStateVersion += 1
        loadingState = true
        resetSmartRouteState(nextProfileName())
        managedProfileId = 0L
        saveError = null
        smartRoutePrefs().edit()
            .putLong("manager.activeProfileId", 0L)
            .putLong("manager.profileId", 0L)
            .remove("draft.name")
            .remove("draft.defaultConf")
            .remove("draft.bypassConf")
            .remove("draft.defaultConfLabel")
            .remove("draft.bypassConfLabel")
            .remove("draft.dnsServer")
            .remove("draft.domains")
            .remove("draft.json")
            .commit()
        persistSmartRouteState()
        loadingState = false
        hasUnsavedChanges = false
        renderDomainList()
        updateDomainPreview()
        applyResponsiveState()
        showConfSheet()
    }

    private fun confirmDiscard(titleRes: Int, message: String, onDiscard: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(R.string.smart_route_discard_draft_confirm) { _, _ -> onDiscard() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun duplicateCurrentProfile() {
        // A draft has nothing to duplicate yet; saving it is the right action.
        if (managedProfileId <= 0L) {
            saveProfile(silent = false)
            return
        }
        val result = generateOrShow(updateDomainText = true) ?: return
        val sourceName = profileNameValue.ifBlank { getString(R.string.smart_route_default_profile_name) }
        val duplicateName = uniqueProfileName(getString(R.string.smart_route_profile_copy_name, sourceName))
        val prefs = smartRoutePrefs()
        val smartRouteGroupName = getString(R.string.smart_route_group_name)
        runOnDefaultDispatcher {
            val saved = runCatching {
                val group = ensureSmartRouteGroup(prefs, smartRouteGroupName)
                val bean = ConfigBean().apply {
                    name = duplicateName
                    type = "v2ray"
                    content = result.json
                    serverAddresses = ""
                }
                val profile = ProfileManager.createProfile(group.id, bean)
                GroupManager.postUpdate(group)
                prefs.edit()
                    .putLong("manager.groupId", group.id)
                    .putString("${profile.id}.name", duplicateName)
                    .putString("${profile.id}.defaultConf", defaultConf)
                    .putString("${profile.id}.bypassConf", bypassConf)
                    .putString("${profile.id}.defaultConfLabel", defaultConfLabel)
                    .putString("${profile.id}.bypassConfLabel", bypassConfLabel)
                    .putString("${profile.id}.dnsServer", dnsServer)
                    .putString("${profile.id}.domains", result.domains.joinToString("\n"))
                    .putString("${profile.id}.json", result.json)
                    .commit()
                profile
            }
            onMainDispatcher {
                if (view == null) return@onMainDispatcher
                saved.onFailure {
                    showError(getString(R.string.smart_route_save_failed))
                }.onSuccess {
                    if (loadProfileIntoEditor(it)) {
                        showMessage(getString(R.string.smart_route_profile_duplicated, it.displayName()))
                    } else {
                        showMessage(R.string.smart_route_profile_missing)
                    }
                }
            }
        }
    }

    private fun renameCurrentProfile() {
        val edit = EditText(requireContext()).apply {
            setText(profileNameValue)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            selectAll()
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.smart_route_rename_profile_title)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = edit.text.toString().trim().ifBlank { getString(R.string.smart_route_default_profile_name) }
                renameProfileTo(newName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renameProfileTo(newName: String) {
        if (newName == profileNameValue) return
        profileNameValue = newName
        if (managedProfileId <= 0L) {
            markChanged()
            return
        }
        runOnDefaultDispatcher {
            val renamed = runCatching {
                val profile = checkNotNull(ProfileManager.getProfile(managedProfileId))
                val bean = checkNotNull(profile.configBean)
                bean.name = newName
                profile.putBean(bean)
                ProfileManager.updateProfile(profile)
                smartRoutePrefs().edit()
                    .putString("${profile.id}.name", newName)
                    .commit()
                profile
            }
            onMainDispatcher {
                if (view == null) return@onMainDispatcher
                renamed.onFailure {
                    showError(getString(R.string.smart_route_save_failed))
                }.onSuccess {
                    applyResponsiveState()
                    showMessage(R.string.smart_route_profile_renamed)
                }
            }
        }
    }

    private fun deleteCurrentProfile() {
        val profile = ProfileManager.getProfile(managedProfileId) ?: return
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.smart_route_delete_profile_title)
            .setMessage(getString(R.string.smart_route_delete_profile_message, profile.displayName()))
            .setPositiveButton(R.string.smart_route_delete_profile_confirm) { _, _ ->
                deleteProfile(profile)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteProfile(profile: ProxyEntity) {
        val prefs = smartRoutePrefs()
        runOnDefaultDispatcher {
            runCatching {
                ProfileManager.deleteProfile(profile.groupId, profile.id)
                prefs.edit()
                    .remove("${profile.id}.name")
                    .remove("${profile.id}.defaultConf")
                    .remove("${profile.id}.bypassConf")
                    .remove("${profile.id}.defaultConfLabel")
                    .remove("${profile.id}.bypassConfLabel")
                    .remove("${profile.id}.dnsServer")
                    .remove("${profile.id}.domains")
                    .remove("${profile.id}.json")
                    .remove(pendingApplyKey(profile.id))
                    .apply {
                        if (prefs.getLong("manager.activeProfileId", 0L) == profile.id) {
                            putLong("manager.activeProfileId", 0L)
                            putLong("manager.profileId", 0L)
                        }
                    }
                    .commit()
                smartRouteProfiles().firstOrNull()
            }.let { deleted ->
                onMainDispatcher {
                    if (view == null) return@onMainDispatcher
                    deleted.onFailure {
                        showError(getString(R.string.smart_route_save_failed))
                    }.onSuccess { next ->
                        managedProfileId = 0L
                        if (next == null || !loadProfileIntoEditor(next)) {
                            resetNewProfile()
                        }
                        showMessage(R.string.smart_route_profile_deleted)
                    }
                }
            }
        }
    }

    private fun smartRouteProfiles(): List<ProxyEntity> {
        val groupId = smartRoutePrefs().getLong("manager.groupId", 0L)
        val profiles = if (groupId > 0L && SagerDatabase.groupDao.getById(groupId) != null) {
            SagerDatabase.proxyDao.getByGroup(groupId)
        } else {
            SagerDatabase.proxyDao.getAll()
        }
        return profiles
            .filter { it.type == ProxyEntity.TYPE_CONFIG }
            .filter { extractSmartRouteState(it.configBean?.content.orEmpty()) != null }
            .sortedBy { it.userOrder }
    }

    private fun resetSmartRouteState(name: String) {
        profileNameValue = name
        defaultConf = ""
        bypassConf = ""
        defaultConfLabel = ""
        bypassConfLabel = ""
        dnsServer = SmartRouteConfigGenerator.DEFAULT_DNS_SERVER
        generatedJson = ""
        domainFilter = ""
        selectedDomains.clear()
        setDomains(emptyList(), resetSelection = true)
    }

    private fun nextProfileName(): String {
        val base = getString(R.string.smart_route_default_profile_name)
        val existing = smartRouteProfiles().map { it.displayName() }.toSet()
        if (base !in existing) return base
        var index = 2
        while (true) {
            val candidate = getString(R.string.smart_route_numbered_profile_name, index)
            if (candidate !in existing) return candidate
            index += 1
        }
    }

    private fun uniqueProfileName(seed: String): String {
        val existing = smartRouteProfiles().map { it.displayName() }.toSet()
        if (seed !in existing) return seed
        var index = 2
        while (true) {
            val candidate = "$seed $index"
            if (candidate !in existing) return candidate
            index += 1
        }
    }

    private suspend fun ensureSmartRouteGroup(
        prefs: SharedPreferences,
        groupName: String,
    ): ProxyGroup {
        prefs.getLong("manager.groupId", 0L)
            .takeIf { it > 0L }
            ?.let { SagerDatabase.groupDao.getById(it) }
            ?.let { return it }

        SagerDatabase.groupDao.allGroups()
            .firstOrNull { !it.ungrouped && it.name == groupName }
            ?.let {
                prefs.edit().putLong("manager.groupId", it.id).commit()
                return it
            }

        return GroupManager.createGroup(ProxyGroup(name = groupName)).also {
            prefs.edit().putLong("manager.groupId", it.id).commit()
        }
    }

    private fun persistSmartRouteState() {
        val prefix = if (managedProfileId > 0L) managedProfileId.toString() else "draft"
        smartRoutePrefs().edit()
            .apply {
                if (managedProfileId > 0L) putLong("manager.profileId", managedProfileId)
                if (managedProfileId > 0L) putLong("manager.activeProfileId", managedProfileId)
                putString("$prefix.name", profileNameValue)
                putString("$prefix.defaultConf", defaultConf)
                putString("$prefix.bypassConf", bypassConf)
                putString("$prefix.defaultConfLabel", defaultConfLabel)
                putString("$prefix.bypassConfLabel", bypassConfLabel)
                putString("$prefix.dnsServer", dnsServer)
                putString("$prefix.domains", domainItems.joinToString("\n"))
                putString("$prefix.json", generatedJson)
            }
            .commit()
    }

    // ---- Dialog helpers --------------------------------------------------------------------

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

    private fun showJsonPreview(json: String) {
        val edit = EditText(requireContext()).apply {
            setText(json)
            setSingleLine(false)
            minLines = 12
            maxLines = 18
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isHorizontalScrollBarEnabled = true
            setTextIsSelectable(true)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.smart_route_json_preview_title)
            .setView(edit)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.smart_route_copy_json) { _, _ ->
                SagerNet.trySetPrimaryClip(json)
                showMessage(R.string.smart_route_json_copied)
            }
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

    private fun displayName(uri: Uri): String? {
        return runCatching {
            requireContext().contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
        }.getOrNull()
    }

    private data class DomainRow(
        val domain: String,
        val selected: Boolean,
        val selectionMode: Boolean,
    )

    private inner class DomainAdapter(
        private val onClick: (String) -> Unit,
        private val onLongClick: (String) -> Boolean,
        private val onDelete: (String) -> Unit,
    ) : ListAdapter<DomainRow, DomainAdapter.DomainHolder>(DomainDiff) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DomainHolder {
            val view = layoutInflater.inflate(R.layout.layout_smart_route_domain_item, parent, false)
            return DomainHolder(view)
        }

        override fun onBindViewHolder(holder: DomainHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class DomainHolder(view: View) : RecyclerView.ViewHolder(view) {
            private val card = view.findViewById<MaterialCardView>(R.id.domain_card)
            private val domainText = view.findViewById<TextView>(R.id.domain_text)
            private val deleteAction = view.findViewById<View>(R.id.domain_delete)

            fun bind(row: DomainRow) {
                val domain = row.domain
                domainText.text = domain
                card.isChecked = row.selected
                // In selection mode the toolbar owns deletion; the per-row button would be ambiguous.
                deleteAction.visibility = if (row.selectionMode) View.GONE else View.VISIBLE
                card.setOnClickListener { onClick(domain) }
                card.setOnLongClickListener { onLongClick(domain) }
                deleteAction.setOnClickListener { onDelete(domain) }
            }
        }
    }

    private object DomainDiff : DiffUtil.ItemCallback<DomainRow>() {
        override fun areItemsTheSame(oldItem: DomainRow, newItem: DomainRow) = oldItem.domain == newItem.domain
        override fun areContentsTheSame(oldItem: DomainRow, newItem: DomainRow) = oldItem == newItem
    }

    companion object {
        private const val PREFS_NAME = "smart_route_profiles"

        private fun smartRoutePrefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun pendingApplyKey(profileId: Long) = "$profileId.pendingApply"

        /**
         * The running service reloads profiles from the database whenever it (re)connects or stops,
         * so any "saved but not applied" marker is stale after such a transition.
         */
        fun clearPendingApply(context: Context) {
            val prefs = smartRoutePrefs(context)
            val keys = prefs.all.keys.filter { it.endsWith(".pendingApply") }
            if (keys.isEmpty()) return
            prefs.edit().apply { keys.forEach { remove(it) } }.apply()
        }
    }
}
