package com.doan.studyhub.user.ui.subject

import com.doan.studyhub.core.data.repository.AuthRepository
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.model.Subject as SubjectModel
import com.doan.studyhub.core.data.repository.SubjectRepository
import com.doan.studyhub.user.R
import kotlinx.coroutines.launch

class Subject : Fragment() {

    private val repository = SubjectRepository()
    private val authRepository = AuthRepository()
    private lateinit var adapter: SubjectAdapter
    private lateinit var searchInput: EditText
    private lateinit var countText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var emptyText: TextView
    private lateinit var retryButton: Button

    private var allSubjects: List<SubjectModel> = emptyList()
    private var followedIds: Set<String> = emptySet()
    private var followActionInProgress = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.fragment_subject,
            container,
            false
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        searchInput = view.findViewById(R.id.edtSearchSubject)
        countText = view.findViewById(R.id.txtSubjectCount)
        progress = view.findViewById(R.id.progressSubjects)
        errorText = view.findViewById(R.id.txtSubjectError)
        emptyText = view.findViewById(R.id.txtSubjectEmpty)
        retryButton = view.findViewById(R.id.btnRetrySubjects)

        val recyclerView =
            view.findViewById<RecyclerView>(R.id.rvSubjects)

        adapter = SubjectAdapter(
            onSubjectClick = { subject ->
                parentFragmentManager.beginTransaction()
                    .replace(
                        R.id.containerMain,
                        SubjectDetailFragment.newInstance(subject.id)
                    )
                    .addToBackStack(null)
                    .commit()
            },
            onFollowClick = { subject ->
                toggleFollow(subject)
            }
        )

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter

        searchInput.doAfterTextChanged { text ->
            renderSubjects(text?.toString().orEmpty())
        }

        retryButton.setOnClickListener {
            loadSubjects()
        }

        loadSubjects()
    }

    private fun loadSubjects() {
        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            errorText.visibility = View.GONE
            retryButton.visibility = View.GONE
            emptyText.visibility = View.GONE

            try {
                // Tải các môn học đang hoạt động từ Supabase.
                allSubjects = repository.getActiveSubjects()

                // Lấy danh sách môn đã theo dõi của tài khoản hiện tại.
                val userId = getCurrentUserId()

                followedIds = if (userId != null) {
                    repository.getFollowedSubjectIds(userId).toSet()
                } else {
                    emptySet()
                }

                renderSubjects(searchInput.text.toString())

            } catch (e: Exception) {
                errorText.text =
                    "Không tải được môn học: ${e.message ?: "Lỗi kết nối"}"
                errorText.visibility = View.VISIBLE
                retryButton.visibility = View.VISIBLE

                adapter.submitData(emptyList(), followedIds)
                countText.text = "0 môn"
                emptyText.visibility = View.GONE
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private suspend fun getCurrentUserId(): String? {
        return authRepository.getCurrentUserId()
    }

    private fun toggleFollow(subject: SubjectModel) {
        if (followActionInProgress) return

        viewLifecycleOwner.lifecycleScope.launch {
            followActionInProgress = true

            try {
                val userId = getCurrentUserId()

                if (userId.isNullOrBlank()) {
                    Toast.makeText(
                        requireContext(),
                        "Vui lòng đăng nhập để theo dõi môn học",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }

                val isFollowing = subject.id in followedIds

                if (isFollowing) {
                    repository.unfollowSubject(userId, subject.id)
                    followedIds = followedIds - subject.id

                    Toast.makeText(
                        requireContext(),
                        "Đã bỏ theo dõi ${subject.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    repository.followSubject(userId, subject.id)
                    followedIds = followedIds + subject.id

                    Toast.makeText(
                        requireContext(),
                        "Đã theo dõi ${subject.name}",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                // Chỉ cập nhật trạng thái giao diện sau khi thao tác thành công.
                renderSubjects(searchInput.text.toString())

            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Không thể cập nhật theo dõi: ${
                        e.message ?: "Lỗi kết nối"
                    }",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                followActionInProgress = false
            }
        }
    }

    private fun renderSubjects(query: String) {
        val keyword = query.trim()

        val filtered = allSubjects.filter { subject ->
            subject.name.contains(keyword, ignoreCase = true) ||
                    (subject.description ?: "").contains(
                        keyword,
                        ignoreCase = true
                    )
        }

        adapter.submitData(filtered, followedIds)

        countText.text = "${filtered.size} môn"

        emptyText.visibility =
            if (filtered.isEmpty() && errorText.visibility != View.VISIBLE) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }
}
