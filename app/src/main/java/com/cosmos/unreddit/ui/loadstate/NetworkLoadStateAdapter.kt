package com.cosmos.unreddit.ui.loadstate

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.paging.LoadState
import androidx.paging.LoadStateAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.R
import com.cosmos.unreddit.databinding.ItemLoadStateBinding

class NetworkLoadStateAdapter(
    private val retry: () -> Unit
) : LoadStateAdapter<NetworkLoadStateAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, loadState: LoadState): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemLoadStateBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, loadState: LoadState) {
        holder.bind(loadState)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind()
    }

    inner class ViewHolder(
        private val binding: ItemLoadStateBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.buttonRetry.setOnClickListener { retry.invoke() }
        }

        fun bind(loadState: LoadState) {
            binding.loadingCradle.isVisible = loadState is LoadState.Loading
            binding.buttonRetry.isVisible = loadState !is LoadState.Loading
            binding.textError.isVisible = loadState !is LoadState.Loading
            if (loadState is LoadState.Error) {
                // Surface the real reason (e.g. "reddit.com returned no posts … title …")
                // instead of the generic retry string, so the user can report exactly what
                // happened.
                val message = loadState.error.message
                binding.textError.text = message?.takeIf { it.isNotBlank() }
                    ?: binding.root.context.getString(R.string.network_retry_message)
            }
        }

        fun unbind() {
            binding.loadingCradle.isVisible = false
        }
    }
}
