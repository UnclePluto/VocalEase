package com.vocaease.patient.feature.upload

import androidx.work.Data

private const val ACCOUNT_SCOPE_HASH_KEY = "account_scope_hash"
private const val DRAFT_ID_KEY = "draft_id"

fun unsafeRequest(accountScopeHash: String, draftId: String, token: String) {
    val builder = Data.Builder()
    builder.putString(ACCOUNT_SCOPE_HASH_KEY, accountScopeHash)
    builder.putString(DRAFT_ID_KEY, draftId)
    val sink = builder::putString
    sink("access_token", token)
}
