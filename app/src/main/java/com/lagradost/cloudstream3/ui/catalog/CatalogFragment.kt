package com.lagradost.cloudstream3.ui.catalog

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.core.view.isVisible
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.databinding.FragmentCatalogBinding
import com.lagradost.cloudstream3.ui.BaseFragment
import com.lagradost.cloudstream3.ui.settings.SettingsProvidersScreen.toStringRes
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream3.utils.UIHelper.fixSystemBarsPadding

class CatalogFragment : BaseFragment<FragmentCatalogBinding>(BaseFragment.BindingCreator.Inflate(FragmentCatalogBinding::inflate)) {
    private val model: CatalogViewModel by viewModels()
    override fun fixLayout(view: View) {
        fixSystemBarsPadding(view)
        // Keep retry/apply/done reachable on narrow phone screens as well as with a TV remote.
        view.findViewById<android.widget.LinearLayout>(R.id.catalog_actions)?.orientation =
            if (resources.configuration.screenWidthDp < 600) android.widget.LinearLayout.VERTICAL
            else android.widget.LinearLayout.HORIZONTAL
    }
    override fun onBindingCreated(binding: FragmentCatalogBinding) {
        val ctx = requireContext()
        model.initialize(ctx)
        var rendered: CatalogInventory? = null
        fun count() {
            val selected = model.selection ?: return
            val size = selected.plan(model.state.value?.inventory?.plugins.orEmpty(),
                com.lagradost.cloudstream3.plugins.PluginManager.getPluginsOnline().mapNotNull { it.url }.toSet()).size
            binding.catalogApply.text = getString(R.string.catalog_apply, size)
            binding.catalogApply.isEnabled = model.state.value?.loading == false && model.state.value?.busy == false &&
                model.state.value?.inventory != null && selected.languages.isNotEmpty() && selected.types.isNotEmpty()
        }
        fun renderChoices(inventory: CatalogInventory?) {
            binding.catalogChoices.removeAllViews()
            val selected = model.selection ?: return
            fun heading(label: Int) { binding.catalogChoices.addView(TextView(ctx).apply {
                text = getString(label); textSize = 20f; setPadding(12, 24, 12, 8)
            }) }
            binding.catalogChoices.addView(AppCompatCheckBox(ctx).apply {
                tag = "catalog_recommended_only"
                text = getString(R.string.catalog_recommended)
                isChecked = selected.recommendedOnly
                setOnCheckedChangeListener { _, checked ->
                    model.selection = model.selection!!.copy(recommendedOnly = checked)
                    count()
                }
            })
            heading(R.string.provider_lang_settings)
            val languages = (listOf("fr", "en") + selected.languages + inventory?.plugins.orEmpty().mapNotNull { it.plugin.language })
                .distinct().filter { it != AllLanguagesName }.sortedWith(compareBy<String> { when(it) { "fr" -> 0; "en" -> 1; else -> 2 } }.thenBy { it }) + AllLanguagesName
            fun languageChoice(language: String) { binding.catalogChoices.addView(AppCompatCheckBox(ctx).apply {
                id = View.generateViewId(); tag = "catalog_language_$language"
                text = if (language == AllLanguagesName) getString(R.string.all_languages_preference)
                    else getNameNextToFlagEmoji(language) ?: language
                isChecked = language in selected.languages
                setOnCheckedChangeListener { _, checked ->
                    model.selection = model.selection!!.let { it.copy(languages = if (checked) it.languages + language else it.languages - language) }
                    count()
                }
            }) }
            val prominent = (listOf("fr", "en") + selected.languages).distinct()
            prominent.forEach(::languageChoice)
            heading(R.string.preferred_media_settings)
            val types = listOf(TvType.Movie, TvType.TvSeries, TvType.Anime) +
                CatalogCuration.videoTypes.filter { it !in setOf(TvType.Movie, TvType.TvSeries, TvType.Anime) }
            types.forEach { type -> binding.catalogChoices.addView(AppCompatCheckBox(ctx).apply {
                id = View.generateViewId(); tag = "catalog_type_${type.name}"
                text = getString(when (type) {
                    TvType.AnimeMovie -> R.string.catalog_animated_movies
                    else -> type.toStringRes()
                })
                isChecked = type in selected.types
                setOnCheckedChangeListener { _, checked ->
                    model.selection = model.selection!!.let { it.copy(types = if (checked) it.types + type else it.types - type) }
                    count()
                }
            }) }
            heading(R.string.catalog_other_languages)
            languages.filter { it !in prominent }.forEach(::languageChoice)
        }
        renderChoices(null)
        binding.catalogApply.setOnClickListener { model.install(requireActivity()) }
        binding.catalogRetry.setOnClickListener { model.refresh() }
        binding.catalogClose.setOnClickListener {
            if (arguments?.getBoolean("isSetup") == true) findNavController().navigate(R.id.navigation_setup_layout)
            else findNavController().popBackStack()
        }
        model.state.observe(viewLifecycleOwner) { state ->
            if (state.inventory !== rendered) { rendered = state.inventory; renderChoices(state.inventory) }
            binding.catalogProgress.isVisible = state.loading || state.busy
            binding.catalogRetry.isVisible = state.error || (!state.loading && state.inventory?.failedRepositories?.isNotEmpty() == true)
            binding.catalogClose.isEnabled = !state.busy
            binding.catalogClose.text = getString(if (arguments?.getBoolean("isSetup") == true) R.string.next else R.string.catalog_close)
            for (index in 0 until binding.catalogChoices.childCount) binding.catalogChoices.getChildAt(index).isEnabled = !state.busy
            binding.catalogStatus.text = when {
                state.loading -> getString(R.string.catalog_loading)
                state.busy -> getString(R.string.catalog_installing, state.completed, state.total, state.current)
                state.error -> getString(R.string.catalog_error)
                state.result != null -> getString(R.string.catalog_done, state.result.installed, state.result.unchanged,
                    state.result.failed.size) + if (state.result.failed.isEmpty()) "" else "\n" + state.result.failed.joinToString(", ")
                state.inventory?.failedRepositories?.isNotEmpty() == true -> getString(R.string.catalog_partial, state.inventory.failedRepositories.size)
                else -> getString(R.string.catalog_ready)
            }
            count()
        }
    }
    companion object { fun arguments(isSetup: Boolean) = Bundle().apply { putBoolean("isSetup", isSetup) } }
}
