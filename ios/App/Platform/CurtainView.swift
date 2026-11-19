import SwiftUI
import UIKit

/// Covers the scene rather than a single SwiftUI view, including sheets and popovers.
struct CurtainView: UIViewRepresentable {
    func makeUIView(context: Context) -> CurtainHost { CurtainHost(frame: .zero) }
    func updateUIView(_ view: CurtainHost, context: Context) {}
}

@MainActor
final class CurtainHost: UIView {
    private var curtain: UIWindow?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        NotificationCenter.default.removeObserver(self)
        guard let scene = window?.windowScene else {
            curtain?.isHidden = true
            curtain = nil
            return
        }
        NotificationCenter.default.addObserver(self, selector: #selector(conceal),
                                               name: UIScene.willDeactivateNotification, object: scene)
        NotificationCenter.default.addObserver(self, selector: #selector(updateCapture),
                                               name: UIScene.didActivateNotification, object: scene)
        NotificationCenter.default.addObserver(self, selector: #selector(updateCapture),
                                               name: UIScreen.capturedDidChangeNotification, object: scene.screen)
        updateCapture()
    }

    @objc private func conceal() {
        guard let scene = window?.windowScene else { return }
        if curtain == nil {
            let shield = UIWindow(windowScene: scene)
            shield.windowLevel = .alert + 1
            shield.isUserInteractionEnabled = false
            let controller = UIViewController()
            controller.view.backgroundColor = UIColor.systemBackground
            shield.rootViewController = controller
            curtain = shield
        }
        curtain?.isHidden = false
    }

    @objc private func updateCapture() {
        guard let scene = window?.windowScene else { return }
        if scene.activationState != .foregroundActive || scene.screen.isCaptured {
            conceal()
        } else {
            curtain?.isHidden = true
        }
    }
}
