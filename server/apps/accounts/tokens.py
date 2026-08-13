from dataclasses import dataclass
from datetime import UTC, datetime

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


def issue_token_pair(user: User) -> TokenPair:
    refresh = SimpleJWTRefreshToken.for_user(user)
    raw_refresh = str(refresh)
    expires_at = datetime.fromtimestamp(refresh["exp"], tz=UTC)
    RefreshToken.objects.create(
        user=user,
        token_hash=RefreshToken.digest(raw_refresh),
        expires_at=expires_at,
    )
    return TokenPair(
        access=str(refresh.access_token),
        refresh=raw_refresh,
        refresh_expires_at=expires_at,
    )


def rotate_refresh_token(raw_refresh: str) -> tuple[User, TokenPair]:
    try:
        decoded = SimpleJWTRefreshToken(raw_refresh)
        user_id = decoded["user_id"]
        token = RefreshToken.objects.select_related("user").get(
            token_hash=RefreshToken.digest(raw_refresh),
            revoked_at__isnull=True,
        )
    except (KeyError, RefreshToken.DoesNotExist, TokenError) as exc:
        raise AuthenticationFailed("刷新令牌无效", code="token_not_valid") from exc

    if token.expires_at <= timezone.now() or str(token.user_id) != str(user_id):
        raise AuthenticationFailed("刷新令牌无效", code="token_not_valid")
    if not token.user.is_active or token.user.deleted_at is not None:
        raise AuthenticationFailed("账号不可用", code="user_not_found")
    token.revoked_at = timezone.now()
    token.save(update_fields=["revoked_at"])
    return token.user, issue_token_pair(token.user)


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
