package com.cosmos.unreddit.ui.loadstate

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.data.model.Resource
import com.cosmos.unreddit.databinding.ItemResourceStateBinding
import com.cosmos.unreddit.R

class ResourceStateAdapter(
    private val retry: () -> Unit
) : RecyclerView.Adapter<ResourceStateAdapter.ViewHolder>() {

    var resource: Resource<Any>? = null
        set(resource) {
            if (field != resource) {
                val oldItem = displayResourceStateAsItem(field)
                val newItem = displayResourceStateAsItem(resource)

                if (oldItem && !newItem) {
                    notifyItemRemoved(0)
                } else if (newItem && !oldItem) {
                    notifyItemInserted(0)
                } else if (oldItem && newItem) {
                    notifyItemChanged(0)
                }

                field = resource
            }
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return ViewHolder(ItemResourceStateBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(resource)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.unbind()
    }

    override fun getItemCount(): Int = if (displayResourceStateAsItem(resource)) 1 else 0

    private fun displayResourceStateAsItem(resource: Resource<Any>?): Boolean {
        return resource != null && (resource !is Resource.Success || isEmpty(resource))
    }

    private fun isEmpty(resource: Resource<Any>?): Boolean {
        return (resource?.dataValue as? List<*>)?.isEmpty() ?: false
    }

    inner class ViewHolder(
        private val binding: ItemResourceStateBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.buttonRetry.setOnClickListener { retry.invoke() }
        }

        fun bind(resource: Resource<Any>?) {
            // A missing post (404) is NOT an error: the post was removed
            // server-side. There is nothing to show and nothing a retry can fix,
            // so the whole state row is hidden instead — an error message about a
            // non-error would mislead the user. Every other error keeps its row
            // and the retry button.
            val is404 = resource is Resource.Error &&
                (resource.code == 404 || resource.message?.contains("Post not found") == true)
            if (is404) {
                binding.loadingCradle.isVisible = false
                binding.textError.isVisible = false
                binding.buttonRetry.isVisible = false
                binding.emptyData.isVisible = false
                binding.textEmptyData.isVisible = false
                return
            }
            binding.loadingCradle.isVisible = resource is Resource.Loading
            binding.buttonRetry.isVisible = resource is Resource.Error
            binding.textError.isVisible = resource is Resource.Error
            if (resource is Resource.Error) {
                binding.textError.text = binding.root.context
                    .getString(R.string.network_retry_message)
            }

            val isEmpty = isEmpty(resource)
            binding.emptyData.isVisible = isEmpty
            binding.textEmptyData.isVisible = isEmpty
        }

        fun unbind() {
            binding.loadingCradle.isVisible = false
        }
    }
}
