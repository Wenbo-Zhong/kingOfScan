import UIKit
import ComposeApp
import Darwin

final class AppDelegate: UIResponder, UIApplicationDelegate {
    func application(_ application: UIApplication, configurationForConnecting session: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        let config = UISceneConfiguration(name: "Verification", sessionRole: session.role)
        config.delegateClass = SceneDelegate.self
        return config
    }
}

final class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options: UIScene.ConnectionOptions) {
        guard let scene = scene as? UIWindowScene else { return }
        window = UIWindow(windowScene: scene)
        let controller = UIViewController()
        controller.view.backgroundColor = .white
        window?.rootViewController = controller
        window?.makeKeyAndVisible()
        let output = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!.appendingPathComponent("evidence")
        IosOfflineVerificationKt.runIosOfflineVerification(root: output.path) { result in
            print(result)
            exit(result.hasPrefix("FAIL") ? 1 : 0)
        }
    }
}
UIApplicationMain(CommandLine.argc, CommandLine.unsafeArgv, nil, NSStringFromClass(AppDelegate.self))
