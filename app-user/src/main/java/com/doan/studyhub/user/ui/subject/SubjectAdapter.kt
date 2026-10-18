
package com.doan.studyhub.user.ui.subject

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.model.Subject
import com.doan.studyhub.user.R

class SubjectAdapter(
    private val onSubjectClick: (Subject) -> Unit,
    private val onFollowClick: (Subject) -> Unit
) : RecyclerView.Adapter<SubjectAdapter.SubjectViewHolder>() {

    private var subjects: List<Subject> = emptyList()
    private var followedIds: Set<String> = emptySet()

    fun submitData(
        newSubjects: List<Subject>,
        newFollowedIds: Set<String>
    ) {
        subjects = newSubjects
        followedIds = newFollowedIds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): SubjectViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_subject, parent, false)

        return SubjectViewHolder(view)
    }

    override fun onBindViewHolder(
        holder: SubjectViewHolder,
        position: Int
    ) {
        val subject = subjects[position]

        holder.name.text = subject.name
        holder.description.text =
            subject.description ?: "Chưa có mô tả"

        holder.itemView.setOnClickListener {
            onSubjectClick(subject)
        }

        val isFollowed = subject.id in followedIds

        holder.followButton.text = if (isFollowed) {
            "Bỏ theo dõi"
        } else {
            "Theo dõi"
        }

        holder.followButton.setOnClickListener {
            onFollowClick(subject)
        }
    }

    override fun getItemCount(): Int = subjects.size

    class SubjectViewHolder(view: View) :
        RecyclerView.ViewHolder(view) {

        val name: TextView =
            view.findViewById(R.id.txtItemSubjectName)

        val description: TextView =
            view.findViewById(R.id.txtItemSubjectDescription)

        val followButton: Button =
            view.findViewById(R.id.btnFollowSubject)
    }
}
