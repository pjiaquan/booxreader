package my.hinoki.booxreader.ui.auth

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import my.hinoki.booxreader.BooxReaderApp
import my.hinoki.booxreader.data.repo.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AuthViewModel(app: Application) : AndroidViewModel(app) {
    private val authRepo = AuthRepository(app, (app as BooxReaderApp).tokenManager)

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val currentUser = authRepo.getCurrentUser()

    fun login(email: String, pass: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepo.login(email, pass)
            result.fold(
                onSuccess = {
                    (getApplication<Application>() as BooxReaderApp).startRealtimeBookSync()
                    _authState.value = AuthState.Success
                },
                onFailure = { 
                    val msg = it.message ?: getApplication<Application>().getString(my.hinoki.booxreader.R.string.auth_login_failed)
                    _authState.value = AuthState.Error(msg) 
                }
            )
        }
    }
    
    fun resendVerification(email: String, pass: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepo.resendVerification(email, pass)
            result.fold(
                onSuccess = { 
                    val msg = getApplication<Application>().getString(my.hinoki.booxreader.R.string.auth_verification_resent)
                    _authState.value = AuthState.Error(msg) 
                },
                onFailure = { 
                    val msg = it.message ?: getApplication<Application>().getString(my.hinoki.booxreader.R.string.auth_verification_resend_failed)
                    _authState.value = AuthState.Error(msg) 
                }
            )
        }
    }

    fun googleLogin(idToken: String, email: String?, name: String?) {
        viewModelScope.launch {
             _authState.value = AuthState.Loading
            val result = authRepo.googleLogin(idToken, email, name)
            result.fold(
                onSuccess = {
                    (getApplication<Application>() as BooxReaderApp).startRealtimeBookSync()
                    _authState.value = AuthState.Success
                },
                onFailure = { 
                    val msg = it.message ?: getApplication<Application>().getString(my.hinoki.booxreader.R.string.auth_login_failed)
                    _authState.value = AuthState.Error(msg) 
                }
            )
        }
    }

    fun register(email: String, pass: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepo.register(email, pass)
            result.fold(
                onSuccess = { 
                    val msg = getApplication<Application>().getString(my.hinoki.booxreader.R.string.auth_registration_verification_sent)
                    _authState.value = AuthState.Error(msg) 
                },
                onFailure = { 
                    val msg = it.message ?: getApplication<Application>().getString(my.hinoki.booxreader.R.string.auth_registration_failed)
                    _authState.value = AuthState.Error(msg) 
                }
            )
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepo.logout()
            (getApplication<Application>() as BooxReaderApp).stopRealtimeBookSync()
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
