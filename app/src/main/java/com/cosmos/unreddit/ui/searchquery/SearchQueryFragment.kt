package com.cosmos.unreddit.ui.searchquery

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.hilt.navigation.fragment.hiltNavGraphViewModels
import androidx.lifecycle.Lifecycle
import com.cosmos.stealth.sdk.data.model.api.ServiceName
import com.cosmos.stealth.sdk.data.model.service.RedditService.Instance.OLD
import com.cosmos.stealth.sdk.data.model.service.RedditService.Instance.REGULAR
import com.cosmos.unreddit.R
import com.cosmos.unreddit.databinding.FragmentSearchQueryBinding
import com.cosmos.unreddit.ui.base.BaseFragment
import com.cosmos.unreddit.ui.sort.SortFragment
import com.cosmos.unreddit.util.LinkValidator
import com.cosmos.unreddit.util.SearchUtil
import com.cosmos.unreddit.util.extension.clearFilteringListener
import com.cosmos.unreddit.util.extension.launchRepeat
import com.cosmos.unreddit.util.extension.setFilteringListener
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SearchQueryFragment : BaseFragment() {

    private var _binding: FragmentSearchQueryBinding? = null
    private val binding get() = _binding!!

    override val viewModel: SearchQueryViewModel by hiltNavGraphViewModels(R.id.search)

    private lateinit var instanceAdapter: InstanceArrayAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSearchQueryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initAppBar()
        initChoices()
        initSpinner()
        bindViewModel()

        binding.inputQuery.editText?.doOnTextChanged { text, _, _, _ ->
            viewModel.query = text.toString()
        }

        binding.listInstances.editText?.doOnTextChanged { text, _, _, _ ->
            viewModel.instance = text.toString()
        }
    }

    override fun onStart() {
        super.onStart()
        setFilteringListener { filtering -> filtering?.let { viewModel.setFiltering(filtering) } }
    }

    override fun onResume() {
        super.onResume()
        binding.inputQuery.editText?.setText(viewModel.query)
    }

    private fun initAppBar() {
        binding.appBar.sortCard.setOnClickListener { showSortDialog() }
    }

    private fun initChoices() {
        binding.groupService.setOnCheckedStateChangeListener { _, checkedIds ->

            viewModel.instance = null

            when (checkedIds.getOrNull(0)) {
                binding.chipReddit.id -> viewModel.setServiceName(ServiceName.reddit)
                binding.chipTeddit.id -> viewModel.setServiceName(ServiceName.teddit)
                binding.chipLemmy.id -> viewModel.setServiceName(ServiceName.lemmy)
                else -> { /* ignore */
                }
            }
        }

        binding.groupRedditSource.setOnCheckedStateChangeListener { _, checkedIds ->
            when (checkedIds.getOrNull(0)) {
                binding.chipOfficial.id -> viewModel.setRedditInstance(REGULAR)
                binding.chipScraping.id -> viewModel.setRedditInstance(OLD)
                else -> { /* ignore */
                }
            }
        }
    }

    private fun initSpinner() {
        instanceAdapter =
            InstanceArrayAdapter(requireContext(), android.R.layout.simple_list_item_1)

        binding.textListInstances.setAdapter(instanceAdapter)
    }

    private fun bindViewModel() {
        launchRepeat(Lifecycle.State.STARTED) {
            launch {
                viewModel.filtering.collect {
                    binding.appBar.sortIcon.setFiltering(it)
                }
            }

            launch {
                viewModel.serviceName.collect {
                    when (it) {
                        ServiceName.reddit -> {
                            binding.run {
                                chipReddit.isChecked = true
                                groupSource.isVisible = true
                                listInstances.isVisible = false
                            }
                        }

                        ServiceName.teddit -> {
                            binding.run {
                                chipTeddit.isChecked = true
                                groupSource.isVisible = false
                                listInstances.isVisible = true
                                setSpinnerInstances(viewModel.tedditInstances)
                            }
                        }

                        ServiceName.lemmy -> {
                            binding.run {
                                chipLemmy.isChecked = true
                                groupSource.isVisible = false
                                listInstances.isVisible = true
                                setSpinnerInstances(viewModel.lemmyInstances)
                            }
                        }
                    }
                }
            }

            launch {
                viewModel.redditInstance.collect {
                    when (it) {
                        REGULAR -> binding.chipOfficial.isChecked = true
                        OLD -> binding.chipScraping.isChecked = true
                    }
                }
            }
        }
    }

    private fun setSpinnerInstances(instances: List<String>) {
        instanceAdapter.setInstances(instances)

        val savedInstance = viewModel.instance.orEmpty().ifEmpty { instanceAdapter.getItem(0) }

        binding.textListInstances.setText(savedInstance)
    }

    fun validate(): Boolean {
        binding.inputQuery.error = null
        binding.listInstances.error = null

        var isQueryValid = true
        if (!SearchUtil.isQueryValid(viewModel.query)) {
            binding.inputQuery.error = getString(R.string.search_query_invalid)
            isQueryValid = false
        }

        var isInstanceValid = true
        if (binding.listInstances.isVisible) {
            val linkValidator = LinkValidator(viewModel.instance.orEmpty())

            val errorMessage = when {
                viewModel.instance.isNullOrBlank() -> getString(R.string.instance_empty_error)
                !linkValidator.isValid -> getString(R.string.instance_invalid_error)
                else -> null
            }

            if (errorMessage != null) {
                binding.listInstances.error = errorMessage
                isInstanceValid = false
            }
        }

        return isQueryValid && isInstanceValid
    }

    private fun showSortDialog() {
        SortFragment.show(
            childFragmentManager,
            viewModel.filtering.value,
            SortFragment.SortType.SEARCH
        )
    }

    private fun clean() {
        viewModel.clean()
        binding.inputQuery.editText?.text = null
        binding.listInstances.editText?.text = null
    }

    override fun onBackPressed() {
        if (viewModel.shouldClean()) {
            clean()
        } else {
            super.onBackPressed()
        }
    }

    override fun onStop() {
        super.onStop()
        clearFilteringListener()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
