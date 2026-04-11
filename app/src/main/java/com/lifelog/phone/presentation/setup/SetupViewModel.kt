package com.lifelog.phone.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lifelog.phone.data.SettingsRepository
import com.lifelog.phone.data.remote.LifeLogApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val api: LifeLogApi
) : ViewModel() {

    private val _baseUrl = MutableStateFlow("")
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    private val _token = MutableStateFlow("")
    val token: StateFlow<String> = _token.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    init {
        _baseUrl.value = settingsRepository.lifeLogSyncUrl
        _token.value = settingsRepository.lifeLogSyncToken
    }

    fun updateBaseUrl(value: String) {
        _baseUrl.value = value
    }

    fun updateToken(value: String) {
        _token.value = value
    }

    fun connect(onSuccess: () -> Unit) {
        viewModelScope.launch {
            _isLoading.value = true
            _status.value = "Connecting..."

            val url = _baseUrl.value.trim()
            if (url.isEmpty()) {
                _status.value = "Please enter server URL"
                _isLoading.value = false
                return@launch
            }

            val result = api.health(url)
            if (result.isSuccess && result.getOrNull() == true) {
                settingsRepository.lifeLogSyncUrl = url
                settingsRepository.lifeLogSyncToken = _token.value.trim()
                _isConnected.value = true
                _status.value = "Connected!"
                _isLoading.value = false
                onSuccess()
            } else {
                _status.value = "Connection failed: ${result.exceptionOrNull()?.message}"
                _isLoading.value = false
            }
        }
    }
}
