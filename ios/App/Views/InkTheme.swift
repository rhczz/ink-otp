import SwiftUI
import UIKit

enum Ink {
    static let background = color(0xF6F7F9, 0x090A0C)
    static let surface = color(0xFFFFFF, 0x121418)
    static let sunken = color(0xEDEFF3, 0x1A1D23)
    static let text = color(0x0E1116, 0xF4F6F9)
    static let secondary = color(0x56606E, 0xA7AFBC)
    static let border = color(0xE2E6EB, 0x232830)
    static let accent = color(0x2B4EFF, 0x7C93FF)
    static let accentSoft = color(0xE9EEFF, 0x1B2140)
    static let warning = color(0x8A5A10, 0xFDB022)
    static let danger = color(0xD92D20, 0xF97066)

    static let xs: CGFloat = 4
    static let s: CGFloat = 8
    static let m: CGFloat = 12
    static let l: CGFloat = 16
    static let xl: CGFloat = 20
    static let xxl: CGFloat = 24
    static let xxxl: CGFloat = 32
    static let screenWidth: CGFloat = 560
    static let touch: CGFloat = 48
    static let buttonHeight: CGFloat = 52
    static let controlRadius: CGFloat = 14
    static let cardRadius: CGFloat = 18
    static let avatarRadius: CGFloat = 10
    static let avatarSize: CGFloat = 36
    static let ringSize: CGFloat = 20

    private static let lightAvatars: [(UInt32, UInt32)] = [
        (0xE7EAF0, 0x3D4654), (0xE6EBFF, 0x2B4EFF), (0xDEF4F0, 0x0B6B5B), (0xFAEEDC, 0x8A5A10),
        (0xFBE7E9, 0xA32638), (0xEEE8FB, 0x5B3FA6), (0xE9F2DE, 0x4A6B22), (0xE3F1FA, 0x1D5E86),
    ]
    private static let darkAvatars: [(UInt32, UInt32)] = [
        (0x252A33, 0xC6CEDA), (0x1C2547, 0xA9B8FF), (0x12312D, 0x7FE0CC), (0x33270F, 0xF5C878),
        (0x331A1F, 0xF5A3AC), (0x251C3B, 0xC4B0F5), (0x222C15, 0xBBD98C), (0x16283A, 0x9FD0EC),
    ]

    static func avatar(_ name: String?) -> (Color, Color) {
        let key = name.flatMap { $0.isEmpty ? nil : $0 } ?? "?"
        let hash = key.lowercased().utf16.reduce(0) { $0 &* 31 &+ Int($1) }
        let index = Int(UInt(bitPattern: hash) % UInt(lightAvatars.count))
        let light = lightAvatars[index]
        let dark = darkAvatars[index]
        return (color(light.0, dark.0), color(light.1, dark.1))
    }

    private static func color(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            let hex = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(
                red: CGFloat((hex >> 16) & 0xff) / 255,
                green: CGFloat((hex >> 8) & 0xff) / 255,
                blue: CGFloat(hex & 0xff) / 255,
                alpha: 1
            )
        })
    }
}

struct InkPage<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Ink.l) { content }
                .frame(maxWidth: Ink.screenWidth)
                .padding(.horizontal, Ink.l)
                .padding(.vertical, Ink.l)
                .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Ink.background)
    }
}

struct InkCard<Content: View>: View {
    var highlighted = false
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: Ink.m) { content }
            .padding(Ink.l)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(highlighted ? Ink.accentSoft : Ink.surface,
                        in: RoundedRectangle(cornerRadius: Ink.cardRadius))
            .overlay {
                RoundedRectangle(cornerRadius: Ink.cardRadius).strokeBorder(Ink.border)
                    .allowsHitTesting(false)
            }
    }
}

struct InkButtonStyle: ButtonStyle {
    var prominent = true
    @Environment(\.isEnabled) private var enabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.body.weight(.semibold))
            .foregroundStyle(prominent ? Ink.surface : Ink.text)
            .padding(.horizontal, Ink.m)
            .padding(.vertical, Ink.s)
            .frame(maxWidth: .infinity, minHeight: Ink.buttonHeight)
            .background(prominent ? Ink.text : Ink.surface,
                        in: RoundedRectangle(cornerRadius: Ink.controlRadius))
            .overlay {
                RoundedRectangle(cornerRadius: Ink.controlRadius).strokeBorder(Ink.border)
                    .allowsHitTesting(false)
            }
            .opacity(enabled ? (configuration.isPressed ? 0.8 : 1) : 0.45)
    }
}

struct InkField: View {
    let title: LocalizedStringKey
    @Binding var value: String
    var secret = false
    var permitsReveal = true
    var keyboard: UIKeyboardType = .default
    var identifier = ""
    @State private var revealed = false

    var body: some View {
        VStack(alignment: .leading, spacing: Ink.xs) {
            Text(title).font(.caption.weight(.medium)).foregroundStyle(Ink.secondary)
            HStack(spacing: Ink.s) {
                Group {
                    if secret && !revealed {
                        SecureField(title, text: $value)
                    } else {
                        TextField(title, text: $value)
                    }
                }
                .keyboardType(keyboard)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .privacySensitive()
                .accessibilityIdentifier(identifier)
                if secret && permitsReveal {
                    Button {
                        revealed.toggle()
                    } label: {
                        Image(systemName: revealed ? "eye.slash" : "eye").frame(minWidth: Ink.touch)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(revealed ? Text("Hide text") : Text("Show text"))
                }
            }
            .padding(.leading, Ink.m)
            .padding(.trailing, secret && permitsReveal ? 0 : Ink.m)
            .frame(minHeight: Ink.touch)
            .background(Ink.surface, in: RoundedRectangle(cornerRadius: Ink.controlRadius))
            .overlay {
                RoundedRectangle(cornerRadius: Ink.controlRadius).strokeBorder(Ink.border)
                    .allowsHitTesting(false)
            }
        }
    }
}

struct InkHeading: View {
    let title: LocalizedStringKey
    let message: LocalizedStringKey
    let symbol: String

    var body: some View {
        VStack(spacing: Ink.m) {
            Image(systemName: symbol)
                .font(.system(size: 44, weight: .regular))
                .accessibilityHidden(true)
            Text(title).font(.title.weight(.semibold)).accessibilityAddTraits(.isHeader)
            Text(message).foregroundStyle(Ink.secondary)
        }
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.vertical, Ink.xxl)
    }
}

func inkText(_ value: String) -> Text {
    Text(LocalizedStringKey(value))
}
