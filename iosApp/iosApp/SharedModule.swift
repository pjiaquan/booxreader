import SwiftUI
import Shared

/// KMP suspend 函式的 Swift async 橋接（completion-handler 版，相容所有 Kotlin/Native 版本）。
enum SharedAsync {
    static func getAllBooks() async throws -> [BookEntity] {
        try await withCheckedThrowingContinuation { continuation in
            AppDatabase.companion.get().bookDao().getAllBooks { books, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: books ?? [])
                }
            }
        }
    }

    static func pullBooks(_ syncRepo: UserSyncRepository) async throws -> Int32 {
        try await withCheckedThrowingContinuation { continuation in
            syncRepo.pullBooks { count, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: count ?? 0)
                }
            }
        }
    }

    static func checkStorage(_ syncRepo: UserSyncRepository) async throws -> CheckResult {
        try await withCheckedThrowingContinuation { continuation in
            syncRepo.ensureStorageBucketReady { result, error in
                if let error = error {
                    continuation.resume(throwing: error)
                } else if let result = result {
                    continuation.resume(returning: result)
                } else {
                    continuation.resume(throwing: NSError(domain: "Shared", code: -1))
                }
            }
        }
    }
}

/// Swift 側組裝 KMP 依賴（對應 Android 的 create*Repository factory）。
/// 注意：KMP top-level 函式以「檔案名Kt」前綴導出（IosRepositories.kt → IosRepositoriesKt）。
enum SharedModule {
    static let syncRepo: UserSyncRepository = {
        IosRepositoriesKt.createIosUserSyncRepository(
            tokenProvider: IosTokenProvider(),
            baseUrl: nil
        )
    }()

    static let authRepo: AuthRepository = {
        IosRepositoriesKt.createIosAuthRepository(tokenProvider: IosTokenProvider())
    }()

    static let aiNoteRepo: AiNoteRepository = {
        IosRepositoriesKt.createIosAiNoteRepository(syncRepo: nil)
    }()
}
