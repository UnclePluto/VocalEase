from drf_spectacular.extensions import OpenApiAuthenticationExtension


class ActiveUserJWTAuthenticationScheme(OpenApiAuthenticationExtension):
    target_class = "apps.accounts.tokens.ActiveUserJWTAuthentication"
    name = "bearerAuth"

    def get_security_definition(self, auto_schema):
        return {
            "type": "http",
            "scheme": "bearer",
            "bearerFormat": "JWT",
        }


class LogoutJWTAuthenticationScheme(ActiveUserJWTAuthenticationScheme):
    target_class = "apps.accounts.views.LogoutJWTAuthentication"
    name = "logoutBearerAuth"
