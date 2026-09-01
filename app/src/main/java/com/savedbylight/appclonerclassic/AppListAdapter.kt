package com.savedbylight.appclonerclassic

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppListAdapter(
    private var apps: List<InstalledApp>,
    private val onClick: (InstalledApp) -> Unit
) : RecyclerView.Adapter<AppListAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val pkg: TextView = view.findViewById(R.id.appPackage)
        val cloneInfo: TextView = view.findViewById(R.id.cloneInfo)
    }

    fun updateData(newApps: List<InstalledApp>) {
        apps = newApps
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val app = apps[position]
        holder.name.text = app.label
        holder.pkg.text = app.packageName
        holder.icon.setImageDrawable(app.icon)

        if (app.existingCloneCount > 0) {
            holder.cloneInfo.visibility = View.VISIBLE
            holder.cloneInfo.text = if (app.existingCloneCount == 1) {
                "1 clone already installed"
            } else {
                "${app.existingCloneCount} clones already installed"
            }
        } else {
            holder.cloneInfo.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onClick(app) }
    }

    override fun getItemCount() = apps.size
}
