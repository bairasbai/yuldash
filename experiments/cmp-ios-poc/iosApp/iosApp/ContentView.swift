import SwiftUI
import Shared

// Оборачивает Kotlin-UIViewController (общий Compose-экран) в SwiftUI.
// MainViewControllerKt.MainViewController() — это функция из shared/src/iosMain/.../MainViewController.kt.
struct ContentView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}
