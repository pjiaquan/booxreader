package my.hinoki.booxreader.data.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.data.repo.AuthRepository
import my.hinoki.booxreader.data.repo.UserSyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val authRepo = AuthRepository(app, (app as BooxReaderApp).tokenManager)
    private val syncRepo = UserSyncRepository(app)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val currentUser = authRepo.getCurrentUser()

    fun login(email: String, pass: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepo.login(email, pass)
            result.fold(
                onSuccess = {
                    syncUserData()
                    _authState.value = AuthState.Success
                },
                onFailure = { _authState.value = AuthState.Error(it.message ?: "Login failed") }
            )
        }
    }
    
    fun resendVerification(email: String, pass: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepo.resendVerification(email, pass)
            result.fold(
                onSuccess = { _authState.value = AuthState.Error("驗證信已重新寄出，請檢查信箱") },
                onFailure = { _authState.value = AuthState.Error(it.message ?: "重寄驗證信失敗") }
            )
        }
    }

    fun googleLogin(idToken: String, email: String?, name: String?) {
        viewModelScope.launch {
             _authState.value = AuthState.Loading
            val result = authRepo.googleLogin(idToken, email, name)
            result.fold(
                onSuccess = {
                    syncUserData()
                    _authState.value = AuthState.Success
                },
                onFailure = { _authState.value = AuthState.Error(it.message ?: "Login failed") }
            )
        }
    }

    private suspend fun syncUserData() {
        runCatching {
            android.util.Log.d("AuthViewModel", "開始同步用戶資料...")
            kotlinx.coroutines.coroutineScope {
                val jobs = listOf(
                    async { syncRepo.pullSettingsIfNewer() },
                    async { syncRepo.pullBooks() },
                    async { syncRepo.pullNotes() },
                    async { syncRepo.pullAllProgress() },
                    async { syncRepo.pullBookmarks() }
                )
                jobs.awaitAll()
            }
            android.util.Log.d("AuthViewModel", "用戶資料同步完成")
        }.onFailure {
            android.util.Log.e("AuthViewModel", "用戶資料同步失敗", it)
            it.printStackTrace()
        }
    }

    fun register(email: String, pass: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepo.register(email, pass)
            result.fold(
                onSuccess = { _authState.value = AuthState.Error("已寄出驗證信，請驗證後再登入") },
                onFailure = { _authState.value = AuthState.Error(it.message ?: "Registration failed") }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepo.logout()
            _authState.value = AuthState.Idle
        }
    }
    
    fun resetState() {
        _authState.value = AuthState.Idle
    }
}

sealed class AuthState {
    object Idle : AuthState()
    object Loading : AuthState()
    object Success : AuthState()
    data class Error(val message: String) : AuthState()
}
