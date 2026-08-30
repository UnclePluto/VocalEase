CREATE TRIGGER IF NOT EXISTS drafts_guard_insert_v1 BEFORE INSERT ON drafts BEGIN
  SELECT CASE WHEN trim(NEW.account_scope) = '' OR trim(NEW.draft_id) = '' OR trim(NEW.song_id) = ''
    OR trim(NEW.session_id) = '' OR trim(NEW.creation_key) = ''
    OR length(NEW.account_scope) > 128 OR length(NEW.draft_id) > 128 OR length(NEW.song_id) > 128
    OR length(NEW.session_id) > 128 OR length(NEW.creation_key) > 128
    OR NEW.state NOT IN ('RECORDING','REVIEW_READY','INTERRUPTED','READY_TO_UPLOAD','UPLOADING','SUBMITTED','FAILED')
    OR typeof(NEW.duration_ms) != 'integer' OR typeof(NEW.created_at) != 'integer' OR typeof(NEW.expires_at) != 'integer'
    OR NEW.duration_ms < 0 OR NEW.created_at < 0 OR NEW.expires_at < NEW.created_at
    OR NEW.expires_at - NEW.created_at > 604800000
    OR (NEW.interruption_reason IS NOT NULL AND length(NEW.interruption_reason) > 256)
  THEN RAISE(ABORT, 'draft constraint') END;
END;
-- VOCAEASE-STATEMENT
CREATE TRIGGER IF NOT EXISTS drafts_guard_update_v1 BEFORE UPDATE ON drafts BEGIN
  SELECT CASE WHEN NEW.account_scope != OLD.account_scope OR NEW.draft_id != OLD.draft_id
    OR NEW.session_id != OLD.session_id OR NEW.creation_key != OLD.creation_key
    OR trim(NEW.account_scope) = '' OR trim(NEW.draft_id) = '' OR trim(NEW.song_id) = ''
    OR trim(NEW.session_id) = '' OR trim(NEW.creation_key) = ''
    OR length(NEW.account_scope) > 128 OR length(NEW.draft_id) > 128 OR length(NEW.song_id) > 128
    OR length(NEW.session_id) > 128 OR length(NEW.creation_key) > 128
    OR NEW.state NOT IN ('RECORDING','REVIEW_READY','INTERRUPTED','READY_TO_UPLOAD','UPLOADING','SUBMITTED','FAILED')
    OR typeof(NEW.duration_ms) != 'integer' OR typeof(NEW.created_at) != 'integer' OR typeof(NEW.expires_at) != 'integer'
    OR NEW.duration_ms < 0 OR NEW.created_at < 0 OR NEW.expires_at < NEW.created_at
    OR NEW.expires_at - NEW.created_at > 604800000
    OR (NEW.interruption_reason IS NOT NULL AND length(NEW.interruption_reason) > 256)
  THEN RAISE(ABORT, 'draft constraint') END;
END;
-- VOCAEASE-STATEMENT
CREATE TRIGGER IF NOT EXISTS media_guard_insert_v1 BEFORE INSERT ON media BEGIN
  SELECT CASE WHEN trim(NEW.account_scope) = '' OR trim(NEW.draft_id) = ''
    OR NEW.type NOT IN ('AUDIO','VIDEO') OR trim(NEW.mime_type) = '' OR length(NEW.mime_type) > 128
    OR typeof(NEW.size_bytes) != 'integer' OR NEW.size_bytes < 0
    OR length(NEW.sha256) != 64 OR NEW.sha256 GLOB '*[^0-9a-f]*'
    OR NEW.validation_state NOT IN ('PENDING','VALID','INVALID')
    OR NEW.encrypted_relative_path NOT GLOB 'media/v1/*.vef'
    OR length(NEW.encrypted_relative_path) != 45
    OR substr(NEW.encrypted_relative_path, 10, 32) GLOB '*[^0-9a-f]*'
    OR NEW.encrypted_relative_path GLOB '*..*' OR instr(NEW.encrypted_relative_path, '\') != 0
  THEN RAISE(ABORT, 'media constraint') END;
END;
-- VOCAEASE-STATEMENT
CREATE TRIGGER IF NOT EXISTS media_guard_update_v1 BEFORE UPDATE ON media BEGIN
  SELECT CASE WHEN NEW.account_scope != OLD.account_scope OR NEW.draft_id != OLD.draft_id
    OR NEW.type != OLD.type OR NEW.encrypted_relative_path != OLD.encrypted_relative_path
    OR trim(NEW.account_scope) = '' OR trim(NEW.draft_id) = ''
    OR NEW.type NOT IN ('AUDIO','VIDEO') OR trim(NEW.mime_type) = '' OR length(NEW.mime_type) > 128
    OR typeof(NEW.size_bytes) != 'integer' OR NEW.size_bytes < 0
    OR length(NEW.sha256) != 64 OR NEW.sha256 GLOB '*[^0-9a-f]*'
    OR NEW.validation_state NOT IN ('PENDING','VALID','INVALID')
    OR NEW.encrypted_relative_path NOT GLOB 'media/v1/*.vef'
    OR length(NEW.encrypted_relative_path) != 45
    OR substr(NEW.encrypted_relative_path, 10, 32) GLOB '*[^0-9a-f]*'
    OR NEW.encrypted_relative_path GLOB '*..*' OR instr(NEW.encrypted_relative_path, '\') != 0
  THEN RAISE(ABORT, 'media constraint') END;
END;
-- VOCAEASE-STATEMENT
CREATE TRIGGER IF NOT EXISTS upload_jobs_guard_insert_v1 BEFORE INSERT ON upload_jobs BEGIN
  SELECT CASE WHEN trim(NEW.account_scope) = '' OR trim(NEW.draft_id) = ''
    OR trim(NEW.audio_grant_key) = '' OR trim(NEW.video_grant_key) = '' OR trim(NEW.submit_key) = ''
    OR length(NEW.audio_grant_key) > 128 OR length(NEW.video_grant_key) > 128 OR length(NEW.submit_key) > 128
    OR NEW.overall_state NOT IN ('PAUSED','WAITING_NETWORK','UPLOADING','WAITING_CALLBACK','CONFIRMING','READY_TO_SUBMIT','SUBMITTING','ANALYZING','FAILED','CANCELLED','COMPLETED')
    OR NEW.audio_grant_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.video_grant_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.audio_upload_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.video_upload_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.audio_receipt_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.video_receipt_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.audio_confirm_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.video_confirm_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.submit_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR typeof(NEW.attempt_count) != 'integer' OR NEW.attempt_count < 0
    OR (NEW.next_retry_at IS NOT NULL AND (typeof(NEW.next_retry_at) != 'integer' OR NEW.next_retry_at < 0))
    OR (NEW.last_safe_error IS NOT NULL AND length(NEW.last_safe_error) > 256)
    OR (NEW.audio_asset_key IS NOT NULL AND (trim(NEW.audio_asset_key) = '' OR length(NEW.audio_asset_key) > 512))
    OR (NEW.video_asset_key IS NOT NULL AND (trim(NEW.video_asset_key) = '' OR length(NEW.video_asset_key) > 512))
    OR (NEW.audio_object_key IS NOT NULL AND (trim(NEW.audio_object_key) = '' OR length(NEW.audio_object_key) > 512))
    OR (NEW.video_object_key IS NOT NULL AND (trim(NEW.video_object_key) = '' OR length(NEW.video_object_key) > 512))
    OR (NEW.audio_receipt IS NOT NULL AND (trim(NEW.audio_receipt) = '' OR length(NEW.audio_receipt) > 512))
    OR (NEW.video_receipt IS NOT NULL AND (trim(NEW.video_receipt) = '' OR length(NEW.video_receipt) > 512))
    OR (NEW.audio_confirmed_at IS NOT NULL AND (typeof(NEW.audio_confirmed_at) != 'integer' OR NEW.audio_confirmed_at < 0))
    OR (NEW.video_confirmed_at IS NOT NULL AND (typeof(NEW.video_confirmed_at) != 'integer' OR NEW.video_confirmed_at < 0))
    OR (NEW.audio_asset_key IS NULL) != (NEW.audio_object_key IS NULL)
    OR (NEW.video_asset_key IS NULL) != (NEW.video_object_key IS NULL)
    OR (NEW.audio_receipt IS NOT NULL AND (NEW.audio_asset_key IS NULL OR NEW.audio_object_key IS NULL))
    OR (NEW.video_receipt IS NOT NULL AND (NEW.video_asset_key IS NULL OR NEW.video_object_key IS NULL))
    OR (NEW.audio_receipt_state = 'RECEIPT_RECEIVED' AND (trim(coalesce(NEW.audio_receipt,'')) = '' OR trim(coalesce(NEW.audio_asset_key,'')) = '' OR trim(coalesce(NEW.audio_object_key,'')) = ''))
    OR (NEW.video_receipt_state = 'RECEIPT_RECEIVED' AND (trim(coalesce(NEW.video_receipt,'')) = '' OR trim(coalesce(NEW.video_asset_key,'')) = '' OR trim(coalesce(NEW.video_object_key,'')) = ''))
    OR (NEW.audio_receipt_state != 'RECEIPT_RECEIVED' AND NEW.audio_receipt IS NOT NULL)
    OR (NEW.video_receipt_state != 'RECEIPT_RECEIVED' AND NEW.video_receipt IS NOT NULL)
    OR (NEW.audio_confirm_state = 'CONFIRMED' AND (NEW.audio_receipt_state != 'RECEIPT_RECEIVED' OR NEW.audio_confirmed_at IS NULL))
    OR (NEW.video_confirm_state = 'CONFIRMED' AND (NEW.video_receipt_state != 'RECEIPT_RECEIVED' OR NEW.video_confirmed_at IS NULL))
    OR (NEW.audio_confirm_state != 'CONFIRMED' AND NEW.audio_confirmed_at IS NOT NULL)
    OR (NEW.video_confirm_state != 'CONFIRMED' AND NEW.video_confirmed_at IS NOT NULL)
    OR (NEW.overall_state = 'READY_TO_SUBMIT' AND (NEW.audio_confirm_state != 'CONFIRMED' OR NEW.video_confirm_state != 'CONFIRMED'))
    OR NEW.audio_grant_key = NEW.video_grant_key OR NEW.audio_grant_key = NEW.submit_key OR NEW.video_grant_key = NEW.submit_key
    OR EXISTS (
      SELECT 1 FROM upload_jobs AS existing
      WHERE existing.account_scope = NEW.account_scope AND existing.draft_id != NEW.draft_id
        AND (existing.audio_grant_key IN (NEW.audio_grant_key,NEW.video_grant_key,NEW.submit_key)
          OR existing.video_grant_key IN (NEW.audio_grant_key,NEW.video_grant_key,NEW.submit_key)
          OR existing.submit_key IN (NEW.audio_grant_key,NEW.video_grant_key,NEW.submit_key))
    )
  THEN RAISE(ABORT, 'upload constraint') END;
END;
-- VOCAEASE-STATEMENT
CREATE TRIGGER IF NOT EXISTS upload_jobs_guard_update_v1 BEFORE UPDATE ON upload_jobs BEGIN
  SELECT CASE WHEN NEW.account_scope != OLD.account_scope OR NEW.draft_id != OLD.draft_id
    OR NEW.audio_grant_key != OLD.audio_grant_key OR NEW.video_grant_key != OLD.video_grant_key OR NEW.submit_key != OLD.submit_key
    OR trim(NEW.account_scope) = '' OR trim(NEW.draft_id) = ''
    OR trim(NEW.audio_grant_key) = '' OR trim(NEW.video_grant_key) = '' OR trim(NEW.submit_key) = ''
    OR length(NEW.audio_grant_key) > 128 OR length(NEW.video_grant_key) > 128 OR length(NEW.submit_key) > 128
    OR NEW.overall_state NOT IN ('PAUSED','WAITING_NETWORK','UPLOADING','WAITING_CALLBACK','CONFIRMING','READY_TO_SUBMIT','SUBMITTING','ANALYZING','FAILED','CANCELLED','COMPLETED')
    OR NEW.audio_grant_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.video_grant_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.audio_upload_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.video_upload_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.audio_receipt_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.video_receipt_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.audio_confirm_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.video_confirm_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR NEW.submit_state NOT IN ('PENDING','REQUESTING_GRANT','GRANT_READY','UPLOADING','UPLOADED','WAITING_RECEIPT','RECEIPT_RECEIVED','CONFIRMING','CONFIRMED','SUBMITTING','SUBMITTED','ANALYZING','SUCCEEDED','RETRYABLE_FAILURE','TERMINAL_FAILURE')
    OR typeof(NEW.attempt_count) != 'integer' OR NEW.attempt_count < 0
    OR (NEW.next_retry_at IS NOT NULL AND (typeof(NEW.next_retry_at) != 'integer' OR NEW.next_retry_at < 0))
    OR (NEW.last_safe_error IS NOT NULL AND length(NEW.last_safe_error) > 256)
    OR (NEW.audio_asset_key IS NOT NULL AND (trim(NEW.audio_asset_key) = '' OR length(NEW.audio_asset_key) > 512))
    OR (NEW.video_asset_key IS NOT NULL AND (trim(NEW.video_asset_key) = '' OR length(NEW.video_asset_key) > 512))
    OR (NEW.audio_object_key IS NOT NULL AND (trim(NEW.audio_object_key) = '' OR length(NEW.audio_object_key) > 512))
    OR (NEW.video_object_key IS NOT NULL AND (trim(NEW.video_object_key) = '' OR length(NEW.video_object_key) > 512))
    OR (NEW.audio_receipt IS NOT NULL AND (trim(NEW.audio_receipt) = '' OR length(NEW.audio_receipt) > 512))
    OR (NEW.video_receipt IS NOT NULL AND (trim(NEW.video_receipt) = '' OR length(NEW.video_receipt) > 512))
    OR (NEW.audio_confirmed_at IS NOT NULL AND (typeof(NEW.audio_confirmed_at) != 'integer' OR NEW.audio_confirmed_at < 0))
    OR (NEW.video_confirmed_at IS NOT NULL AND (typeof(NEW.video_confirmed_at) != 'integer' OR NEW.video_confirmed_at < 0))
    OR (NEW.audio_asset_key IS NULL) != (NEW.audio_object_key IS NULL)
    OR (NEW.video_asset_key IS NULL) != (NEW.video_object_key IS NULL)
    OR (NEW.audio_receipt IS NOT NULL AND (NEW.audio_asset_key IS NULL OR NEW.audio_object_key IS NULL))
    OR (NEW.video_receipt IS NOT NULL AND (NEW.video_asset_key IS NULL OR NEW.video_object_key IS NULL))
    OR (NEW.audio_receipt_state = 'RECEIPT_RECEIVED' AND (trim(coalesce(NEW.audio_receipt,'')) = '' OR trim(coalesce(NEW.audio_asset_key,'')) = '' OR trim(coalesce(NEW.audio_object_key,'')) = ''))
    OR (NEW.video_receipt_state = 'RECEIPT_RECEIVED' AND (trim(coalesce(NEW.video_receipt,'')) = '' OR trim(coalesce(NEW.video_asset_key,'')) = '' OR trim(coalesce(NEW.video_object_key,'')) = ''))
    OR (NEW.audio_receipt_state != 'RECEIPT_RECEIVED' AND NEW.audio_receipt IS NOT NULL)
    OR (NEW.video_receipt_state != 'RECEIPT_RECEIVED' AND NEW.video_receipt IS NOT NULL)
    OR (NEW.audio_confirm_state = 'CONFIRMED' AND (NEW.audio_receipt_state != 'RECEIPT_RECEIVED' OR NEW.audio_confirmed_at IS NULL))
    OR (NEW.video_confirm_state = 'CONFIRMED' AND (NEW.video_receipt_state != 'RECEIPT_RECEIVED' OR NEW.video_confirmed_at IS NULL))
    OR (NEW.audio_confirm_state != 'CONFIRMED' AND NEW.audio_confirmed_at IS NOT NULL)
    OR (NEW.video_confirm_state != 'CONFIRMED' AND NEW.video_confirmed_at IS NOT NULL)
    OR (NEW.overall_state = 'READY_TO_SUBMIT' AND (NEW.audio_confirm_state != 'CONFIRMED' OR NEW.video_confirm_state != 'CONFIRMED'))
    OR NEW.audio_grant_key = NEW.video_grant_key OR NEW.audio_grant_key = NEW.submit_key OR NEW.video_grant_key = NEW.submit_key
    OR EXISTS (
      SELECT 1 FROM upload_jobs AS existing
      WHERE existing.account_scope = NEW.account_scope AND existing.draft_id != NEW.draft_id
        AND (existing.audio_grant_key IN (NEW.audio_grant_key,NEW.video_grant_key,NEW.submit_key)
          OR existing.video_grant_key IN (NEW.audio_grant_key,NEW.video_grant_key,NEW.submit_key)
          OR existing.submit_key IN (NEW.audio_grant_key,NEW.video_grant_key,NEW.submit_key))
    )
  THEN RAISE(ABORT, 'upload constraint') END;
END;
-- VOCAEASE-STATEMENT
CREATE TRIGGER IF NOT EXISTS preparation_drafts_guard_insert_v2 BEFORE INSERT ON preparation_drafts BEGIN
  SELECT CASE WHEN trim(NEW.account_scope) = '' OR trim(NEW.draft_id) = '' OR trim(NEW.song_id) = ''
    OR trim(NEW.song_title) = '' OR trim(NEW.song_artist) = '' OR trim(NEW.creation_key) = ''
    OR length(NEW.account_scope) > 128 OR length(NEW.draft_id) > 128 OR length(NEW.song_id) > 128
    OR length(NEW.song_title) > 256 OR length(NEW.song_artist) > 256 OR length(NEW.creation_key) > 128
    OR NEW.creation_key NOT LIKE 'session-create:%:' || NEW.draft_id
    OR typeof(NEW.song_duration_seconds) != 'integer' OR NEW.song_duration_seconds <= 0
    OR (NEW.server_session_id IS NOT NULL AND (trim(NEW.server_session_id) = '' OR length(NEW.server_session_id) > 128))
    OR NEW.status NOT IN ('PENDING','BOUND','HANDOFF_PENDING','HANDED_OFF','ABANDONED')
    OR (NEW.status IN ('PENDING','BOUND','HANDOFF_PENDING') AND NEW.active_song_id IS NOT NEW.song_id)
    OR (NEW.status IN ('HANDED_OFF','ABANDONED') AND NEW.active_song_id IS NOT NULL)
    OR (NEW.status = 'PENDING' AND NEW.server_session_id IS NOT NULL)
    OR (NEW.status IN ('BOUND','HANDOFF_PENDING','HANDED_OFF') AND NEW.server_session_id IS NULL)
    OR typeof(NEW.created_at) != 'integer' OR typeof(NEW.expires_at) != 'integer'
    OR NEW.created_at < 0 OR NEW.expires_at < NEW.created_at
    OR NEW.expires_at - NEW.created_at > 604800000
  THEN RAISE(ABORT, 'preparation draft constraint') END;
END;
-- VOCAEASE-STATEMENT
CREATE TRIGGER IF NOT EXISTS preparation_drafts_guard_update_v2 BEFORE UPDATE ON preparation_drafts BEGIN
  SELECT CASE WHEN NEW.account_scope != OLD.account_scope OR NEW.draft_id != OLD.draft_id
    OR NEW.song_id != OLD.song_id OR NEW.creation_key != OLD.creation_key
    OR NEW.created_at != OLD.created_at OR NEW.expires_at != OLD.expires_at
    OR NOT (
      (NEW.status = OLD.status AND NEW.server_session_id IS OLD.server_session_id
        AND NEW.active_song_id IS OLD.active_song_id AND NEW.song_title = OLD.song_title
        AND NEW.song_artist = OLD.song_artist AND NEW.song_duration_seconds = OLD.song_duration_seconds)
      OR (OLD.status = 'PENDING' AND NEW.status = 'BOUND' AND OLD.server_session_id IS NULL
        AND NEW.server_session_id IS NOT NULL AND NEW.active_song_id = OLD.song_id)
      OR (OLD.status = 'BOUND' AND NEW.status = 'HANDOFF_PENDING'
        AND NEW.server_session_id IS OLD.server_session_id AND NEW.active_song_id = OLD.song_id
        AND NEW.song_title = OLD.song_title AND NEW.song_artist = OLD.song_artist
        AND NEW.song_duration_seconds = OLD.song_duration_seconds)
      OR (OLD.status = 'HANDOFF_PENDING' AND NEW.status = 'HANDED_OFF'
        AND NEW.server_session_id IS OLD.server_session_id AND NEW.active_song_id IS NULL
        AND NEW.song_title = OLD.song_title AND NEW.song_artist = OLD.song_artist
        AND NEW.song_duration_seconds = OLD.song_duration_seconds)
      OR (OLD.status = 'HANDED_OFF' AND NEW.status = 'BOUND'
        AND NEW.server_session_id IS OLD.server_session_id AND NEW.active_song_id = OLD.song_id
        AND NEW.song_title = OLD.song_title AND NEW.song_artist = OLD.song_artist
        AND NEW.song_duration_seconds = OLD.song_duration_seconds)
      OR (OLD.status IN ('PENDING','BOUND','HANDOFF_PENDING') AND NEW.status = 'ABANDONED'
        AND NEW.server_session_id IS OLD.server_session_id AND NEW.active_song_id IS NULL
        AND NEW.song_title = OLD.song_title AND NEW.song_artist = OLD.song_artist
        AND NEW.song_duration_seconds = OLD.song_duration_seconds)
    )
    OR trim(NEW.account_scope) = '' OR trim(NEW.draft_id) = '' OR trim(NEW.song_id) = ''
    OR trim(NEW.song_title) = '' OR trim(NEW.song_artist) = '' OR trim(NEW.creation_key) = ''
    OR length(NEW.account_scope) > 128 OR length(NEW.draft_id) > 128 OR length(NEW.song_id) > 128
    OR length(NEW.song_title) > 256 OR length(NEW.song_artist) > 256 OR length(NEW.creation_key) > 128
    OR NEW.creation_key NOT LIKE 'session-create:%:' || NEW.draft_id
    OR typeof(NEW.song_duration_seconds) != 'integer' OR NEW.song_duration_seconds <= 0
    OR (NEW.server_session_id IS NOT NULL AND (trim(NEW.server_session_id) = '' OR length(NEW.server_session_id) > 128))
    OR NEW.status NOT IN ('PENDING','BOUND','HANDOFF_PENDING','HANDED_OFF','ABANDONED')
    OR (NEW.status IN ('PENDING','BOUND','HANDOFF_PENDING') AND NEW.active_song_id IS NOT NEW.song_id)
    OR (NEW.status IN ('HANDED_OFF','ABANDONED') AND NEW.active_song_id IS NOT NULL)
    OR (NEW.status = 'PENDING' AND NEW.server_session_id IS NOT NULL)
    OR (NEW.status IN ('BOUND','HANDOFF_PENDING','HANDED_OFF') AND NEW.server_session_id IS NULL)
    OR typeof(NEW.created_at) != 'integer' OR typeof(NEW.expires_at) != 'integer'
    OR NEW.created_at < 0 OR NEW.expires_at < NEW.created_at
    OR NEW.expires_at - NEW.created_at > 604800000
  THEN RAISE(ABORT, 'preparation draft constraint') END;
END;
