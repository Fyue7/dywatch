plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

// 依赖锁定（审计 F-STACK-2）：解析结果写进 gradle.lockfile，构建不再随远端漂移。
// 升级依赖后要重新生成：gradlew :app:dependencies --write-locks
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
