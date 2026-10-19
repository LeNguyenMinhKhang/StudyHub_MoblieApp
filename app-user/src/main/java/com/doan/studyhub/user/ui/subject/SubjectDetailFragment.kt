
package com.doan.studyhub.user.ui.subject

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.doan.studyhub.core.data.repository.AuthRepository
import com.doan.studyhub.core.data.repository.SubjectRepository
import com.doan.studyhub.user.R
import kotlinx.coroutines.launch

class SubjectDetailFragment : Fragment() {

    private val subjectRepository = SubjectRepository()
    private val authRepository = AuthRepository()

    private var subjectId: String = ""
    private var isFollowed = false

    private lateinit var nameText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var followButton: Button

    companion object {
        private const val ARG_SUBJECT_ID = "subject_id"

        fun newInstance(subjectId: String) =
            SubjectDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SUBJECT_ID, subjectId)
                }
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(
            R.layout.fragment_subject_detail,
            container,
            false
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        nameText = view.findViewById(R.id.txtSubjectDetailName)
        descriptionText = view.findViewById(R.id.txtSubjectDetailDescription)
        progress = view.findViewById(R.id.progressSubjectDetail)
        errorText = view.findViewById(R.id.txtSubjectDetailError)
        followButton = view.findViewById(R.id.btnFollowSubjectDetail)

        subjectId = arguments?.getString(ARG_SUBJECT_ID).orEmpty()

        view.findViewById<Button>(R.id.btnBackSubjectDetail)
            .setOnClickListener {
                parentFragmentManager.popBackStack()
            }

        followButton.setOnClickListener {
            toggleFollow()
        }

        if (subjectId.isBlank()) {
            showError("Không tìm thấy mã môn học.")
        } else {
            loadSubjectDetail()
        }
    }

    private fun loadSubjectDetail() {
        viewLifecycleOwner.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            errorText.visibility = View.GONE
            followButton.isEnabled = false

            try {
                val subject = subjectRepository.getSubjectById(subjectId)

                if (subject == null) {
                    showError("Môn học không tồn tại hoặc đã ngừng hoạt động.")
                    return@launch
                }

                nameText.text = subject.name
                descriptionText.text =
                    subject.description?.takeIf { it.isNotBlank() }
                        ?: "Môn học chưa có mô tả."

                val userId = authRepository.getCurrentUserId()

                if (userId != null) {
                    isFollowed =
                        subjectRepository.getFollowedSubjectIds(userId)
                            .contains(subjectId)
                }

                updateFollowButton()
            } catch (e: Exception) {
                showError("Không thể tải chi tiết môn học: ${e.message.orEmpty()}")
            } finally {
                progress.visibility = View.GONE
                followButton.isEnabled = true
            }
        }
    }

    private fun toggleFollow() {
        viewLifecycleOwner.lifecycleScope.launch {
            val userId = authRepository.getCurrentUserId()

            if (userId == null) {
                Toast.makeText(
                    requireContext(),
                    "Bạn cần đăng nhập để theo dõi môn học.",
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }

            followButton.isEnabled = false

            try {
                if (isFollowed) {
                    subjectRepository.unfollowSubject(userId, subjectId)
                    isFollowed = false
                } else {
                    subjectRepository.followSubject(userId, subjectId)
                    isFollowed = true
                }

                updateFollowButton()
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    "Thao tác thất bại: ${e.message.orEmpty()}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                followButton.isEnabled = true
            }
        }
    }

    private fun updateFollowButton() {
        followButton.text =
            if (isFollowed) "Bỏ theo dõi" else "Theo dõi"
    }

    private fun showError(message: String) {
        errorText.text = message
        errorText.visibility = View.VISIBLE
    }
}
