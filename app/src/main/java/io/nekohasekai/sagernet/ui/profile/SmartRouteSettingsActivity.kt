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
import android.text.InputType
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.fmt.internal.ConfigBean
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.startFilesForResult
import io.nekohasekai.sagernet.ui.ThemedActivity
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteConfigGenerator
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteDomainNormalizer
import io.nekohasekai.sagernet.ui.profile.smartroute.SmartRouteInputException
import java.io.OutputStreamWriter

class SmartRouteSettingsActivity : ThemedActivity(R.layout.layout_smart_route_settings) {

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
    private val domainItems = ArrayList<String>()

    private lateinit var profileName: TextInputEditText
    private lateinit var domainInput: TextInputEditText
    private lateinit var domainCount: TextView
    private lateinit var domainListView: LinearLayout

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        val text = readText(uri) ?: return@registerForActivityResult
        when (importTarget) {
            ImportTarget.DefaultConf -> {
                defaultConf = text
                showMessage(R.string.smart_route_default_loaded)
            }
            ImportTarget.BypassConf -> {
                bypassConf = text
                showMessage(R.string.smart_route_bypass_loaded)
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
    }

    private val exportDomains = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.smart_route)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        profileName = findViewById(R.id.profile_name)
        domainInput = findViewById(R.id.domain_input)
        domainCount = findViewById(R.id.domain_count)
        domainListView = findViewById(R.id.domain_checked_list)
        profileName.setText(getString(R.string.smart_route_default_profile_name))
        setDomains(emptyList())
        loadManagedSmartRoute()

        findViewById<Button>(R.id.default_from_file).setOnClickListener {
            importTarget = ImportTarget.DefaultConf
            startFilesForResult(importFile, "*/*")
        }
        findViewById<Button>(R.id.bypass_from_file).setOnClickListener {
            importTarget = ImportTarget.BypassConf
            startFilesForResult(importFile, "*/*")
        }
        findViewById<Button>(R.id.domains_from_file).setOnClickListener {
            importTarget = ImportTarget.Domains
            startFilesForResult(importFile, "text/*")
        }
        findViewById<Button>(R.id.domains_export_file).setOnClickListener {
            exportDomains.launch("smart-route-domains.txt")
        }

        findViewById<Button>(R.id.default_from_clipboard).setOnClickListener { pasteConf(default = true) }
        findViewById<Button>(R.id.bypass_from_clipboard).setOnClickListener { pasteConf(default = false) }
        findViewById<Button>(R.id.default_edit).setOnClickListener {
            editTextDialog(R.string.smart_route_default_conf, defaultConf) {
                defaultConf = it
                generatedJson = ""
            }
        }
        findViewById<Button>(R.id.bypass_edit).setOnClickListener {
            editTextDialog(R.string.smart_route_bypass_conf, bypassConf) {
                bypassConf = it
                generatedJson = ""
            }
        }
        findViewById<Button>(R.id.default_clear).setOnClickListener {
            defaultConf = ""
            generatedJson = ""
            showMessage(R.string.smart_route_cleared)
        }
        findViewById<Button>(R.id.bypass_clear).setOnClickListener {
            bypassConf = ""
            generatedJson = ""
            showMessage(R.string.smart_route_cleared)
        }
        findViewById<Button>(R.id.domain_add).setOnClickListener { addDomainsFromInput() }
        findViewById<Button>(R.id.domains_remove_checked).setOnClickListener { removeCheckedDomains() }
        findViewById<Button>(R.id.domains_advanced_edit).setOnClickListener { editDomainsAdvanced() }
        findViewById<Button>(R.id.domains_normalize).setOnClickListener { normalizeDomains(sort = false) }
        findViewById<Button>(R.id.domains_sort).setOnClickListener { normalizeDomains(sort = true) }
        findViewById<Button>(R.id.domains_clear).setOnClickListener {
            setDomains(emptyList())
            generatedJson = ""
        }
        findViewById<Button>(R.id.validate_config).setOnClickListener {
            generateOrShow(updateDomainText = true)?.let { result ->
                val warning = result.warnings.distinct().joinToString("\n") { getString(it) }
                showInfo(
                    getString(R.string.smart_route_validate),
                    listOf(getString(R.string.smart_route_validation_ok), warning).filter { it.isNotBlank() }.joinToString("\n\n"),
                )
            }
        }
        findViewById<Button>(R.id.preview_json).setOnClickListener {
            generateOrShow(updateDomainText = true)?.let {
                showInfo(getString(R.string.smart_route_preview_json), it.json)
            }
        }
        findViewById<Button>(R.id.copy_json).setOnClickListener {
            val json = generateOrShow(updateDomainText = true)?.json ?: return@setOnClickListener
            SagerNet.trySetPrimaryClip(json)
            showMessage(R.string.smart_route_json_copied)
        }
        findViewById<Button>(R.id.save_profile).setOnClickListener {
            saveProfile()
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(findViewById(R.id.smart_route_root), text, Snackbar.LENGTH_LONG)
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
            showInfo(getString(R.string.smart_route_add_domain), getString(R.string.smart_route_domains_added, added))
        } catch (e: SmartRouteInputException) {
            showError(formatInputError(e))
        }
    }

    private fun removeCheckedDomains() {
        val checked = (0 until domainListView.childCount)
            .filter { index -> (domainListView.getChildAt(index) as? MaterialCheckBox)?.isChecked == true }
            .toSet()
        if (checked.isEmpty()) return
        val remaining = domainItems.filterIndexed { index, _ -> index !in checked }
        setDomains(remaining)
        generatedJson = ""
    }

    private fun setDomains(values: List<String>) {
        domainItems.clear()
        domainItems.addAll(values)
        domainCount.text = getString(R.string.smart_route_checked_domains_count, values.size)
        domainListView.removeAllViews()
        values.forEach { domain ->
            domainListView.addView(MaterialCheckBox(this).apply {
                text = domain
                isSingleLine = false
                setPadding(0, 4, 0, 4)
            })
        }
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
                showMessage(R.string.smart_route_saved)
                setResult(RESULT_OK)
                finish()
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
            setDomains(SmartRouteDomainNormalizer.normalizeLines(savedDomains))
        }
    }

    private fun smartRoutePrefs() = getSharedPreferences("smart_route_profiles", MODE_PRIVATE)

    private fun editDomainsAdvanced() {
        editTextDialog(R.string.smart_route_advanced_domain_edit, domainItems.joinToString("\n")) {
            try {
                setDomains(SmartRouteDomainNormalizer.normalizeLines(it))
                generatedJson = ""
            } catch (e: SmartRouteInputException) {
                showError(formatInputError(e))
            }
        }
    }

    private fun editTextDialog(titleRes: Int, value: String, onSave: (String) -> Unit) {
        val edit = EditText(this).apply {
            setText(value)
            minLines = 10
            maxLines = 20
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(edit)
            .setPositiveButton(android.R.string.ok) { _, _ -> onSave(edit.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showInfo(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showError(message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.error_title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showMessage(resId: Int) {
        snackbar(resId).show()
    }

    private fun formatInputError(error: SmartRouteInputException): String {
        val base = getString(error.stringRes)
        return error.detail?.let { "$base\n$it" } ?: base
    }

    private fun readText(uri: Uri): String? {
        return runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.onFailure {
            showError(getString(R.string.action_import_err))
        }.getOrNull()
    }

}
