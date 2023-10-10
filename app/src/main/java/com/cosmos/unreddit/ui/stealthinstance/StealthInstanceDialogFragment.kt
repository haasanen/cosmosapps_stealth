package com.cosmos.unreddit.ui.stealthinstance

import android.app.Dialog
import android.content.DialogInterface
import android.content.DialogInterface.OnShowListener
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.setFragmentResult
import com.cosmos.unreddit.R
import com.cosmos.unreddit.databinding.FragmentStealthInstanceBinding
import com.cosmos.unreddit.util.LinkValidator
import com.cosmos.unreddit.util.extension.doAndDismiss
import com.cosmos.unreddit.util.extension.serializable
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class StealthInstanceDialogFragment : DialogFragment(), OnShowListener {

    private var _binding: FragmentStealthInstanceBinding? = null
    private val binding get() = _binding!!

    private var instance: String? = null
    private lateinit var instances: List<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.run {
            instance = serializable(KEY_INSTANCE)
            instances = getStringArrayList(KEY_INSTANCES) ?: emptyList()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = FragmentStealthInstanceBinding.inflate(requireActivity().layoutInflater)

        initSpinner()

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_stealth_instance_title)
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
                setOnShowListener(this@StealthInstanceDialogFragment)
            }
    }

    private fun initSpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_list_item_1, instances)

        val savedInstance = instance.orEmpty().ifEmpty { adapter.getItem(0) }

        binding.textListInstances.run {
            setAdapter(adapter)
            setText(savedInstance, false)
        }
    }

    private fun save() {
        val instance = binding.textListInstances.text.toString()
        val linkValidator = LinkValidator(instance)

        val errorMessage: String? = when {
            instance.isBlank() -> getString(R.string.instance_empty_error)
            !linkValidator.isValid -> getString(R.string.instance_invalid_error)
            else -> null
        }

        if (errorMessage != null) {
            binding.listInstances.error = errorMessage
            return
        }

        val url = linkValidator.validUrl?.run {
            buildString {
                append(host)
                if (port != 80 && port != 443) {
                    // Append any non-http or non-https port
                    append(":")
                    append(port)
                }
            }
        }

        doAndDismiss {
            setFragmentResult(
                REQUEST_KEY_STEALTH_INSTANCE,
                bundleOf(
                    KEY_INSTANCE to url
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
        private const val TAG = "StealthInstanceDialogFragment"

        const val REQUEST_KEY_STEALTH_INSTANCE = "REQUEST_KEY_STEALTH_INSTANCE"

        const val KEY_INSTANCE = "KEY_INSTANCE"
        private const val KEY_INSTANCES = "KEY_INSTANCES"

        fun show(
            fragmentManager: FragmentManager,
            instance: String?,
            instances: List<String>
        ) {
            StealthInstanceDialogFragment().apply {
                arguments = bundleOf(
                    KEY_INSTANCE to instance,
                    KEY_INSTANCES to ArrayList(instances)
                )
            }.show(fragmentManager, TAG)
        }
    }
}
