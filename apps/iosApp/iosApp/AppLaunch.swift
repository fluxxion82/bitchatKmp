import SwiftUI
import UIKit
import BitchatApp

/// Starts the app once the Keychain lets it.
///
/// iOS launches the app in the background while the phone is locked (Bluetooth state restoration).
/// Before the first unlock after a restart, and while items saved by an older build are still
/// readable only when unlocked, the identity cannot be read then. The start says so instead of
/// failing, nothing is run, and it is tried again whenever the phone may have been unlocked. The
/// two notifications are only occasions to try: neither promises the Keychain answers.
final class AppLaunch: ObservableObject {
    static let shared = AppLaunch()

    @Published private(set) var started = false
    /// Why the Keychain did not answer at the last try, while the app is not started.
    @Published private(set) var waitingFor: String?
    private var observers: [NSObjectProtocol] = []

    private init() {}

    /// On the main thread.
    func start() {
        guard !started else { return }

        if let reason = startApplication() {
            print("Bitchat: the Keychain is not answering yet, trying again after an unlock: \(reason)")
            waitingFor = reason
            tryAgainAfterUnlock()
            return
        }

        observers.forEach { NotificationCenter.default.removeObserver($0) }
        observers = []
        waitingFor = nil
        started = true

        Task {
            do {
                let initializeApplication = KotlinDependencies.shared.initializeApplication
                let result = try await initializeApplication.invoke(param: KotlinUnit())
                print("init app use case return type:", result as Any)
            } catch {
                print(error)
            }
        }
    }

    private func tryAgainAfterUnlock() {
        guard observers.isEmpty else { return }
        let occasions = [
            UIApplication.protectedDataDidBecomeAvailableNotification,
            UIApplication.didBecomeActiveNotification,
        ]
        observers = occasions.map { occasion in
            NotificationCenter.default.addObserver(forName: occasion, object: nil, queue: .main) { [weak self] _ in
                self?.start()
            }
        }
    }
}
