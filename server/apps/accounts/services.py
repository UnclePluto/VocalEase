from django.contrib.auth import authenticate
from django.db import transaction
from django.utils import timezone
from rest_framework.exceptions import AuthenticationFailed, PermissionDenied, ValidationError

from apps.audit.services import record

from .models import User
from .tokens import TokenPair, issue_token_pair, revoke_user_refresh_tokens


def authenticate_login(*, login_id: str, password: str) -> User:
    user = authenticate(username=login_id, password=password)
    if user is None or user.deleted_at is not None:
        raise AuthenticationFailed("登录凭据无效", code="authentication_failed")
    return user


def login(
    *, login_id: str, password: str, client_kind: str, remember_me: bool = False
) -> tuple[User, TokenPair]:
    user = authenticate_login(login_id=login_id, password=password)
    if client_kind == "web" and user.role == "patient":
        raise PermissionDenied(
            "患者账号不能登录医生后台", code="admin_access_denied"
        )
    return user, issue_token_pair(user, remember_me=remember_me)


def change_password(
    *, user: User, old_password: str, new_password: str, request_id: str
) -> None:
    with transaction.atomic():
        locked_user = User.objects.select_for_update().get(pk=user.pk)
        if not locked_user.check_password(old_password):
            raise ValidationError({"old_password": "当前密码不正确"})
        locked_user.set_password(new_password)
        locked_user.must_change_password = False
        locked_user.save(update_fields=["password", "must_change_password"])
        revoke_user_refresh_tokens(locked_user)
        record(
            actor=locked_user,
            action="auth.change_password",
            target=locked_user,
            changes={"old_password": old_password, "new_password": new_password},
            request_id=request_id,
        )


def reset_password(*, actor: User, user: User, new_password: str, request_id: str) -> None:
    with transaction.atomic():
        locked_user = User.objects.select_for_update().get(pk=user.pk)
        locked_user.set_password(new_password)
        locked_user.must_change_password = True
        locked_user.save(update_fields=["password", "must_change_password"])
        revoke_user_refresh_tokens(locked_user)
        record(
            actor=actor,
            action="auth.reset_password",
            target=locked_user,
            changes={"new_password": new_password},
            request_id=request_id,
        )


def update_account_security_state(
    *,
    actor: User,
    target: User,
    request_id: str,
    is_active: bool | None = None,
    deleted: bool | None = None,
) -> User:
    if is_active is None and deleted is None:
        raise ValueError("必须指定账号安全状态变更")
    with transaction.atomic():
        locked_target = User.objects.select_for_update().get(pk=target.pk)
        changes = {}
        update_fields = []
        if is_active is not None:
            changes["is_active"] = {"from": locked_target.is_active, "to": is_active}
            locked_target.is_active = is_active
            update_fields.append("is_active")
        if deleted is not None:
            deleted_at = timezone.now() if deleted else None
            changes["deleted"] = {
                "from": locked_target.deleted_at is not None,
                "to": deleted,
            }
            locked_target.deleted_at = deleted_at
            update_fields.append("deleted_at")
        locked_target.save(update_fields=update_fields)
        revoke_user_refresh_tokens(locked_target)
        record(
            actor=actor,
            action="account.security_state_changed",
            target=locked_target,
            changes=changes,
            request_id=request_id,
        )
    return locked_target
