package io.github.samzhu.gate;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collection;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 應用程式啟動處理器
 *
 * <p>在應用程式完全啟動後執行初始化任務，包括：
 * <ul>
 *   <li>驗證配置正確性（Profile 衝突檢查）</li>
 *   <li>輸出啟動資訊（URL、Profile、JVM、建置資訊）</li>
 * </ul>
 *
 * @see <a href="https://github.com/jhipster/jhipster-sample-app">JHipster Sample App</a>
 * @see <a href="https://microsoft.github.io/code-with-engineering-playbook/observability/pillars/logging/">Microsoft Engineering Playbook - Logging</a>
 */
@Component
public class ApplicationStartup {

    private static final Logger log = LoggerFactory.getLogger(ApplicationStartup.class);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());

    private final Environment env;
    private final Optional<BuildProperties> buildProperties;
    private final Optional<GitProperties> gitProperties;

    public ApplicationStartup(
            Environment env,
            Optional<BuildProperties> buildProperties,
            Optional<GitProperties> gitProperties) {
        this.env = env;
        this.buildProperties = buildProperties;
        this.gitProperties = gitProperties;
    }

    /**
     * 初始化應用程式配置驗證
     *
     * <p>檢查 Profile 配置是否正確，避免衝突的 Profile 同時啟用
     */
    @PostConstruct
    public void initApplication() {
        Collection<String> activeProfiles = Arrays.asList(env.getActiveProfiles());

        // 檢查 dev 和 prod 不能同時啟用
        if (activeProfiles.contains("dev") && activeProfiles.contains("prod")) {
            log.error("配置錯誤！應用程式不應同時啟用 'dev' 和 'prod' 環境");
        }

        // 檢查 local 和 cloud 不能同時啟用
        if (activeProfiles.contains("local") && activeProfiles.contains("cloud")) {
            log.error("配置錯誤！應用程式不應同時啟用 'local' 和 'cloud' 環境");
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logApplicationStartup();
    }

    private void logApplicationStartup() {
        String protocol = Optional.ofNullable(env.getProperty("server.ssl.key-store"))
            .map(key -> "https")
            .orElse("http");
        String applicationName = env.getProperty("spring.application.name");
        String serverPort = env.getProperty("server.port", "8080");
        String contextPath = Optional.ofNullable(env.getProperty("server.servlet.context-path"))
            .filter(StringUtils::isNotBlank)
            .orElse("/");
        String hostAddress = "localhost";
        try {
            hostAddress = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            log.warn("無法取得主機名稱，使用 `localhost` 作為預設值");
        }

        String[] activeProfiles = env.getActiveProfiles();
        Object profiles = activeProfiles.length > 0
            ? activeProfiles
            : env.getDefaultProfiles();

        // JVM 資訊
        Runtime runtime = Runtime.getRuntime();
        String javaVersion = System.getProperty("java.version");
        String javaVendor = System.getProperty("java.vendor");
        long maxMemoryMB = runtime.maxMemory() / (1024 * 1024);
        int availableProcessors = runtime.availableProcessors();

        // 建置資訊
        String version = buildProperties.map(BuildProperties::getVersion).orElse("N/A");
        String buildTime = buildProperties
            .map(BuildProperties::getTime)
            .map(DATE_FORMATTER::format)
            .orElse("N/A");

        // Git 資訊
        String gitCommit = gitProperties.map(GitProperties::getShortCommitId).orElse("N/A");
        String gitBranch = gitProperties.map(GitProperties::getBranch).orElse("N/A");

        // 可觀測性設定
        String tracingSampling = env.getProperty("management.tracing.sampling.probability", "N/A");

        log.info("""

            ----------------------------------------------------------
            \t應用程式 '{}' 啟動完成！
            ----------------------------------------------------------
            \t存取網址：
            \t  本機：   {}://localhost:{}{}
            \t  外部：   {}://{}:{}{}
            ----------------------------------------------------------
            \t執行環境： {}
            ----------------------------------------------------------
            \t建置資訊：
            \t  版本：   {}
            \t  建置時間：{}
            \t  Git 分支：{}
            \t  Git Commit：{}
            ----------------------------------------------------------
            \tJVM 資訊：
            \t  Java：   {} ({})
            \t  最大記憶體：{} MB
            \t  處理器數量：{}
            ----------------------------------------------------------
            \t可觀測性：
            \t  Tracing 取樣率：{}
            ----------------------------------------------------------""",
            applicationName,
            protocol,
            serverPort,
            contextPath,
            protocol,
            hostAddress,
            serverPort,
            contextPath,
            profiles,
            version,
            buildTime,
            gitBranch,
            gitCommit,
            javaVersion,
            javaVendor,
            maxMemoryMB,
            availableProcessors,
            tracingSampling
        );
    }
}
