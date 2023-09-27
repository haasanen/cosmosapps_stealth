package com.cosmos.unreddit.ui.search

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.data.model.Community
import com.cosmos.unreddit.databinding.ItemSearchSubredditBinding
import com.cosmos.unreddit.util.extension.formatNumber
import com.cosmos.unreddit.util.extension.loadSubredditIcon

class SearchCommunityAdapter(
    private val listener: (Community) -> Unit
) : PagingDataAdapter<Community, SearchCommunityAdapter.CommunityViewHolder>(
    COMMUNITY_COMPARATOR
) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommunityViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return CommunityViewHolder(ItemSearchSubredditBinding.inflate(inflater, parent, false))
    }

    override fun onBindViewHolder(holder: CommunityViewHolder, position: Int) {
        val subreddit = getItem(position) ?: return
        holder.bind(subreddit)
    }

    inner class CommunityViewHolder(
        private val binding: ItemSearchSubredditBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(community: Community) {
            // TODO: Add NSFW flair next to name when needed
            binding.community = community

            binding.subredditSubscribers.text = community.members?.formatNumber()

            binding.subredditImage.loadSubredditIcon(community.icon?.source?.url)

            itemView.setOnClickListener { listener(community) }
        }
    }

    companion object {
        private val COMMUNITY_COMPARATOR = object : DiffUtil.ItemCallback<Community>() {
            override fun areItemsTheSame(
                oldItem: Community,
                newItem: Community
            ): Boolean {
                return oldItem.id == newItem.id
            }

            override fun areContentsTheSame(
                oldItem: Community,
                newItem: Community
            ): Boolean {
                return oldItem == newItem
            }
        }
    }
}
