package my.hinoki.booxreader.data.auth

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
                onSuccess = { _authState.value = AuthState.Success },
                onFailure = { _authState.value = AuthState.Error(it.message ?: "Login failed") }
            )
        }
    }
    
    fun googleLogin(idToken: String, email: String?, name: String?) {
        viewModelScope.launch {
             _authState.value = AuthState.Loading
            val result = authRepo.googleLogin(idToken, email, name)
            result.fold(
                onSuccess = { _authState.value = AuthState.Success },
                onFailure = { _authState.value = AuthState.Error(it.message ?: "Login failed") }
            )
        }
    }

    fun register(email: String, pass: String) {
        viewModelScope.launch {
            _authState.value = AuthState.Loading
            val result = authRepo.register(email, pass)
            result.fold(
                onSuccess = { _authState.value = AuthState.Success },
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

