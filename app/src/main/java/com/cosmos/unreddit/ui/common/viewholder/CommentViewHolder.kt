package com.cosmos.unreddit.ui.common.viewholder

import android.content.res.ColorStateList
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import com.cosmos.unreddit.R
import com.cosmos.unreddit.data.model.db.CommentItem
import com.cosmos.unreddit.databinding.ItemUserCommentBinding
import com.cosmos.unreddit.ui.common.widget.RedditView
import com.cosmos.unreddit.ui.user.UserCommentsAdapter
import com.cosmos.unreddit.util.DateUtil
import com.cosmos.unreddit.util.extension.formatNumber

class CommentViewHolder(
    private val binding: ItemUserCommentBinding,
    private val colorPrimary: ColorStateList,
    private val commentClickListener: UserCommentsAdapter.CommentClickListener,
    private val onLinkClickListener: RedditView.OnLinkClickListener? = null
) : RecyclerView.ViewHolder(binding.root) {

    fun bind(comment: CommentItem) {
        binding.comment = comment
        binding.includeItemComment.comment = comment

        with(comment) {
            binding.includeItemComment.commentScore.text = itemView.context.getString(
                R.string.comment_score,
                comment.score.formatNumber()
            )

            binding.includeItemComment.commentDate.run {
                val timeDifference = DateUtil.getTimeDifference(context, created)
                text = if (edited != null) {
                    val editedTimeDifference = DateUtil.getTimeDifference(
                        context,
                        edited,
                        false
                    )
                    context.getString(
                        R.string.comment_date_edited,
                        timeDifference,
                        editedTimeDifference
                    )
                } else {
                    timeDifference
                }
            }

            binding.includeItemComment.commentColorIndicator.run {
                visibility = View.VISIBLE
                backgroundTintList = colorPrimary
            }

            binding.includeItemComment.commentBadge.run {
                if (authorBadge != null) {
                    visibility = View.VISIBLE

                    setBadge(authorBadge)
                } else {
                    visibility = View.GONE
                }
            }

            binding.includeItemComment.commentReactions.run {
                if (reactions != null) {
                    visibility = View.VISIBLE

                    setReactions(reactions)
                } else {
                    visibility = View.GONE
                }
            }

        }

        binding.includeItemComment.commentBody.run {
            setText(comment.bodyText)
            setOnLinkClickListener(onLinkClickListener)
            setOnClickListener { commentClickListener.onClick(comment) }
            setOnLongClickListener {
                commentClickListener.onLongClick(comment)
                true
            }
        }

        itemView.run {
            setOnClickListener { commentClickListener.onClick(comment) }
            setOnLongClickListener {
                commentClickListener.onLongClick(comment)
                true
            }
        }
    }
}
