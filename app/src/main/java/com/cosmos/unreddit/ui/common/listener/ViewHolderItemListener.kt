package com.cosmos.unreddit.ui.common.listener

interface ViewHolderItemListener {
    fun onClick(position: Int, isLong: Boolean = false)

    fun onMediaClick(position: Int)

    fun onMenuClick(position: Int)

    fun onSaveClick(position: Int)
}
