package com.flixclusive.data.configuration

import com.flixclusive.core.datastore.AppSettingsManager
import com.flixclusive.core.network.retrofit.GithubApiService
import com.flixclusive.core.network.retrofit.GithubRawApiService
import com.flixclusive.core.network.util.okhttp.UserAgentManager
import com.flixclusive.core.util.R
import com.flixclusive.core.util.common.configuration.GITHUB_REPOSITORY
import com.flixclusive.core.util.common.configuration.GITHUB_USERNAME
import com.flixclusive.core.util.common.dispatcher.di.ApplicationScope
import com.flixclusive.core.util.common.resource.Resource
import com.flixclusive.core.util.common.ui.UiText
import com.flixclusive.core.util.exception.toNetworkException
import com.flixclusive.core.util.log.errorLog
import com.flixclusive.model.configuration.AppConfig
import com.flixclusive.model.tmdb.category.HomeCategoriesData
import com.flixclusive.model.tmdb.category.SearchCategoriesData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

sealed class UpdateStatus(
    val errorMessage: UiText? = null
) {
    data object Fetching: UpdateStatus()
    data object Maintenance : UpdateStatus()
    data object Outdated : UpdateStatus()
    data object UpToDate : UpdateStatus()
    class Error(errorMessage: UiText?) : UpdateStatus(errorMessage)
}

/**
 *
 * Substitute model for BuildConfig
 * */
data class AppBuild(
    val applicationName: String,
    val applicationId: String,
    val debug: Boolean,
    val versionName: String,
    val build: Long,
    val commitVersion: String,
)

private const val MAX_RETRIES = 5

@Singleton
class AppConfigurationManager @Inject constructor(
    private val githubRawApiService: GithubRawApiService,
    private val githubApiService: GithubApiService,
    private val appSettingsManager: AppSettingsManager,
    @ApplicationScope private val scope: CoroutineScope,
    client: OkHttpClient,
) {
    private val userAgentManager = UserAgentManager(client)

    private var fetchJob: Job? = null

    private val _configurationStatus = MutableStateFlow<Resource<Unit>>(Resource.Loading)
    val configurationStatus = _configurationStatus.asStateFlow()

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Fetching)
    val updateStatus = _updateStatus.asStateFlow()

    var currentAppBuild: AppBuild? = null
        private set

    var appConfig: AppConfig? = null
    var homeCategoriesData: HomeCategoriesData? = null
    var searchCategoriesData: SearchCategoriesData? = null

    private val Resource<Unit>.needsToInitialize: Boolean
        get() = (this is Resource.Success
                && (appConfig == null || homeCategoriesData == null || searchCategoriesData == null))

    init {
        scope.launch {
            _configurationStatus.collectLatest {
                if(it.needsToInitialize)
                    initialize(currentAppBuild)
            }
        }
    }

    fun initialize(appBuild: AppBuild? = null) {
        if(fetchJob?.isActive == true)
            return

        if(this.currentAppBuild == null)
            this.currentAppBuild = appBuild

        fetchJob = scope.launch {
            val retryDelay = 3000L
            for (i in 0..MAX_RETRIES) {
                _configurationStatus.update { Resource.Loading }

                try {
                    checkForUpdates()

                    userAgentManager.loadLatestUserAgents()
                    homeCategoriesData = githubRawApiService.getHomeCategoriesConfig()
                    searchCategoriesData = githubRawApiService.getSearchCategoriesConfig()

                    return@launch _configurationStatus.update { Resource.Success(Unit) }
                } catch (e: Exception) {
                    errorLog(e)

                    if (i == MAX_RETRIES) {
                        val errorMessageId = e.toNetworkException().error!!

                        return@launch _configurationStatus.update { Resource.Failure(errorMessageId) }
                    }
                }

                delay(retryDelay)
            }

            _configurationStatus.update {
                Resource.Failure(R.string.failed_to_init_app)
            }
        }
    }

    suspend fun checkForUpdates() {
        _updateStatus.update { UpdateStatus.Fetching }

        try {
            val appSettings = appSettingsManager.appSettings.data.first()
            val isUsingPrereleaseUpdates = appSettings.isUsingPrereleaseUpdates

            appConfig = githubRawApiService.getAppConfig()

            if(appConfig!!.isMaintenance)
                return _updateStatus.update { UpdateStatus.Maintenance }

            if (isUsingPrereleaseUpdates && currentAppBuild?.debug == false) {
                val lastCommitObject = githubApiService.getLastCommitObject()
                val appCommitVersion = currentAppBuild?.commitVersion
                    ?: throw NullPointerException("appCommitVersion should not be null!")

                val preReleaseTag = "pre-release"
                val preReleaseTagInfo = githubApiService.getTagsInfo().find { it.name == preReleaseTag }

                val shortenedSha = lastCommitObject.lastCommit.sha.shortenSha()
                val isNeedingAnUpdate = appCommitVersion != shortenedSha
                        && lastCommitObject.lastCommit.sha == preReleaseTagInfo?.lastCommit?.sha

                if (isNeedingAnUpdate) {
                    val preReleaseReleaseInfo = githubApiService.getReleaseInfo(tag = preReleaseTag)

                    appConfig = appConfig!!.copy(
                        versionName = "PR-$shortenedSha \uD83D\uDDFF",
                        updateInfo = preReleaseReleaseInfo.releaseNotes,
                        updateUrl = "https://github.com/$GITHUB_USERNAME/$GITHUB_REPOSITORY/releases/download/pre-release/flixclusive-release.apk"
                    )

                    _updateStatus.update { UpdateStatus.Outdated }
                    return
                }

                _updateStatus.update { UpdateStatus.UpToDate }
                return
            } else {
                val isNeedingAnUpdate = appConfig!!.build != -1L && appConfig!!.build > currentAppBuild!!.build

                if(isNeedingAnUpdate) {
                    val releaseInfo = githubApiService.getReleaseInfo(tag = appConfig!!.versionName)

                    appConfig = appConfig!!.copy(updateInfo = releaseInfo.releaseNotes)
                    return _updateStatus.update { UpdateStatus.Outdated }
                }

                return _updateStatus.update { UpdateStatus.UpToDate }
            }
        } catch (e: Exception) {
            errorLog(e)
            val errorMessageId = e.toNetworkException().error!!

            _updateStatus.update { UpdateStatus.Error(errorMessageId) }
        }
    }

    private fun String.shortenSha()
            = substring(0, 7)
}