package com.vocaease.patient.feature.training

import com.vocaease.patient.core.database.AccountScopedDraftStorage
import com.vocaease.patient.core.database.AccountScopedDraftStorageProvider
import com.vocaease.patient.core.database.PreparationDraftSnapshot

class AccountScopedPreparationDraftStore(
    private val storage: AccountScopedDraftStorage,
) : PreparationDraftStore {
    override val accountScopeHash: String = storage.accountScopeHash

    override suspend fun find(draftId: String): PreparationDraft? =
        storage.findPreparationDraft(draftId)?.toDomain()

    override suspend fun create(draft: PreparationDraft) {
        storage.insertPreparationDraft(draft.toSnapshot(accountScopeHash))
    }

    override suspend fun bindServerSession(draftId: String, session: CreatedTrainingSession) {
        storage.bindPreparationSession(
            draftId = draftId,
            patientId = session.patientId.toString(),
            serverSessionId = session.sessionId.toString(),
            songId = session.song.id.toString(),
            songTitle = session.song.title,
            songArtist = session.song.artist,
            songDurationSeconds = session.song.durationSeconds,
        )
    }
}

class AccountScopedPreparationDraftStoreProvider(
    private val storageProvider: AccountScopedDraftStorageProvider,
) : PreparationDraftStoreProvider {
    override fun current(): PreparationDraftStore =
        AccountScopedPreparationDraftStore(storageProvider.current())
}

private fun PreparationDraft.toSnapshot(scopeHash: String) = PreparationDraftSnapshot(
    accountScopeHash = scopeHash,
    draftId = draftId,
    songId = songId,
    songTitle = songTitle,
    songArtist = songArtist,
    songDurationSeconds = songDurationSeconds,
    serverSessionId = serverSessionId,
    creationKey = creationKey,
    createdAt = createdAt,
    expiresAt = expiresAt,
)

private fun PreparationDraftSnapshot.toDomain() = PreparationDraft(
    draftId = draftId,
    songId = songId,
    songTitle = songTitle,
    songArtist = songArtist,
    songDurationSeconds = songDurationSeconds,
    serverSessionId = serverSessionId,
    creationKey = creationKey,
    createdAt = createdAt,
    expiresAt = expiresAt,
)
