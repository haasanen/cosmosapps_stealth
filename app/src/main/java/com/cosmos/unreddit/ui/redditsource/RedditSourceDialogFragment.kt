package com.cosmos.unreddit.ui.redditsource

import android.app.Dialog
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.preferences.DataPreferences
import com.cosmos.unreddit.databinding.FragmentRedditSourceBinding
import com.cosmos.unreddit.util.extension.doAndDismiss
import com.cosmos.unreddit.util.extension.serializable
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class RedditSourceDialogFragment : DialogFragment(), OnShowListener {

    private var _binding: FragmentRedditSourceBinding? = null
    private val binding get() = _binding!!

    private lateinit var source: DataPreferences.RedditSource

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.run {
            source = serializable(KEY_SOURCE) ?: DataPreferences.RedditSource.ARCTIC
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = FragmentRedditSourceBinding.inflate(requireActivity().layoutInflater)

        initView()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_reddit_source_title)
            .setView(binding.root)
            .setPositiveButton(R.string.save) { _, _ ->
                // Ignore
            }
            .setNeutralButton(R.string.dialog_cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .create()
            .apply {
                setOnShowListener(this@RedditSourceDialogFragment)
            }
    }

    private fun initView() {
        binding.run {
            // Legacy stored values (REDDIT, TEDDIT, REDDIT_SCRAP) are no longer selectable;
            // fall back to ARCTIC so they never remain silently selected.
            radioArctic.isChecked = source == DataPreferences.RedditSource.ARCTIC
            radioRedditOfficial.isChecked = source == DataPreferences.RedditSource.REDDIT_OFFICIAL
            radioRedditAtom.isChecked = source == DataPreferences.RedditSource.REDDIT_ATOM
        }
    }

    private fun save() {
        val source = when (binding.radioGroup.checkedRadioButtonId) {
            R.id.radio_arctic -> DataPreferences.RedditSource.ARCTIC
            R.id.radio_reddit_official -> DataPreferences.RedditSource.REDDIT_OFFICIAL
            R.id.radio_reddit_atom -> DataPreferences.RedditSource.REDDIT_ATOM
            else -> DataPreferences.RedditSource.ARCTIC
        }

        doAndDismiss {
            setFragmentResult(
                REQUEST_KEY_SOURCE,
                bundleOf(
                    KEY_SOURCE to source,
                    KEY_INSTANCE to ""
                )
            )
        }
    }

    override fun onShow(dialog: DialogInterface?) {
        (dialog as AlertDialog?)
            ?.getButton(DialogInterface.BUTTON_POSITIVE)
            ?.setOnClickListener {
                save()
            }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val TAG = "RedditSourceDialogFragment"

        const val REQUEST_KEY_SOURCE = "REQUEST_KEY_SOURCE"

        const val KEY_SOURCE = "KEY_SOURCE"
        const val KEY_INSTANCE = "KEY_INSTANCE"

        fun show(
            fragmentManager: FragmentManager,
            source: DataPreferences.RedditSource
        ) {
            RedditSourceDialogFragment().apply {
                arguments = bundleOf(
                    KEY_SOURCE to source
                )
            }.show(fragmentManager, TAG)
        }
    }
}
