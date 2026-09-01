package com.savedbylight.appclonerclassic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class CloneListAdapter(
    private var clones: List<CloneInfo>,
    private val onOpen: (CloneInfo) -> Unit,
    private val onUninstall: (CloneInfo) -> Unit
) : RecyclerView.Adapter<CloneListAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.cloneIcon)
        val name: TextView = view.findViewById(R.id.cloneName)
        val pkg: TextView = view.findViewById(R.id.clonePackage)
        val openBtn: Button = view.findViewById(R.id.openButton)
        val uninstallBtn: Button = view.findViewById(R.id.uninstallButton)
    }

    fun updateData(newClones: List<CloneInfo>) {
        clones = newClones
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_clone, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val clone = clones[position]
        holder.name.text = "${clone.label} (${clone.cloneNumber})"
        holder.pkg.text = clone.packageName
        holder.icon.setImageDrawable(clone.icon)
        holder.openBtn.setOnClickListener { onOpen(clone) }
        holder.uninstallBtn.setOnClickListener { onUninstall(clone) }
    }

    override fun getItemCount() = clones.size
}
