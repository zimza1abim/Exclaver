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
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
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
    )

    private lateinit var domainInput: EditText
    private lateinit var domainPreview: TextView
    private lateinit var domainAdd: TextView
    private lateinit var domainListView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var emptyTitle: TextView
    private lateinit var emptySummary: TextView
    private lateinit var saveProfileButton: ExtendedFloatingActionButton
    private lateinit var status: TextView
    private lateinit var proxyStatus: TextView
    private lateinit var profileSelector: TextView
    private lateinit var domainAdapter: DomainAdapter

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val text = readText(uri) ?: return@registerForActivityResult
        when (importTarget) {
            ImportTarget.DefaultConf -> {
                defaultConf = text
                defaultConfLabel = displayName(uri) ?: getString(R.string.smart_route_conf_source_file)
                markChanged()
                showMessage(R.string.smart_route_default_loaded)
                refreshConfSheet()
            }
            ImportTarget.BypassConf -> {
                bypassConf = text
                bypassConfLabel = displayName(uri) ?: getString(R.string.smart_route_conf_source_file)
                markChanged()
                showMessage(R.string.smart_route_bypass_loaded)
                refreshConfSheet()
            }
            ImportTarget.Domains -> {
                try {
                    setDomains(SmartRouteDomainNormalizer.normalizeLines(text), resetSelection = true)
                    markChanged()
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
        toolbar.inflateMenu(R.menu.smart_route_menu)
        toolbar.setOnMenuItemClickListener(this)

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
        syncSelectedConfigurationProfile()
    }

    private fun bindViews(view: View) {
        domainInput = view.findViewById(R.id.domain_input)
        domainPreview = view.findViewById(R.id.domain_preview)
        domainAdd = view.findViewById(R.id.domain_add)
        domainListView = view.findViewById(R.id.domain_list)
        emptyState = view.findViewById(R.id.empty_state)
        emptyTitle = emptyState.findViewById(R.id.empty_title)
        emptySummary = emptyState.findViewById(R.id.empty_summary)
        saveProfileButton = view.findViewById(R.id.save_profile)
        status = view.findViewById(R.id.smart_route_status)
        proxyStatus = view.findViewById(R.id.smart_route_proxy_status)
        profileSelector = view.findViewById(R.id.smart_route_profile_selector)
    }

    private fun setupDomainList() {
        domainAdapter = DomainAdapter(
            onClick = { domain ->
                toggleSelected(domain)
            },
            onLongClick = { domain ->
                toggleSelected(domain)
                true
            },
            onEdit = { domain ->
                selectedDomains.clear()
                selectedDomains.add(domain)
                renderDomainList()
                editDomain(domain)
            },
            onDelete = { domain ->
                removeDomain(domain)
            },
        )
        domainListView.layoutManager = LinearLayoutManager(requireContext())
        domainListView.adapter = domainAdapter
        domainListView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy > 0) saveProfileButton.shrink() else if (dy < 0) saveProfileButton.extend()
            }
        })
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
        saveProfileButton.setOnClickListener { saveProfile(silent = false) }
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
            R.id.action_smart_route_proxy_settings -> (requireActivity() as MainActivity).displayFragmentWithId(R.id.nav_settings)
            R.id.action_smart_route_clear_all -> {
                setDomains(emptyList(), resetSelection = true)
                markChanged()
            }
            else -> return false
        }
        return true
    }

    override fun onSmartRouteConfDone(profileName: String, dnsServer: String): Boolean {
        profileNameValue = profileName.trim().ifBlank { getString(R.string.smart_route_default_profile_name) }
        this.dnsServer = dnsServer.trim().ifBlank { SmartRouteConfigGenerator.DEFAULT_DNS_SERVER }
        markChanged()
        return if (hasCompleteConf()) {
            true
        } else {
            showMessage(R.string.smart_route_save_state_missing_setup)
            false
        }
    }

    override fun onSmartRouteConfFile(default: Boolean) {
        importTarget = if (default) ImportTarget.DefaultConf else ImportTarget.BypassConf
        startFilesForResult(importFile, "*/*")
    }

    override fun onSmartRouteConfClipboard(default: Boolean) = pasteConf(default)

    override fun onSmartRouteConfEdit(default: Boolean) {
        editTextDialog(
            if (default) R.string.smart_route_default_conf else R.string.smart_route_bypass_conf,
            if (default) defaultConf else bypassConf,
        ) {
            if (default) {
                defaultConf = it
                defaultConfLabel = getString(R.string.smart_route_conf_source_manual)
            } else {
                bypassConf = it
                bypassConfLabel = getString(R.string.smart_route_conf_source_manual)
            }
            generatedJson = ""
            markChanged()
            refreshConfSheet()
        }
    }

    override fun onSmartRouteConfClear(default: Boolean) {
        if (default) {
            defaultConf = ""
            defaultConfLabel = ""
        } else {
            bypassConf = ""
            bypassConfLabel = ""
        }
        generatedJson = ""
        markChanged()
        refreshConfSheet()
        showMessage(R.string.smart_route_cleared)
    }

    private fun showConfSheet() {
        SmartRouteConfBottomSheet.newInstance(
            profileName = profileNameValue,
            dnsServer = dnsServer,
            defaultReady = defaultConf.isNotBlank(),
            bypassReady = bypassConf.isNotBlank(),
            defaultLabel = defaultConfLabel,
            bypassLabel = bypassConfLabel,
        ).show(childFragmentManager, "smart_route_conf")
    }

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

    private fun pasteConf(default: Boolean) {
        val text = SagerNet.getClipboardText()
        if (text.isEmpty()) {
            showMessage(R.string.clipboard_empty)
            return
        }
        if (default) {
            defaultConf = text
            defaultConfLabel = getString(R.string.smart_route_conf_source_clipboard)
            showMessage(R.string.smart_route_default_loaded)
        } else {
            bypassConf = text
            bypassConfLabel = getString(R.string.smart_route_conf_source_clipboard)
            showMessage(R.string.smart_route_bypass_loaded)
        }
        generatedJson = ""
        markChanged()
        refreshConfSheet()
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
        try {
            val current = SmartRouteDomainNormalizer.normalizeLines(domainItems.joinToString("\n"))
            val incoming = SmartRouteDomainNormalizer.normalizeLines(domainInput.text?.toString().orEmpty())
            if (incoming.isEmpty()) throw SmartRouteInputException(R.string.smart_route_error_domain_empty)
            val merged = (current + incoming).distinct()
            val added = merged.size - current.size
            setDomains(merged)
            domainInput.setText("")
            if (added > 0 && domainFilter.isNotBlank()) {
                domainFilter = ""
                renderDomainList()
            }
            generatedJson = ""
            markChanged()
            if (added == 0) {
                showMessage(R.string.smart_route_domains_already_exists)
            } else {
                val skipped = incoming.size - added
                showMessage(
                    if (skipped > 0) {
                        getString(R.string.smart_route_domains_added_with_duplicates, added, skipped)
                    } else {
                        getString(R.string.smart_route_domains_added, added)
                    },
                )
            }
        } catch (e: SmartRouteInputException) {
            showError(formatInputError(e))
        }
    }

    private fun toggleSelected(domain: String) {
        if (domain in selectedDomains) selectedDomains.remove(domain) else selectedDomains.add(domain)
        renderDomainList()
    }

    private fun removeSelectedDomains() {
        if (selectedDomains.isEmpty()) {
            showMessage(R.string.smart_route_no_selected_domains)
            return
        }
        setDomains(domainItems.filterNot { it in selectedDomains }, resetSelection = true)
        generatedJson = ""
        markChanged()
    }

    private fun removeDomain(domain: String) {
        if (domain !in domainItems) return
        selectedDomains.remove(domain)
        setDomains(domainItems.filterNot { it == domain })
        generatedJson = ""
        markChanged()
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
        domainAdapter.submitList(visibleDomains)
        emptyState.visibility = if (visibleDomains.isEmpty()) View.VISIBLE else View.GONE
        if (domainItems.isEmpty()) {
            emptyTitle.setText(R.string.smart_route_empty_title)
            emptySummary.setText(R.string.smart_route_empty_summary)
        } else {
            emptyTitle.setText(R.string.smart_route_search_empty_title)
            emptySummary.setText(R.string.smart_route_search_empty_summary)
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
                    markChanged()
                } catch (e: SmartRouteInputException) {
                    showError(formatInputError(e))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateOrShow(updateDomainText: Boolean) = generateSmartRoute(updateDomainText, showErrors = true)

    private fun generateSmartRoute(updateDomainText: Boolean, showErrors: Boolean): SmartRouteGeneratedConfig? = try {
        val result = SmartRouteConfigGenerator.generate(
            defaultConf = defaultConf,
            bypassConf = bypassConf,
            domainText = domainItems.joinToString("\n"),
            dnsServer = dnsServer,
        )
        generatedJson = result.json
        if (updateDomainText) setDomains(result.domains)
        result
    } catch (e: SmartRouteInputException) {
        if (showErrors) showError(formatInputError(e))
        null
    } catch (_: Exception) {
        if (showErrors) showError(getString(R.string.smart_route_error_invalid_wireguard_conf))
        null
    }

    private fun saveProfile(silent: Boolean) {
        if (profileSaveInFlight) {
            profileSaveQueued = true
            profileSaveQueuedSilent = profileSaveQueuedSilent && silent
            return
        }
        profileSaveQueuedSilent = true
        ensureSmartRouteRuntimeDefaults()
        val result = generateSmartRoute(updateDomainText = true, showErrors = !silent) ?: return
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
                SaveResult(profile.id, groupId)
            }
            onMainDispatcher {
                profileSaveInFlight = false
                saved.onFailure {
                    if (!silent) showError(getString(R.string.smart_route_save_failed))
                }.onSuccess { saveResult ->
                    if (snapshot.stateVersion != profileStateVersion) {
                        return@onSuccess
                    }
                    managedProfileId = saveResult.profileId
                    DataStore.selectedProxy = saveResult.profileId
                    DataStore.selectedGroup = saveResult.groupId
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
                    if (!silent) {
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

    private fun loadManagedSmartRoute() {
        val prefs = smartRoutePrefs()
        val storedActiveProfileId = prefs.getLong("manager.activeProfileId", 0L)
        if (storedActiveProfileId <= 0L && loadStoredSmartRouteState("draft")) return

        managedProfileId = DataStore.selectedProxy
            .takeIf { it > 0L && isSmartRouteProfile(it) }
            ?: storedActiveProfileId.takeIf { it > 0L }
            ?: prefs.getLong("manager.profileId", 0L)
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
            setDomains(SmartRouteDomainNormalizer.normalizeLines(savedDomains), resetSelection = true)
        }
        if (!hasCompleteConf() || domainItems.isEmpty()) {
            extractSmartRouteState(managedProfile?.configBean?.content.orEmpty())?.let {
                applyRestoredSmartRouteState(it, generatedJson.ifBlank { managedProfile?.configBean?.content.orEmpty() })
                persistSmartRouteState()
            }
        }
    }

    private fun syncSelectedConfigurationProfile() {
        if (managedProfileId <= 0L) return
        val selectedProfileId = DataStore.selectedProxy
            .takeIf { it > 0L && it != managedProfileId && isSmartRouteProfile(it) }
            ?: return
        val profile = ProfileManager.getProfile(selectedProfileId) ?: return
        loadProfileIntoEditor(profile, announce = false)
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
            if (storedDomains.isBlank()) emptyList() else SmartRouteDomainNormalizer.normalizeLines(storedDomains),
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

    private fun applyResponsiveState() {
        val configured = hasCompleteConf() && managedProfileId > 0L && ProfileManager.getProfile(managedProfileId) != null
        status.setText(
            when {
                configured -> R.string.smart_route_configured_summary
                hasUnsavedDraft() -> R.string.smart_route_draft_summary
                else -> R.string.smart_route_not_configured
            },
        )
        proxyStatus.text = currentProxyStatus()
        val profileName = profileNameValue.ifBlank { getString(R.string.smart_route_default_profile_name) }
        profileSelector.text = getString(
            if (managedProfileId > 0L) R.string.smart_route_current_profile_named else R.string.smart_route_draft_profile_named,
            profileName,
        )

        val canSave = hasCompleteConf()
        saveProfileButton.isEnabled = canSave
        saveProfileButton.text = getString(
            when {
                managedProfileId <= 0L -> R.string.smart_route_save_new_profile
                hasUnsavedChanges -> R.string.smart_route_save_changes
                else -> R.string.smart_route_save_profile
            },
        )

        toolbar.menu.findItem(R.id.action_smart_route_delete_selected)?.isVisible = selectedDomains.isNotEmpty()
        toolbar.menu.findItem(R.id.action_smart_route_duplicate_profile)?.isEnabled = hasCompleteConf()
        toolbar.menu.findItem(R.id.action_smart_route_delete_profile)?.isEnabled = managedProfileId > 0L
        toolbar.menu.findItem(R.id.action_smart_route_default_status)?.title = routeLabel(default = true)
        toolbar.menu.findItem(R.id.action_smart_route_bypass_status)?.title = routeLabel(default = false)
        toolbar.title = if (selectedDomains.isEmpty()) {
            getString(R.string.smart_route)
        } else {
            getString(R.string.smart_route_domains_count_selected, domainItems.size, selectedDomains.size)
        }
        domainAdapter.selected = selectedDomains.toSet()
        domainAdapter.notifyDataSetChanged()
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
        return getString(R.string.smart_route_proxy_status, socks, http)
    }

    private fun smartRoutePrefs() = requireContext().getSharedPreferences("smart_route_profiles", android.content.Context.MODE_PRIVATE)

    private fun markChanged() {
        if (!loadingState) {
            hasUnsavedChanges = true
            generatedJson = ""
            persistSmartRouteState()
            applyResponsiveState()
            saveProfileIfReady()
        }
    }

    private fun saveProfileIfReady() {
        if (managedProfileId > 0L && hasCompleteConf()) saveProfile(silent = true)
    }

    private fun showProfilePicker() {
        runOnDefaultDispatcher {
            val profiles = smartRouteProfiles()
            onMainDispatcher {
                if (profiles.isEmpty()) {
                    showMessage(R.string.smart_route_profile_empty)
                    showConfSheet()
                    return@onMainDispatcher
                }
                val names = profiles.map { profile ->
                    val marker = if (profile.id == managedProfileId) getString(R.string.smart_route_profile_active_marker) else ""
                    marker + profile.displayName()
                }.toTypedArray()
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.smart_route_select_profile_title)
                    .setItems(names) { _, which -> selectProfile(profiles[which].id) }
                    .setPositiveButton(R.string.smart_route_new_profile) { _, _ -> startNewProfile() }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun selectProfile(profileId: Long) {
        if (hasUnsavedDraft()) {
            confirmDiscardDraft { loadSelectedProfile(profileId) }
            return
        }
        loadSelectedProfile(profileId)
    }

    private fun loadSelectedProfile(profileId: Long) {
        val profile = ProfileManager.getProfile(profileId) ?: run {
            showMessage(R.string.smart_route_profile_missing)
            return
        }
        if (!loadProfileIntoEditor(profile, announce = true)) {
            showMessage(R.string.smart_route_profile_missing)
        }
    }

    private fun loadProfileIntoEditor(profile: ProxyEntity, announce: Boolean): Boolean {
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
        DataStore.selectedProxy = profile.id
        DataStore.selectedGroup = profile.groupId
        smartRoutePrefs().edit()
            .putLong("manager.groupId", profile.groupId)
            .putLong("manager.activeProfileId", profile.id)
            .putLong("manager.profileId", profile.id)
            .commit()
        renderDomainList()
        updateDomainPreview()
        applyResponsiveState()
        if (announce) showMessage(getString(R.string.smart_route_profile_selected, profile.displayName()))
        return true
    }

    private fun isSmartRouteProfile(profileId: Long): Boolean {
        val profile = ProfileManager.getProfile(profileId) ?: return false
        return profile.type == ProxyEntity.TYPE_CONFIG && extractSmartRouteState(profile.configBean?.content.orEmpty()) != null
    }

    private fun startNewProfile() {
        if (hasUnsavedDraft()) {
            confirmDiscardDraft { resetNewProfile() }
            return
        }
        resetNewProfile()
    }

    private fun resetNewProfile() {
        profileStateVersion += 1
        loadingState = true
        resetSmartRouteState(nextProfileName())
        managedProfileId = 0L
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

    private fun confirmDiscardDraft(onDiscard: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.smart_route_discard_draft_title)
            .setMessage(R.string.smart_route_discard_draft_message)
            .setPositiveButton(R.string.smart_route_discard_draft_confirm) { _, _ -> onDiscard() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun duplicateCurrentProfile() {
        val result = generateSmartRoute(updateDomainText = true, showErrors = true) ?: return
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
                    .putLong("manager.activeProfileId", profile.id)
                    .putLong("manager.profileId", profile.id)
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
                saved.onFailure {
                    showError(getString(R.string.smart_route_save_failed))
                }.onSuccess {
                    selectProfile(it.id)
                    showMessage(getString(R.string.smart_route_profile_duplicated, it.displayName()))
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
        profileNameValue = newName
        if (managedProfileId <= 0L) {
            markChanged()
            applyResponsiveState()
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
                renamed.onFailure {
                    showError(getString(R.string.smart_route_save_failed))
                }.onSuccess {
                    hasUnsavedChanges = false
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
                    .apply {
                        if (prefs.getLong("manager.activeProfileId", 0L) == profile.id) {
                            putLong("manager.activeProfileId", 0L)
                            putLong("manager.profileId", 0L)
                        }
                    }
                    .commit()
                val next = smartRouteProfiles().firstOrNull()
                next
            }.let { deleted ->
                onMainDispatcher {
                    deleted.onFailure {
                        showError(getString(R.string.smart_route_save_failed))
                    }.onSuccess { next ->
                        if (next != null) {
                            selectProfile(next.id)
                        } else {
                            startNewProfile()
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
        prefs: android.content.SharedPreferences,
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

    private fun updateDomainPreview() {
        val text = domainInput.text?.toString().orEmpty()
        if (text.isBlank()) {
            domainPreview.setText(R.string.smart_route_domain_preview_empty)
            return
        }
        try {
            val normalized = SmartRouteDomainNormalizer.normalizeLines(text)
            domainPreview.text = when {
                normalized.isEmpty() -> getString(R.string.smart_route_domain_preview_empty)
                normalized.size == 1 -> getString(R.string.smart_route_domain_preview_one, normalized.first())
                else -> getString(R.string.smart_route_domain_preview_many, normalized.size, normalized.first())
            }
        } catch (e: SmartRouteInputException) {
            domainPreview.setText(R.string.smart_route_domain_preview_invalid)
        }
    }

    private fun routeLabel(default: Boolean): String {
        return if (default) {
            if (defaultConf.isBlank()) {
                getString(R.string.smart_route_default_conf_missing)
            } else {
                getString(
                    R.string.smart_route_default_route_current,
                    defaultConfLabel.ifBlank { getString(R.string.smart_route_conf_source_saved) },
                )
            }
        } else {
            if (bypassConf.isBlank()) {
                getString(R.string.smart_route_bypass_conf_missing)
            } else {
                getString(
                    R.string.smart_route_bypass_route_current,
                    bypassConfLabel.ifBlank { getString(R.string.smart_route_conf_source_saved) },
                )
            }
        }
    }

    private fun editDomainsAdvanced() {
        editTextDialog(R.string.smart_route_advanced_domain_edit, domainItems.joinToString("\n")) {
            try {
                setDomains(SmartRouteDomainNormalizer.normalizeLines(it), resetSelection = true)
                generatedJson = ""
                markChanged()
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

    private inner class DomainAdapter(
        private val onClick: (String) -> Unit,
        private val onLongClick: (String) -> Boolean,
        private val onEdit: (String) -> Unit,
        private val onDelete: (String) -> Unit,
    ) : ListAdapter<String, DomainAdapter.DomainHolder>(DomainDiff) {

        var selected: Set<String> = emptySet()

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
            private val editAction = view.findViewById<TextView>(R.id.domain_edit)
            private val deleteAction = view.findViewById<TextView>(R.id.domain_delete)

            fun bind(domain: String) {
                domainText.text = domain
                val isSelected = domain in selected
                editAction.visibility = if (isSelected) View.VISIBLE else View.GONE
                deleteAction.visibility = if (isSelected) View.VISIBLE else View.GONE
                card.isChecked = isSelected
                itemView.setOnClickListener { onClick(domain) }
                itemView.setOnLongClickListener { onLongClick(domain) }
                editAction.setOnClickListener { onEdit(domain) }
                deleteAction.setOnClickListener { onDelete(domain) }
            }
        }
    }

    private object DomainDiff : DiffUtil.ItemCallback<String>() {
        override fun areItemsTheSame(oldItem: String, newItem: String) = oldItem == newItem
        override fun areContentsTheSame(oldItem: String, newItem: String) = oldItem == newItem
    }
}
