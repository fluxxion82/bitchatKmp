import SwiftUI
import BitchatApp

@main struct iOSApp: App {

    init() {
        startKoin()

        Task {
            do {
                var initializeApplication = KotlinDependencies.shared.initializeApplication
                let result = try await initializeApplication.invoke(param: KotlinUnit())
                print("init app use case return type:", result as Any)
            } catch {
                print(error)
            }
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
