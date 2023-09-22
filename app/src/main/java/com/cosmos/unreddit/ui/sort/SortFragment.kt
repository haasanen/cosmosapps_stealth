package com.cosmos.unreddit.ui.sort

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import androidx.transition.Slide
import androidx.transition.TransitionManager
import com.cosmos.stealth.sdk.data.model.api.Order
import com.cosmos.stealth.sdk.data.model.api.Sort
import com.cosmos.stealth.sdk.data.model.api.Time
import com.cosmos.unreddit.data.model.Filtering
import com.cosmos.unreddit.data.model.Sorting
import com.cosmos.unreddit.databinding.FragmentSortBinding
import com.cosmos.unreddit.util.extension.parcelable
import com.cosmos.unreddit.util.extension.serializable
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip

class SortFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentSortBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSortBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initChoices()
    }

    private fun initChoices() {
        val type = arguments?.serializable(BUNDLE_KEY_TYPE) ?: SortType.GENERAL
        binding.type = type

        val filtering = arguments?.parcelable<Filtering>(BUNDLE_KEY_SORTING) ?: return
        with(filtering) {
            when (sort) {
                Sort.trending -> binding.chipTrending.isChecked = true
                Sort.date -> {
                    if (order == Order.asc) {
                        binding.chipOld.isChecked = true
                    } else {
                        binding.chipNew.isChecked = true
                    }
                }
                Sort.score -> {
                    if (order == Order.asc) {
                        binding.chipControversial.isChecked = true
                    } else {
                        binding.chipTop.isChecked = true
                    }

                    if (type != SortType.POST) {
                        showTimeGroup()
                    }
                }
                Sort.comments -> {
                    binding.chipComments.isChecked = true
                    showTimeGroup()
                }
                Sort.relevance -> {
                    binding.chipRelevance.isChecked = true
                    showTimeGroup()
                }
                null -> error("Sort cannot be null")
            }

            when (time) {
                Time.hour -> binding.chipHour.isChecked = true
                Time.day -> binding.chipDay.isChecked = true
                Time.week -> binding.chipWeek.isChecked = true
                Time.month -> binding.chipMonth.isChecked = true
                Time.year -> binding.chipYear.isChecked = true
                Time.all -> binding.chipAll.isChecked = true
                else -> {
                    // Ignore
                }
            }
        }

        binding.groupGeneral.setOnCheckedStateChangeListener { _, checkedIds ->
            val checkedId = checkedIds.getOrNull(0) ?: return@setOnCheckedStateChangeListener
            when (checkedId) {
                binding.chipTrending.id, binding.chipNew.id, binding.chipOld.id -> setChoice(false)
                binding.chipTop.id, binding.chipControversial.id, binding.chipRelevance.id,
                binding.chipComments.id -> {
                    if (type != SortType.POST) {
                        binding.groupTime.clearCheck()
                        showTimeGroup()
                    } else {
                        setChoice(false)
                    }
                }
                else -> {
                    // Ignore
                }
            }
        }

        binding.groupTime.setOnCheckedStateChangeListener { group, checkedIds ->
            val checkedId = checkedIds.getOrNull(0) ?: return@setOnCheckedStateChangeListener
            if (group.findViewById<Chip?>(checkedId)?.isChecked == true) {
                setChoice(true)
            }
        }
    }

    private fun getGeneralChoice(): Filtering? {
        return when (binding.groupGeneral.checkedChipId) {
            binding.chipTrending.id -> Filtering(Sort.trending)
            binding.chipNew.id -> Filtering(Sort.date, Order.desc)
            binding.chipTop.id -> Filtering(Sort.score, Order.desc)
            binding.chipControversial.id -> Filtering(Sort.score, Order.asc)
            binding.chipRelevance.id -> Filtering(Sort.relevance)
            binding.chipComments.id -> Filtering(Sort.comments)
            binding.chipOld.id -> Filtering(Sort.date, Order.asc)
            else -> null
        }
    }

    private fun getTimeChoice(): Time? {
        return when (binding.groupTime.checkedChipId) {
            binding.chipHour.id -> Time.hour
            binding.chipDay.id -> Time.day
            binding.chipWeek.id -> Time.week
            binding.chipMonth.id -> Time.month
            binding.chipYear.id -> Time.year
            binding.chipAll.id -> Time.all
            else -> null
        }
    }

    private fun showTimeGroup() {
        val transition = Slide().apply {
            duration = 250
            addTarget(binding.textTimeLabel)
            addTarget(binding.groupTime)
        }
        TransitionManager.beginDelayedTransition(binding.layoutRoot, transition)
        binding.groupViewTime.visibility = View.VISIBLE
        binding.textTimeLabel.visibility = View.VISIBLE
        binding.groupTime.visibility = View.VISIBLE
    }

    private fun setChoice(withTime: Boolean) {
        val filtering = getGeneralChoice() ?: return
        val timeSorting = if (withTime) getTimeChoice() else null

        setFragmentResult(
            REQUEST_KEY_SORTING,
            bundleOf(BUNDLE_KEY_SORTING to filtering.copy(time = timeSorting))
        )

        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    enum class SortType {
        GENERAL, SEARCH, POST
    }

    companion object {
        private const val TAG = "SortFragment"

        const val REQUEST_KEY_SORTING = "REQUEST_KEY_SORTING"

        const val BUNDLE_KEY_SORTING = "BUNDLE_KEY_SORTING"
        const val BUNDLE_KEY_TYPE = "BUNDLE_KEY_TYPE"

        @Deprecated("Legacy function")
        fun show(
            fragmentManager: FragmentManager,
            sorting: Sorting,
            type: SortType = SortType.GENERAL
        ) {
            SortFragment().apply {
                arguments = bundleOf(
                    BUNDLE_KEY_SORTING to sorting,
                    BUNDLE_KEY_TYPE to type
                )
            }.show(fragmentManager, TAG)
        }

        fun show(
            fragmentManager: FragmentManager,
            filtering: Filtering,
            type: SortType = SortType.GENERAL
        ) {
            SortFragment().apply {
                arguments = bundleOf(
                    BUNDLE_KEY_SORTING to filtering,
                    BUNDLE_KEY_TYPE to type
                )
            }.show(fragmentManager, TAG)
        }
    }
}
