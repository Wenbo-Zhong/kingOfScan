import UIKit
import ComposeApp
import Darwin

final class AiAppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "AI Verification", sessionRole: session.role)
        config.delegateClass = AiSceneDelegate.self
        return config
    }
}
final class AiSceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        window = UIWindow(windowScene: scene)
        let controller = UIViewController()
        controller.view.backgroundColor = .white
        window?.rootViewController = controller
        window?.makeKeyAndVisible()
        let root = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!.appendingPathComponent("ai-evidence")
        IosDocumentAiVerificationKt.runIosDocumentAiVerification(root: root.path) { result in
            print(result)
            exit(result.hasPrefix("FAIL") ? 1 : 0)
        }
    }
}
UIApplicationMain(CommandLine.argc, CommandLine.unsafeArgv, nil, NSStringFromClass(AiAppDelegate.self))
