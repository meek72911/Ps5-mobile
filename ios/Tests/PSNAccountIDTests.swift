import Foundation
import XCTest
@testable import MobilePlay

final class PSNAccountIDTests: XCTestCase {
	func testNumericAndBase64DecodeToTheSameLittleEndianBytes() {
		let expected = Data([1, 2, 3, 4, 5, 6, 7, 8])
		XCTAssertEqual(PSNAuth.decodeAccountID(" 578437695752307201\n"), expected)
		XCTAssertEqual(PSNAuth.decodeAccountID("AQIDBAUGBwg="), expected)
		XCTAssertEqual(PSNAuth.decodeAccountID("18446744073709551615"), Data(repeating: 255, count: 8))
	}

	func testRejectsOverflowAndInvalidBase64() {
		XCTAssertNil(PSNAuth.decodeAccountID("18446744073709551616"))
		XCTAssertNil(PSNAuth.decodeAccountID("AQID"))
		XCTAssertNil(PSNAuth.decodeAccountID(""))
	}
}
