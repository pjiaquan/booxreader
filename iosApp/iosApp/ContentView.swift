import SwiftUI
import Shared

struct ContentView: View {
    var body: some View {
        TabView {
            LibraryView()
                .tabItem {
                    Label("書庫", systemImage: "books.vertical")
                }
            SettingsView()
                .tabItem {
                    Label("設定", systemImage: "gearshape")
                }
            ProfileView()
                .tabItem {
                    Label("個人", systemImage: "person.crop.circle")
                }
        }
    }
}

struct ContentView_Previews: PreviewProvider {
    static var previews: some View {
        ContentView()
    }
}
