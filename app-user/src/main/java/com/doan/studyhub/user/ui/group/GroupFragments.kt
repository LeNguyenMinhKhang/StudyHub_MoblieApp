package com.doan.studyhub.user.ui.group

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.doan.studyhub.core.data.model.GroupRole
import com.doan.studyhub.user.R
import com.doan.studyhub.user.databinding.*
import com.doan.studyhub.user.ui.home.Home
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import com.doan.studyhub.user.ui.chat.InitialsAvatarDrawable

private fun Fragment.home() = parentFragment as? Home
private fun Fragment.confirm(message: String, action: () -> Unit) {
    val lifecycle = viewLifecycleOwner.lifecycle
    val dialog = MaterialAlertDialogBuilder(requireContext())
        .setTitle(R.string.group_confirm).setMessage(message).setNegativeButton(R.string.group_cancel, null)
        .setPositiveButton(R.string.group_confirm) { _, _ ->
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) action()
        }.create()
    val observer = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
    }
    lifecycle.addObserver(observer)
    dialog.setOnDismissListener { lifecycle.removeObserver(observer) }
    dialog.show()
}

private fun actionLabel(action: GroupAction) = when (action) {
    GroupAction.OPEN -> R.string.group_open
    GroupAction.ACCEPT -> R.string.group_accept
    GroupAction.DECLINE -> R.string.group_decline
    GroupAction.INVITE -> R.string.group_invite
    GroupAction.REVOKE -> R.string.group_revoke
    GroupAction.REMOVE -> R.string.group_remove
    GroupAction.TRANSFER -> R.string.group_transfer
}
private fun sectionLabel(section: GroupSection) = when (section) {
    GroupSection.GROUPS -> R.string.group_mine
    GroupSection.INCOMING -> R.string.group_incoming
    GroupSection.MEMBERS -> R.string.group_members
    GroupSection.PENDING -> R.string.group_pending
    GroupSection.FRIENDS -> R.string.group_friends
}

private class GroupAdapter(private val click: (GroupRow, GroupAction) -> Unit) : RecyclerView.Adapter<GroupAdapter.Holder>() {
    private var rows = emptyList<GroupRow>()
    private var busy: String? = null
    private var loading = false
    class Holder(val binding: ItemStudyGroupBinding) : RecyclerView.ViewHolder(binding.root)
    fun submit(state: GroupBrowseState) {
        rows = state.rows; busy = state.busy; loading = state.loading
        notifyDataSetChanged()
    }
    override fun getItemCount() = rows.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(ItemStudyGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        with(holder.binding) {
            title.text = row.title
            subtitle.text = when (row.subtitle) {
                "OWNER" -> root.context.getString(R.string.group_owner)
                "MEMBER" -> root.context.getString(R.string.group_member)
                else -> row.subtitle
            }
            actions.removeAllViews()
            row.actions.forEach { action ->
                actions.addView(Button(root.context).apply {
                    setText(if (busy == row.id) R.string.group_saving else actionLabel(action))
                    contentDescription = context.getString(R.string.group_action_for, context.getString(actionLabel(action)), row.title)
                    isEnabled = busy == null && !loading
                    setOnClickListener { click(row, action) }
                })
            }
        }
    }
}

abstract class GroupBrowserFragment : Fragment() {
    protected abstract val model: GroupBrowserViewModel
    private var binding: FragmentGroupBinding? = null
    private var choices = emptyList<GroupSection>()
    private val groupId get() = arguments?.getString("group_id")
    /** Members opens on MEMBERS; "Add member" opens on FRIENDS. */
    private val initialSection get() = arguments?.getString("section")
        ?.let { name -> GroupSection.values().firstOrNull { it.name == name } } ?: GroupSection.MEMBERS
    private var closing = false
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        closing = false
        return FragmentGroupBinding.inflate(inflater, container, false).also { binding = it }.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = requireNotNull(binding)
        val adapter = GroupAdapter { row, action ->
            val confirmText = when (action) {
                GroupAction.REMOVE -> getString(R.string.group_confirm_remove, row.title)
                GroupAction.TRANSFER -> getString(R.string.group_confirm_transfer, row.title)
                else -> null
            }
            if (confirmText == null) model.act(row, action) else confirm(confirmText) { model.act(row, action) }
        }
        b.rows.layoutManager = LinearLayoutManager(requireContext()); b.rows.adapter = adapter
        b.btnBack.isVisible = groupId != null
        b.btnBack.setOnClickListener { home()?.closeDetail() }
        b.btnCreate.isVisible = groupId == null
        b.btnCreate.setOnClickListener { home()?.openDetail(CreateGroupFragment()) }
        b.refresh.setOnRefreshListener { model.refresh() }
        b.btnRetry.setOnClickListener { model.refresh() }
        b.btnMore.setOnClickListener { model.more() }
        b.section.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                choices.getOrNull(position)?.let { if (model.state.value.section != it) model.show(it, groupId) }
            }
        }
        model.show(if (groupId == null) model.state.value.section else initialSection, groupId)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    val current = binding ?: return@collect
                    val options = if (groupId == null) listOf(GroupSection.GROUPS, GroupSection.INCOMING)
                        else if (state.detail?.role == GroupRole.OWNER) listOf(GroupSection.MEMBERS, GroupSection.PENDING, GroupSection.FRIENDS)
                        else listOf(GroupSection.MEMBERS, GroupSection.FRIENDS)
                    if (choices != options) {
                        choices = options
                        current.section.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options.map { getString(sectionLabel(it)) })
                        current.section.setSelection(options.indexOf(state.section).coerceAtLeast(0))
                    }
                    current.tvTitle.text = state.detail?.name ?: getString(R.string.group_title)
                    current.section.isEnabled = !state.loading && state.busy == null
                    current.refresh.isRefreshing = state.loading
                    current.btnRetry.isVisible = state.error != null || state.actionError != null
                    current.btnRetry.isEnabled = !state.loading && state.busy == null
                    current.btnMore.isVisible = state.more
                    current.btnMore.isEnabled = !state.loading && state.busy == null
                    current.tvStatus.text = state.actionError ?: state.error ?: when {
                        state.loading -> getString(R.string.group_loading)
                        state.loaded && state.rows.isEmpty() -> getString(R.string.group_empty)
                        else -> ""
                    }
                    adapter.submit(state)
                    state.completedAction?.let {
                        model.acknowledged()
                        Toast.makeText(requireContext(), getString(R.string.group_action_done, getString(actionLabel(it))), Toast.LENGTH_SHORT).show()
                    }
                    state.openGroup?.let { id ->
                        model.opened()
                        home()?.openDetail(GroupChatFragment.newInstance(id))
                    }
                    if (state.inaccessible && !closing) {
                        closing = true
                        Toast.makeText(requireContext(), state.error ?: getString(R.string.group_closed), Toast.LENGTH_LONG).show()
                        home()?.closeGroupDetails()
                    }
                }
            }
        }
    }
    override fun onResume() { super.onResume(); model.refresh() }
    override fun onDestroyView() {
        binding?.rows?.adapter = null; binding?.section?.onItemSelectedListener = null
        binding = null; choices = emptyList(); super.onDestroyView()
    }
}

class GroupMembersFragment : GroupBrowserFragment() {
    override val model: GroupMembersViewModel by viewModels()
    companion object {
        fun newInstance(id: String, section: GroupSection = GroupSection.MEMBERS) = GroupMembersFragment().apply {
            arguments = Bundle().apply { putString("group_id", id); putString("section", section.name) }
        }
    }
}

class CreateGroupFragment : Fragment() {
    private val model: CreateGroupViewModel by viewModels()
    private var binding: FragmentCreateGroupBinding? = null
    private var rendering = false
    private var subjectIds = emptyList<String>()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        FragmentCreateGroupBinding.inflate(inflater, container, false).also { binding = it }.root
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = requireNotNull(binding)
        fun edit() {
            if (rendering) return
            val state = model.state.value
            val subject = if (state.loading || subjectIds.isEmpty() || (state.subjectId != null && state.subjectId !in subjectIds)) state.subjectId
                else subjectIds.getOrNull(b.subject.selectedItemPosition - 1)
            model.edit(b.name.text.toString(), b.description.text.toString(), subject)
        }
        b.name.doAfterTextChanged { edit() }; b.description.doAfterTextChanged { edit() }
        b.subject.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!rendering && !model.state.value.loading && position == b.subject.selectedItemPosition) {
                    model.edit(b.name.text.toString(), b.description.text.toString(), subjectIds.getOrNull(position - 1))
                }
            }
        }
        b.btnBack.setOnClickListener { home()?.closeDetail() }
        b.btnSave.setOnClickListener { model.submit() }
        b.btnRetry.setOnClickListener { model.loadSubjects() }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    val current = binding ?: return@collect
                    rendering = true
                    if (current.name.text.toString() != state.name) current.name.setText(state.name)
                    if (current.description.text.toString() != state.description) current.description.setText(state.description)
                    val activeIds = state.subjects.map { it.id }
                    val retainedSubject = state.subjectId?.takeIf { it !in activeIds }
                    val displayIds = activeIds + listOfNotNull(retainedSubject)
                    if (subjectIds != displayIds || current.subject.adapter == null) {
                        subjectIds = displayIds
                        current.subject.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item,
                            listOf(getString(R.string.group_no_subject)) + state.subjects.map { it.name } +
                                if (retainedSubject != null) listOf(getString(R.string.group_previous_subject)) else emptyList())
                    }
                    current.subject.setSelection(subjectIds.indexOf(state.subjectId) + 1)
                    current.name.isEnabled = !state.busy; current.description.isEnabled = !state.busy
                    current.subject.isEnabled = !state.busy && !state.loading
                    current.btnSave.isEnabled = !state.busy
                    current.btnRetry.isEnabled = !state.busy && !state.loading
                    current.status.text = state.error ?: when {
                        state.busy -> getString(R.string.group_saving)
                        state.loading -> getString(R.string.group_loading)
                        else -> ""
                    }
                    rendering = false
                    state.created?.let { id ->
                        model.createdOpened()
                        home()?.let { home -> home.closeDetail(); home.openDetail(GroupChatFragment.newInstance(id)) }
                    }
                }
            }
        }
        model.loadSubjects()
    }
    override fun onDestroyView() { binding?.subject?.onItemSelectedListener = null; binding = null; subjectIds = emptyList(); super.onDestroyView() }
}

/**
 * Group info, opened from the chat's info icon: avatar and summary on top, an owner-only edit form,
 * then icon rows (add member, members, documents, leave / archive). Subject is shown only when set.
 */
class GroupDetailFragment : Fragment() {
    private val model: GroupDetailViewModel by viewModels()
    private var binding: FragmentGroupDetailBinding? = null
    private var rendering = false
    private var closing = false
    private val id get() = requireNotNull(requireArguments().getString("group_id"))
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        closing = false
        return FragmentGroupDetailBinding.inflate(inflater, container, false).also { binding = it }.root
    }
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = requireNotNull(binding)
        fun edit() {
            if (!rendering) model.edit(b.name.text.toString(), b.description.text.toString())
        }
        b.name.doAfterTextChanged { edit() }
        b.description.doAfterTextChanged { edit() }
        b.btnBack.setOnClickListener { home()?.closeDetail() }
        b.btnRetry.setOnClickListener { model.refresh() }
        b.btnAddMember.setOnClickListener { home()?.openDetail(GroupMembersFragment.newInstance(id, GroupSection.FRIENDS)) }
        b.btnMembers.setOnClickListener { home()?.openDetail(GroupMembersFragment.newInstance(id)) }
        b.btnDocuments.setOnClickListener {
            home()?.openDetail(com.doan.studyhub.user.ui.group.document.DocumentLibraryFragment.newInstance(id))
        }
        b.btnSave.setOnClickListener { model.save(b.name.text.toString(), b.description.text.toString()) }
        b.btnLeave.setOnClickListener { confirm(getString(R.string.group_confirm_leave, model.state.value.detail?.name.orEmpty())) { model.leave() } }
        b.btnArchive.setOnClickListener { confirm(getString(R.string.group_confirm_archive, model.state.value.detail?.name.orEmpty())) { model.archive() } }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.state.collect { state ->
                    val current = binding ?: return@collect
                    val owner = state.detail?.role == GroupRole.OWNER
                    state.detail?.let { detail ->
                        current.title.text = detail.name
                        current.ivAvatar.setImageDrawable(InitialsAvatarDrawable(detail.name))
                        val role = getString(if (owner) R.string.group_owner else R.string.group_member)
                        current.summary.text = detail.subject?.let { getString(R.string.group_summary, it.name, detail.memberCount, role) }
                            ?: getString(R.string.group_summary_no_subject, detail.memberCount, role)
                        // Owners edit the description in the form below; others read it here.
                        current.tvDescription.text = detail.description.orEmpty()
                        current.tvDescription.isVisible = !owner && !detail.description.isNullOrBlank()
                    }
                    rendering = true
                    if (current.name.text.toString() != state.draftName) current.name.setText(state.draftName)
                    if (current.description.text.toString() != state.draftDescription) current.description.setText(state.draftDescription)
                    rendering = false
                    val ready = state.detail != null && !state.loading && !state.busy
                    current.layoutEdit.isVisible = owner
                    current.name.isEnabled = owner && ready; current.description.isEnabled = owner && ready
                    current.btnSave.isEnabled = ready
                    current.btnArchive.isVisible = owner
                    current.btnLeave.isVisible = state.detail != null && !owner
                    enable(current.btnArchive, ready); enable(current.btnLeave, ready)
                    enable(current.btnAddMember, ready); enable(current.btnMembers, ready)
                    enable(current.btnDocuments, state.detail != null)
                    current.btnRetry.isVisible = state.error != null
                    current.btnRetry.isEnabled = !state.loading && !state.busy
                    current.status.text = state.error ?: if (state.busy) getString(R.string.group_saving) else if (state.loading) getString(R.string.group_loading) else ""
                    if (state.saved) {
                        model.acknowledged()
                        Toast.makeText(requireContext(), R.string.group_saved, Toast.LENGTH_SHORT).show()
                    }
                    if (state.closed && !closing) {
                        closing = true
                        current.title.text = ""; current.summary.text = ""; current.name.setText(""); current.description.setText("")
                        Toast.makeText(requireContext(), state.error ?: getString(R.string.group_closed), Toast.LENGTH_LONG).show()
                        home()?.closeGroupDetails()
                    }
                }
            }
        }
        model.show(id)
    }

    private fun enable(row: View, enabled: Boolean) { row.isEnabled = enabled; row.alpha = if (enabled) 1f else 0.4f }

    override fun onResume() { super.onResume(); model.refresh() }
    override fun onDestroyView() { binding = null; rendering = false; super.onDestroyView() }
    companion object { fun newInstance(id: String) = GroupDetailFragment().apply { arguments = Bundle().apply { putString("group_id", id) } } }
}
