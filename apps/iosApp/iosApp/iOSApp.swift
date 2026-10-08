import SwiftUI
import BitchatApp

@main struct iOSApp: App {
    @ObservedObject private var launch = AppLaunch.shared

    init() {
        AppLaunch.shared.start()
    }

    var body: some Scene {
        WindowGroup {
            if launch.started {
                ContentView()
            } else {
                // Seen only if the app is open while its Keychain does not answer, which an
                // unlocked phone's does. The reason is there for whoever has to find out why.
                VStack(spacing: 12) {
                    Text("Waiting for the Keychain")
                    Text(launch.waitingFor ?? "")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                        .multilineTextAlignment(.center)
                }
                .padding()
            }
        }
    }
}
