package io.github.xgl34222220.baize

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import io.github.xgl34222220.baize.databinding.ItemProfileCandidateBinding

class ProfileCandidateAdapter(
    private val onSelectionChanged: (ProfileCandidate, Boolean) -> Unit
) : RecyclerView.Adapter<ProfileCandidateAdapter.Holder>() {
    private val items = ArrayList<ProfileCandidate>()
    private var interactionEnabled = true

    fun submitPage(values: List<ProfileCandidate>) {
        items.clear()
        items.addAll(values)
        notifyDataSetChanged()
    }

    fun setInteractionEnabled(enabled: Boolean) {
        interactionEnabled = enabled
        notifyDataSetChanged()
    }

    fun selectedOnPage(): Int = items.count { it.selected }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val binding = ItemProfileCandidateBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return Holder(binding)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class Holder(private val binding: ItemProfileCandidateBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: ProfileCandidate) {
            binding.selectCheck.setOnCheckedChangeListener(null)
            binding.selectCheck.text = item.appName.ifBlank { item.categoryLabel }
            binding.selectCheck.isChecked = item.selected
            binding.selectCheck.isEnabled = item.risk != "critical"
            binding.selectCheck.isClickable = interactionEnabled && item.risk != "critical"
            binding.selectCheck.isFocusable = interactionEnabled && item.risk != "critical"
            binding.selectCheck.alpha = 1f

            binding.categoryText.text = buildString {
                append(item.categoryLabel)
                if (item.packageName.isNotBlank()) append(" · ${item.packageName}")
                if (item.note.isNotBlank()) append(" · ${item.note}")
            }
            binding.pathText.text = item.path
            binding.riskText.text = when (item.risk) {
                "critical" -> "仅审计"
                "high" -> "高风险"
                "medium" -> "中风险"
                else -> "低风险"
            }
            val color = when (item.risk) {
                "critical", "high" -> R.color.baize_error
                "medium" -> R.color.baize_warning
                else -> R.color.baize_success
            }
            binding.riskText.setTextColor(ContextCompat.getColor(binding.root.context, color))
            binding.detailText.text = if (item.measured) {
                buildString {
                    append(Formatter.formatFileSize(binding.root.context, item.bytes.coerceAtLeast(0L)))
                    append(" · ${item.files.coerceAtLeast(0L)} 个文件")
                    append(" · ${item.directories.coerceAtLeast(0L)} 个目录")
                    if (!item.complete) append(" · 统计受限")
                }
            } else {
                "当前页按需统计大小"
            }

            if (interactionEnabled && item.risk != "critical") {
                binding.selectCheck.setOnCheckedChangeListener { _, checked ->
                    item.selected = checked
                    onSelectionChanged(item, checked)
                }
            }
            binding.root.setOnClickListener {
                if (interactionEnabled && item.risk != "critical") {
                    binding.selectCheck.isChecked = !binding.selectCheck.isChecked
                }
            }
        }
    }
}
