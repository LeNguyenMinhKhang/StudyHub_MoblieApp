package com.doan.studyhub.core.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SubjectFollow(
    @SerialName("subject_id") val subjectId: String,
    @SerialName("user_id") val userId: String
)