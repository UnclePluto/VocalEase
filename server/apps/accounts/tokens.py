from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from time import sleep
from collections.abc import Callable
from uuid import UUID, uuid4

from django.db import OperationalError, connection, transaction
from django.utils import timezone
from rest_framework.exceptions import AuthenticationFailed
from rest_framework_simplejwt.authentication import JWTAuthentication
from rest_framework_simplejwt.exceptions import TokenError
from rest_framework_simplejwt.tokens import RefreshToken as SimpleJWTRefreshToken

from .models import RefreshToken, User


@dataclass(frozen=True)
class TokenPair:
    access: str
    refresh: str
    refresh_expires_at: datetime


class ActiveUserJWTAuthentication(JWTAuthentication):
    def get_user(self, validated_token):
        user = super().get_user(validated_token)
        if user.deleted_at is not None:
            raise AuthenticationFailed("账号已删除", code="user_not_found")
        return user


def issue_token_pair(
    user: User,
    *,
    remember_me: bool = False,
    family_id: UUID | None = None,
    parent: RefreshToken | None = None,
) -> TokenPair:
    refresh = SimpleJWTRefreshToken.for_user(user)
    if remember_me:
        refresh.set_exp(lifetime=timedelta(days=30))
    raw_refresh = str(refresh)
    expires_at = datetime.fromtimestamp(refresh["exp"], tz=UTC)
    RefreshToken.objects.create(
        user=user,
        token_hash=RefreshToken.digest(raw_refresh),
        expires_at=expires_at,
        family_id=family_id or uuid4(),
        parent=parent,
    )
    return TokenPair(
        access=str(refresh.access_token),
        refresh=raw_refresh,
        refresh_expires_at=expires_at,
    )


def rotate_refresh_token(
    raw_refresh: str, *, on_success: Callable[[User], None] | None = None
) -> tuple[User, TokenPair]:
    try:
        decoded = SimpleJWTRefreshToken(raw_refresh)
        user_id = decoded["user_id"]
    except (KeyError, RefreshToken.DoesNotExist, TokenError) as exc:
        raise AuthenticationFailed("刷新令牌无效", code="token_not_valid") from exc

    for attempt in range(3):
        try:
            return _rotate_refresh_token(raw_refresh, decoded, user_id, on_success)
        except OperationalError as exc:
            if connection.vendor != "sqlite" or "locked" not in str(exc).lower() or attempt == 2:
                raise
            sleep(0.01 * (attempt + 1))
    raise AssertionError("unreachable")


def _rotate_refresh_token(
    raw_refresh: str,
    decoded,
    user_id,
    on_success: Callable[[User], None] | None,
) -> tuple[User, TokenPair]:
    reuse_detected = False
    invalid_token = False
    pair = None
    user = None
    with transaction.atomic():
        try:
            token = RefreshToken.objects.select_for_update().select_related("user").get(
                token_hash=RefreshToken.digest(raw_refresh)
            )
        except RefreshToken.DoesNotExist:
            invalid_token = True
        else:
            if token.revoked_at is not None:
                if RefreshToken.objects.filter(parent=token).exists():
                    RefreshToken.objects.filter(
                        family_id=token.family_id, revoked_at__isnull=True
                    ).update(revoked_at=timezone.now())
                    reuse_detected = True
                else:
                    invalid_token = True
            elif token.expires_at <= timezone.now() or str(token.user_id) != str(user_id):
                invalid_token = True
            elif not token.user.is_active or token.user.deleted_at is not None:
                raise AuthenticationFailed("账号不可用", code="user_not_found")
            else:
                token.revoked_at = timezone.now()
                token.save(update_fields=["revoked_at"])
                remember_me = decoded["exp"] - decoded["iat"] > 24 * 60 * 60
                user = token.user
                pair = issue_token_pair(
                    token.user,
                    remember_me=remember_me,
                    family_id=token.family_id,
                    parent=token,
                )
                if on_success is not None:
                    on_success(user)

    if reuse_detected:
        raise AuthenticationFailed("检测到刷新令牌重放，令牌族已撤销", code="token_reuse_detected")
    if invalid_token or user is None or pair is None:
        raise AuthenticationFailed("刷新令牌无效", code="token_not_valid")
    return user, pair


def revoke_refresh_token(raw_refresh: str) -> bool:
    token = RefreshToken.objects.filter(
        token_hash=RefreshToken.digest(raw_refresh), revoked_at__isnull=True
    ).first()
    if token is None:
        return False
    token.revoked_at = timezone.now()
    token.save(update_fields=["revoked_at"])
    return True


def revoke_user_refresh_tokens(user: User) -> None:
    RefreshToken.objects.filter(user=user, revoked_at__isnull=True).update(revoked_at=timezone.now())
