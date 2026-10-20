package com.doan.studyhub.user.ui.profile

import android.net.Uri
import android.widget.ImageView
import androidx.fragment.app.Fragment
import com.bumptech.glide.Glide
import com.doan.studyhub.core.data.local.AvatarMode
import com.doan.studyhub.core.data.local.AvatarSelection
import com.doan.studyhub.user.R

object AvatarImageLoader {
    fun show(fragment: Fragment, view: ImageView, selection: AvatarSelection, remoteUrl: String?) {
        val request = Glide.with(fragment)
        val model: Any? = when (selection.mode) {
            AvatarMode.HIDDEN -> null
            AvatarMode.LOCAL -> selection.file?.takeIf { it.isFile }
            AvatarMode.REMOTE -> remoteUrl?.takeIf { url ->
                url.isNotBlank() && run {
                    val scheme = Uri.parse(url).scheme
                    scheme == "https" || scheme == "http"
                }
            }
        }
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
}
