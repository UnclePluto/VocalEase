package com.vocaease.build;

import java.net.URI;
import java.net.URISyntaxException;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

@DisableCachingByDefault(because = "纯校验任务没有输出")
public abstract class ValidateReleaseApiBaseUrlTask extends DefaultTask {
    @Input
    public abstract Property<String> getApiBaseUrl();

    @TaskAction
    public void validateApiBaseUrl() {
        String rawUrl = getApiBaseUrl().getOrElse("");
        if (rawUrl.isBlank()) {
            throw new GradleException(
                    "release 构建必须通过 -PvocaeaseApiBaseUrl 或 VOCAEASE_API_BASE_URL 提供 API 地址");
        }

        URI uri;
        try {
            uri = new URI(rawUrl);
        } catch (URISyntaxException exception) {
            throw invalidUrl(exception);
        }

        boolean valid = uri.isAbsolute()
                && "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && !uri.getHost().isBlank()
                && uri.getRawUserInfo() == null
                && uri.getRawQuery() == null
                && uri.getRawFragment() == null
                && "/".equals(uri.getRawPath());
        if (!valid) {
            throw invalidUrl(null);
        }
    }

    private GradleException invalidUrl(Throwable cause) {
        String message = "release API 地址必须是 HTTPS 域名根地址（例如 https://api.example/），不能包含 /api/ 等路径；接口会自动添加 api/v1";
        return cause == null ? new GradleException(message) : new GradleException(message, cause);
    }
}
