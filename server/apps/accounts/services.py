from django.contrib.auth import authenticate
from rest_framework.exceptions import AuthenticationFailed, ValidationError

from apps.audit.services import record

from .models import User
from .tokens import TokenPair, issue_token_pair, revoke_user_refresh_tokens


def authenticate_login(*, login_id: str, password: str) -> User:
    user = authenticate(username=login_id, password=password)
    if user is None or user.deleted_at is not None:
        raise AuthenticationFailed("登录凭据无效", code="authentication_failed")
    return user


def login(*, login_id: str, password: str) -> tuple[User, TokenPair]:
    user = authenticate_login(login_id=login_id, password=password)
    return user, issue_token_pair(user)


def change_password(*, user: User, old_password: str, new_password: str) -> None:
    if not user.check_password(old_password):
        raise ValidationError({"old_password": "当前密码不正确"})
    user.set_password(new_password)
    user.must_change_password = False
    user.save(update_fields=["password", "must_change_password"])
    revoke_user_refresh_tokens(user)


def reset_password(*, actor: User, user: User, new_password: str, request_id: str) -> None:
    user.set_password(new_password)
    user.must_change_password = True
    user.save(update_fields=["password", "must_change_password"])
    revoke_user_refresh_tokens(user)
    record(
        actor=actor,
        action="auth.reset_password",
        target=user,
        changes={"new_password": new_password},
        request_id=request_id,
    )
