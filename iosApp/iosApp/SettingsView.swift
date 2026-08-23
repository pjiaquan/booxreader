import SwiftUI
import Shared

/// 設定：顯示後端 URL、手動同步、連線檢查（透過 shared UserSyncRepository）。
struct SettingsView: View {
    @State private var backendUrl: String = ""
    @State private var statusMessage: String?
    @State private var isWorking = false

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("伺服器")) {
                    HStack {
                        Text("後端 URL")
                        Spacer()
                        Text(backendUrl)
                            .foregroundColor(.secondary)
                    }
                }

                Section(header: Text("同步")) {
                    Button {
                        Task { await runSync() }
                    } label: {
                        HStack {
                            Text("手動同步書籍")
                            Spacer()
                            if isWorking {
                                ProgressView()
                            }
                        }
                    }
                    .disabled(isWorking)

                    Button {
                        Task { await runStorageCheck() }
                    } label: {
                        Text("連線/儲存檢查")
                    }
                    .disabled(isWorking)

                    if let statusMessage = statusMessage {
                        Text(statusMessage)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                Section(header: Text("關於")) {
                    HStack {
                        Text("版本")
                        Spacer()
                        Text("iOS 殼 v0.1（Phase 4）")
                            .foregroundColor(.secondary)
                    }
                    Text("閱讀引擎（Readium Swift Toolkit）尚未整合；目前僅驗證 shared 層與資料庫連線。")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("設定")
            .task {
                backendUrl = IosTokenProvider().getBackendUrl()
            }
        }
    }

    private func runSync() async {
        isWorking = true
        defer { isWorking = false }
        do {
            let count = try await SharedAsync.pullBooks(SharedModule.syncRepo)
            statusMessage = "已同步 \(count) 本書"
        } catch {
            statusMessage = "同步失敗：\(error.localizedDescription)"
        }
    }

    private func runStorageCheck() async {
        isWorking = true
        defer { isWorking = false }
        do {
            let result = try await SharedAsync.checkStorage(SharedModule.syncRepo)
            statusMessage = result.ok ? "連線正常" : (result.message ?? "連線異常")
        } catch {
            statusMessage = "檢查失敗：\(error.localizedDescription)"
        }
    }
}
