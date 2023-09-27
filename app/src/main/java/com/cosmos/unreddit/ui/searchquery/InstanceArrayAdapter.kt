package com.cosmos.unreddit.ui.searchquery

import android.content.Context
import android.widget.ArrayAdapter
import androidx.annotation.LayoutRes

class InstanceArrayAdapter(
    context: Context,
    @LayoutRes resource: Int
) : ArrayAdapter<String>(context, resource) {

    private val instances: MutableList<String> = mutableListOf()

    override fun getItem(position: Int): String {
        return instances[position]
    }

    override fun getCount(): Int {
        return instances.size
    }

    fun setInstances(instances: List<String>) {
        this.instances.run {
            clear()
            addAll(instances)
        }
        notifyDataSetChanged()
    }
}
