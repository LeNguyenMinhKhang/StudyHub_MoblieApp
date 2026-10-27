package com.doan.studyhub.user.ui.profile

import android.net.Uri
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.doan.studyhub.core.data.local.AvatarMode
import com.doan.studyhub.core.data.local.AvatarSelection
import com.doan.studyhub.user.R

object AvatarImageLoader {

    fun showCommitted(fragment: Fragment, view: ImageView, remoteUrl: String?) {
        loadUrl(fragment, view, remoteUrl)
    }

    fun showEditor(fragment: Fragment, view: ImageView, selection: AvatarSelection, remoteUrl: String?) {
        val model: Any? = when (selection.mode) {
            AvatarMode.LOCAL -> selection.file?.takeIf { it.isFile }
            AvatarMode.REMOTE -> remoteUrl?.takeIf { isValidHttpsUrl(it) }
            AvatarMode.HIDDEN -> remoteUrl?.takeIf { isValidHttpsUrl(it) }
        }
        load(fragment, view, model)
    }

    fun show(fragment: Fragment, view: ImageView, selection: AvatarSelection, remoteUrl: String?) {
        showEditor(fragment, view, selection, remoteUrl)
    }

    private fun loadUrl(fragment: Fragment, view: ImageView, url: String?) {
        val model: Any? = url?.takeIf { isValidHttpsUrl(it) }
        load(fragment, view, model)
    }

    private fun load(fragment: Fragment, view: ImageView, model: Any?) {
        val request = Glide.with(fragment)
        if (model == null) {
            request.clear(view)
            view.setImageResource(R.drawable.ic_person)
        } else {
            request.load(model)
                .circleCrop()
                .placeholder(R.drawable.ic_person)
                .error(R.drawable.ic_person)
                .into(view)
        }
    }

    private fun isValidHttpsUrl(url: String): Boolean {
        return try {
            val uri = Uri.parse(url)
            uri.scheme == "https" && !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }
}
