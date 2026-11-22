import ChiakiKit
import UIKit
import XCTest
@testable import MobilePlay

/// Touch-ownership checks for the virtual controller. They use scale 1 so the
/// expected positions don't depend on which simulator the tests run on.
final class VirtualControlsOverlayTests: XCTestCase {
	private let area = CGRect(x: 0, y: 0, width: 800, height: 400)

	private func makeOverlay(scale: CGFloat = 1) -> VirtualControlsOverlay {
		let overlay = VirtualControlsOverlay(frame: area)
		overlay.setLayout(in: area, systemButtonY: 370, scale: scale)
		return overlay
	}

	/// Cross sits at (maxX - 68k, maxY - 125k + 36k)
	private func crossPoint(scale k: CGFloat) -> CGPoint {
		CGPoint(x: area.maxX - 68 * k, y: area.maxY - 125 * k + 36 * k)
	}

	func testCrossPressAndReleaseLeavesNeutralState() {
		let overlay = makeOverlay()
		let id = ObjectIdentifier(NSObject())
		let down = overlay.begin(id, at: crossPoint(scale: 1), allowsStick: true)
		XCTAssertTrue(down.handled)
		XCTAssertEqual(down.state?.buttons, CK_BUTTON_CROSS.rawValue)
		let up = overlay.end(id)
		XCTAssertEqual(up, VirtualPadState())
	}

	func testTwoFingersOwnTwoButtons() {
		let overlay = makeOverlay()
		let a = ObjectIdentifier(NSObject()), b = ObjectIdentifier(NSObject())
		_ = overlay.begin(a, at: crossPoint(scale: 1), allowsStick: true)
		let both = overlay.begin(b, at: CGPoint(x: area.maxX - 68 - 36, y: area.maxY - 125), allowsStick: true)
		XCTAssertEqual(both.state?.buttons, CK_BUTTON_CROSS.rawValue | CK_BUTTON_BOX.rawValue)
		XCTAssertEqual(overlay.end(a)?.buttons, CK_BUTTON_BOX.rawValue)
		XCTAssertEqual(overlay.end(b), VirtualPadState())
	}

	func testResetClearsOwnedTouches() {
		let overlay = makeOverlay()
		let id = ObjectIdentifier(NSObject())
		_ = overlay.begin(id, at: crossPoint(scale: 1), allowsStick: true)
		overlay.reset()
		XCTAssertNil(overlay.end(id), "a finger dropped by reset() must not report a release")
	}

	func testFloatingStickOwnsItsSideUntilRelease() {
		let overlay = makeOverlay()
		let id = ObjectIdentifier(NSObject())
		let open = CGPoint(x: 150, y: 150)
		let down = overlay.begin(id, at: open, allowsStick: true)
		XCTAssertNotNil(down.state?.leftStick)
		let moved = overlay.move(id, to: CGPoint(x: 200, y: 150))
		XCTAssertGreaterThan(moved?.leftStick?.x ?? 0, 0)
		let second = overlay.begin(ObjectIdentifier(NSObject()), at: CGPoint(x: 160, y: 160), allowsStick: true)
		XCTAssertFalse(second.handled, "a second finger must not take over an occupied stick")
		XCTAssertNil(overlay.end(id)?.leftStick)
	}

	func testTabletScaleMovesControlsInwardAndEnlargesThem() {
		let phone = makeOverlay(scale: 1), tablet = makeOverlay(scale: 1.45)
		XCTAssertEqual(tablet.scale, 1.45, accuracy: 0.001)
		let id = ObjectIdentifier(NSObject())
		let hit = tablet.begin(id, at: crossPoint(scale: 1.45), allowsStick: false)
		XCTAssertEqual(hit.state?.buttons, CK_BUTTON_CROSS.rawValue)
		_ = tablet.end(id)
		_ = phone
	}

	func testFoldableDetectionIsOffOnPad() throws {
		try XCTSkipUnless(UIDevice.current.userInterfaceIdiom == .pad, "iPad simulator only")
		XCTAssertFalse(DeviceLayout.isFoldable)
		XCTAssertFalse(DeviceLayout.isFolded(CGSize(width: 507, height: 700)))
	}
}
