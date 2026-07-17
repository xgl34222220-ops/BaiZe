package io.github.xgl34222220.baize

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import io.github.xgl34222220.baize.databinding.ItemCandidateBinding

class CandidateAdapter(
    private val onSelectionChanged: (ScanCandidate, Boolean) -> Unit,
    private val onWhitelist: (ScanCandidate) -> Unit
) : RecyclerView.Adapter<CandidateAdapter.ViewHolder>() {
    private val items = mutableListOf<ScanCandidate>()
    private var interactionEnabled = true

    fun submitPage(newItems: List<ScanCandidate>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun setInteractionEnabled(enabled: Boolean) {
        if (interactionEnabled == enabled) return
        interactionEnabled = enabled
        notifyDataSetChanged()
    }

    fun markPackageWhitelisted(packageName: String) {
        items.indices.forEach { index ->
            val item = items[index]
            if (item.packageName == packageName && !item.whitelisted) {
                items[index] = item.copy(whitelisted = true, selected = false)
                notifyItemChanged(index)
            }
        }
    }

    fun currentSelectedCount(): Int = items.count { it.selected && !it.whitelisted }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemCandidateBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemCandidateBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ScanCandidate) {
            binding.selectCheck.setOnCheckedChangeListener(null)
            binding.selectCheck.isEnabled = interactionEnabled && !item.whitelisted
            binding.selectCheck.isChecked = item.selected && !item.whitelisted
            binding.selectCheck.text = item.appName

            binding.packageText.text = buildString {
                append(item.packageName)
                append(" · ")
                append(item.categoryLabel)
                if (item.userId != 0) append(" · 用户 ${item.userId}")
            }
            binding.pathText.text = item.path
            binding.detailText.text = buildString {
                when {
                    !item.measured -> append("尚未统计")
                    item.complete -> {
                        append(Formatter.formatFileSize(binding.root.context, item.bytes))
                        append(" · ${item.files} 个文件")
                    }
                    else -> {
                        append("已统计至少 ")
                        append(Formatter.formatFileSize(binding.root.context, item.bytes))
                        append(" · ${item.files} 个文件 · 达到时间预算")
                    }
                }
                if (!item.readable) append(" · 部分内容不可读")
                if (item.whitelisted) append(" · 已加入白名单")
            }
            binding.whitelistButton.isEnabled = interactionEnabled && !item.whitelisted
            binding.whitelistButton.text = if (item.whitelisted) "已白名单" else "加入白名单"

            binding.selectCheck.setOnCheckedChangeListener { _, checked ->
                if (!interactionEnabled) return@setOnCheckedChangeListener
                val index = bindingAdapterPosition
                if (index == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                val current = items[index]
                items[index] = current.copy(selected = checked)
                onSelectionChanged(items[index], checked)
            }
            binding.whitelistButton.setOnClickListener {
                if (interactionEnabled) onWhitelist(item)
            }
        }
    }
}
