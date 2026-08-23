import SwiftUI
import Shared

/// 個人：顯示登入狀態（Phase 4 殼，尚未實作登入 UI）。
struct ProfileView: View {
    @State private var hasToken = false

    var body: some View {
        NavigationView {
            Form {
                Section(header: Text("帳號")) {
                    LabeledContent("登入狀態") {
                        Text(hasToken ? "已登入" : "未登入")
                            .foregroundColor(hasToken ? .green : .secondary)
                    }
                }

                Section {
                    Text("登入/註冊畫面將在後續迭代加入（共用 AuthRepository）。")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .navigationTitle("個人")
            .task {
                hasToken = !(IosTokenProvider().getAccessToken()?.isEmpty ?? true)
            }
        }
    }
}
