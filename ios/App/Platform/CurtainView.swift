import SwiftUI
import UIKit

enum CurtainMode: Equatable {
    case clear, inactive, capture

    init(sceneIsActive: Bool, captured: Bool) {
        if !sceneIsActive { self = .inactive }
        else { self = captured ? .capture : .clear }
    }

    var coversContent: Bool { self != .clear }
    var showsNotice: Bool { self == .capture }
}

struct CurtainView: UIViewRepresentable {
    @Binding var mode: CurtainMode

    func makeUIView(context: Context) -> CurtainHost {
        let host = CurtainHost(frame: .zero)
        let mode = $mode
        host.onModeChange = { value in
            DispatchQueue.main.async { mode.wrappedValue = value }
        }
        return host
    }

    func updateUIView(_ view: CurtainHost, context: Context) {}

    static func dismantleUIView(_ view: CurtainHost, coordinator: Void) {
        view.detach()
    }
}

/// Protects the entire presented hierarchy, including UIKit and SwiftUI modals.
@MainActor
final class CurtainHost: UIView {
    var onModeChange: ((CurtainMode) -> Void)?

    private weak var contentWindow: UIWindow?
    private var shield: UIWindow?
    private var previousAccessibilityHidden: Bool?
    private var previousInteractionEnabled: Bool?
    private var mode: CurtainMode = .inactive
    private var sceneIsActive = false
    private var observesCaptureTrait = false

    override func didMoveToWindow() {
        super.didMoveToWindow()
        guard let window, let scene = window.windowScene else {
            detach()
            return
        }
        guard contentWindow !== window else { return }
        detach()
        contentWindow = window
        sceneIsActive = scene.activationState == .foregroundActive

        if !observesCaptureTrait {
            registerForTraitChanges([UITraitSceneCaptureState.self]) { (view: CurtainHost, _) in
                view.refresh()
            }
            observesCaptureTrait = true
        }

        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(deactivate(_:)),
                           name: UIScene.willDeactivateNotification, object: scene)
        center.addObserver(self, selector: #selector(activate(_:)),
                           name: UIScene.didActivateNotification, object: scene)
        center.addObserver(self, selector: #selector(captureOrKeyChanged(_:)),
                           name: UIScreen.capturedDidChangeNotification, object: scene.screen)
        center.addObserver(self, selector: #selector(captureOrKeyChanged(_:)),
                           name: UIWindow.didBecomeKeyNotification, object: window)
        refresh()
    }

    func detach() {
        NotificationCenter.default.removeObserver(self)
        restoreContentWindow()
        shield?.isHidden = true
        shield = nil
        contentWindow = nil
        sceneIsActive = false
        mode = .inactive
    }

    @objc private func deactivate(_ notification: Notification) {
        sceneIsActive = false
        refresh()
    }

    @objc private func activate(_ notification: Notification) {
        sceneIsActive = true
        refresh()
    }

    @objc private func captureOrKeyChanged(_ notification: Notification) {
        refresh()
    }

    private func refresh() {
        guard let window = contentWindow, let scene = window.windowScene else { return }
        let capture = traitCollection.sceneCaptureState
        let captured = capture == .active || (capture == .unspecified && scene.screen.isCaptured)
        let previous = mode
        let next = CurtainMode(sceneIsActive: sceneIsActive, captured: captured)
        mode = next

        if next.coversContent {
            showCurtain(next, over: window, in: scene)
            if next.showsNotice && previous != next,
               let notice = (shield?.rootViewController as? CurtainNoticeController)?.noticeElement {
                UIAccessibility.post(notification: .screenChanged, argument: notice)
            }
        } else {
            restoreContentWindow()
            shield?.isHidden = true
            if previous.showsNotice {
                UIAccessibility.post(notification: .screenChanged, argument: nil)
            }
        }
        onModeChange?(next)
    }

    private func showCurtain(_ mode: CurtainMode, over window: UIWindow, in scene: UIWindowScene) {
        if shield == nil {
            let curtain = UIWindow(windowScene: scene)
            curtain.windowLevel = .alert + 1
            curtain.rootViewController = CurtainNoticeController()
            shield = curtain
        }
        guard let shield, let notice = shield.rootViewController as? CurtainNoticeController else { return }
        notice.show(mode)
        shield.isHidden = false
        if previousAccessibilityHidden == nil { previousAccessibilityHidden = window.accessibilityElementsHidden }
        window.accessibilityElementsHidden = true

        if mode.showsNotice {
            if previousInteractionEnabled == nil { previousInteractionEnabled = window.isUserInteractionEnabled }
            window.isUserInteractionEnabled = false
            if window.isKeyWindow {
                window.endEditing(true)
                shield.makeKey()
            }
        } else {
            restoreInput()
        }
    }

    private func restoreInput() {
        guard let window = contentWindow else { return }
        if let previousInteractionEnabled {
            window.isUserInteractionEnabled = previousInteractionEnabled
            self.previousInteractionEnabled = nil
        }
        if shield?.isKeyWindow == true && !window.isHidden {
            window.makeKey()
        }
    }

    private func restoreContentWindow() {
        if let previousAccessibilityHidden {
            contentWindow?.accessibilityElementsHidden = previousAccessibilityHidden
            self.previousAccessibilityHidden = nil
        }
        restoreInput()
    }
}

@MainActor
final class CurtainNoticeController: UIViewController {
    private let heading = UILabel()
    private let explanation = UILabel()
    private let notice = UIStackView()

    var noticeElement: UILabel? { notice.isHidden ? nil : heading }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        view.accessibilityViewIsModal = true

        heading.text = String(localized: "Screen sharing active")
        heading.font = .preferredFont(forTextStyle: .title1)
        heading.textColor = .label
        heading.accessibilityTraits.insert(.header)

        explanation.text = String(localized: "Stop recording or mirroring to access InkOTP.")
        explanation.font = .preferredFont(forTextStyle: .body)
        explanation.textColor = .secondaryLabel
        for label in [heading, explanation] {
            label.adjustsFontForContentSizeCategory = true
            label.numberOfLines = 0
            label.textAlignment = .center
            label.isAccessibilityElement = true
            notice.addArrangedSubview(label)
        }

        notice.axis = .vertical
        notice.spacing = Ink.m
        notice.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(notice)

        view.accessibilityElements = [heading, explanation]
        let safe = view.safeAreaLayoutGuide
        let width = notice.widthAnchor.constraint(equalTo: safe.widthAnchor, constant: -2 * Ink.xxl)
        width.priority = .defaultHigh
        NSLayoutConstraint.activate([
            width,
            notice.widthAnchor.constraint(lessThanOrEqualToConstant: Ink.screenWidth),
            notice.centerXAnchor.constraint(equalTo: safe.centerXAnchor),
            notice.centerYAnchor.constraint(equalTo: safe.centerYAnchor),
            notice.leadingAnchor.constraint(greaterThanOrEqualTo: safe.leadingAnchor, constant: Ink.xxl),
            notice.trailingAnchor.constraint(lessThanOrEqualTo: safe.trailingAnchor, constant: -Ink.xxl),
        ])
    }

    func show(_ mode: CurtainMode) {
        loadViewIfNeeded()
        notice.isHidden = !mode.showsNotice
        view.accessibilityElementsHidden = !mode.showsNotice
    }
}
