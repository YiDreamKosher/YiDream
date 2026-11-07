package com.yidream.mdm

/** UI fixtures never contact the public release server or install an APK. */
internal object OfflineAppUpdateFixture : AppUpdateClient {
    override suspend fun check() = UpdateManager.CheckResult.UpToDate
    override suspend fun install(update: UpdateManager.UpdateInfo) = SilentInstaller.Result.Failure("UI fixture does not install packages")
}
