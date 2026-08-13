import hashlib

from django.contrib.auth.base_user import BaseUserManager
from django.contrib.auth.models import AbstractBaseUser, PermissionsMixin
from django.db import models

from common.models import UUIDSoftDeleteModel


class Role(models.TextChoices):
    SYSTEM_ADMIN = "system_admin", "系统管理员"
    DOCTOR = "doctor", "医生"
    PATIENT = "patient", "患者"


class UserManager(BaseUserManager):
    def create_user(self, login_id, password=None, **extra_fields):
        if not login_id:
            raise ValueError("login_id 不能为空")
        if not password:
            raise ValueError("密码不能为空")
        user = self.model(login_id=login_id, **extra_fields)
        user.set_password(password)
        user.save(using=self._db)
        return user

    def create_superuser(self, login_id, password=None, **extra_fields):
        if extra_fields.get("role", Role.SYSTEM_ADMIN) != Role.SYSTEM_ADMIN:
            raise ValueError("超级用户必须是系统管理员")
        if extra_fields.get("is_superuser", True) is not True:
            raise ValueError("超级用户必须设置 is_superuser=True")
        extra_fields["role"] = Role.SYSTEM_ADMIN
        extra_fields["is_superuser"] = True
        return self.create_user(login_id, password, **extra_fields)


class User(AbstractBaseUser, PermissionsMixin, UUIDSoftDeleteModel):
    login_id = models.CharField(max_length=32, unique=True)
    role = models.CharField(max_length=20, choices=Role.choices)
    is_active = models.BooleanField(default=True)
    must_change_password = models.BooleanField(default=True)

    objects = UserManager()

    USERNAME_FIELD = "login_id"
    REQUIRED_FIELDS = ["role"]

    class Meta:
        ordering = ["login_id"]

    @property
    def is_staff(self):
        return self.role == Role.SYSTEM_ADMIN


class RefreshToken(UUIDSoftDeleteModel):
    user = models.ForeignKey(User, on_delete=models.CASCADE, related_name="refresh_tokens")
    token_hash = models.CharField(max_length=64, unique=True)
    expires_at = models.DateTimeField()
    revoked_at = models.DateTimeField(null=True, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        ordering = ["-created_at"]

    @classmethod
    def digest(cls, raw_token: str) -> str:
        return hashlib.sha256(raw_token.encode("utf-8")).hexdigest()

    @property
    def is_active(self) -> bool:
        return self.revoked_at is None
