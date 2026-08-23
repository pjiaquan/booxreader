import SwiftUI
import Shared

/// 書庫：從 shared AppDatabase（Room KMP + BundledSQLiteDriver）列出書籍。
struct LibraryView: View {
    @State private var books: [BookEntity] = []
    @State private var errorMessage: String?

    var body: some View {
        NavigationView {
            Group {
                if let errorMessage = errorMessage {
                    VStack(spacing: 12) {
                        Image(systemName: "exclamationmark.triangle")
                            .font(.largeTitle)
                            .foregroundColor(.orange)
                        Text(errorMessage)
                            .multilineTextAlignment(.center)
                            .foregroundColor(.secondary)
                    }
                    .padding()
                } else if books.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "books.vertical")
                            .font(.largeTitle)
                            .foregroundColor(.secondary)
                        Text("書庫為空")
                            .foregroundColor(.secondary)
                        Text("在 Android 版同步的書籍會出現在這裡（Phase 4 殼，尚未實作匯入）")
                            .font(.caption)
                            .multilineTextAlignment(.center)
                            .foregroundColor(.secondary)
                    }
                    .padding()
                } else {
                    List(books, id: \.bookId) { book in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(book.title ?? "未命名")
                                .font(.headline)
                            Text(book.fileUri)
                                .font(.caption)
                                .foregroundColor(.secondary)
                                .lineLimit(1)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .navigationTitle("書庫")
            .task {
                await loadBooks()
            }
            .refreshable {
                await loadBooks()
            }
        }
    }

    private func loadBooks() async {
        do {
            books = try await SharedAsync.getAllBooks()
            errorMessage = nil
        } catch {
            errorMessage = "讀取書庫失敗：\(error.localizedDescription)"
        }
    }
}
