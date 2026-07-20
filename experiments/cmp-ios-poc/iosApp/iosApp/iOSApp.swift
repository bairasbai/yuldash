import SwiftUI

// Точка входа iOS-приложения. Показывает общий Compose-UI из Kotlin-модуля :shared.
@main
struct iOSApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
                .ignoresSafeArea(.all)
        }
    }
}
