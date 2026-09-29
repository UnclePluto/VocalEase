package com.vocaease.patient.feature.upload

import androidx.work.Data

private const val ACCOUNT_SCOPE_HASH_KEY = "account_scope_hash"
private const val DRAFT_ID_KEY = "draft_id"

private fun Data.Builder.write(key: String, value: String) = putString(key, value)

fun unsafeRequest(accountScopeHash: String, draftId: String, token: String) {
    Data.Builder()
        .putString(ACCOUNT_SCOPE_HASH_KEY, accountScopeHash)
        .putString(DRAFT_ID_KEY, draftId)
        .write("access_token", token)
}
