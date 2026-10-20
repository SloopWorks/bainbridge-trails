import AuthenticationServices
import ComposeApp
import CryptoKit
import UIKit

/// Sign in with Apple for the shared Kotlin `AuthRepository` (registered via `IosAuth.configure` at startup).
/// Returns Apple's identity token, the one-time authorization code (server revokes the token on account
/// deletion, TL-10) and the SHA-256 nonce sent to Apple (the server checks the token's `nonce` claim).
/// Needs the "Sign in with Apple" capability on the app's signing team for real devices.
final class AppleSignIn: NSObject, AppleSignInBridge, ASAuthorizationControllerDelegate,
    ASAuthorizationControllerPresentationContextProviding {
    private var completion: ((AppleSignInOutcome) -> Void)?
    private var hashedNonce: String?

    func signIn(onResult: @escaping (AppleSignInOutcome) -> Void) {
        DispatchQueue.main.async {
            self.finish(AppleSignInOutcome(idToken: nil, authorizationCode: nil, nonce: nil, cancelled: true, error: nil)) // supersede
            self.completion = onResult
            let raw = UUID().uuidString + UUID().uuidString
            let hashed = SHA256.hash(data: Data(raw.utf8)).map { String(format: "%02x", $0) }.joined()
            self.hashedNonce = hashed
            let request = ASAuthorizationAppleIDProvider().createRequest()
            request.requestedScopes = [] // no name/email: the server stores only provider + sub
            request.nonce = hashed
            let controller = ASAuthorizationController(authorizationRequests: [request])
            controller.delegate = self
            controller.presentationContextProvider = self
            controller.performRequests()
        }
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
              let tokenData = credential.identityToken,
              let token = String(data: tokenData, encoding: .utf8) else {
            finish(AppleSignInOutcome(idToken: nil, authorizationCode: nil, nonce: nil, cancelled: false, error: "No identity token"))
            return
        }
        let code = credential.authorizationCode.flatMap { String(data: $0, encoding: .utf8) }
        finish(AppleSignInOutcome(idToken: token, authorizationCode: code, nonce: hashedNonce, cancelled: false, error: nil))
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        let cancelled = (error as? ASAuthorizationError)?.code == .canceled
        finish(AppleSignInOutcome(idToken: nil, authorizationCode: nil, nonce: nil, cancelled: cancelled,
                                  error: cancelled ? nil : error.localizedDescription))
    }

    func cancel() {
        DispatchQueue.main.async {
            self.completion = nil
            self.hashedNonce = nil
        }
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let windows = scenes.flatMap { $0.windows }
        if let key = windows.first(where: { $0.isKeyWindow }) ?? windows.first { return key }
        // No window yet: anchor to a window in the first (foreground-first) scene, never a detached one.
        let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
        if let scene { return UIWindow(windowScene: scene) }
        return ASPresentationAnchor()
    }

    private func finish(_ outcome: AppleSignInOutcome) {
        guard let done = completion else { return }
        completion = nil
        hashedNonce = nil
        done(outcome)
    }
}
