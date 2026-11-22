package com.doan.studyhub.admin.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.doan.studyhub.admin.databinding.FragmentDashboardBinding

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = requireNotNull(_binding)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Setup placeholder stats
        binding.tvStatUsers.text = "1,248"
        binding.tvStatPosts.text = "3,890"
        binding.tvStatSubjects.text = "24"
        binding.tvStatReports.text = "5"
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
