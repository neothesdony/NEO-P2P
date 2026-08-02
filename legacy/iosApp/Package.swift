// swift-tools-version: 5.9
// The swift-tools-version declares the minimum version of Swift required to build this package.

import PackageDescription

let package = Package(
    name: "iosApp",
    platforms: [
        .iOS(.v17)
    ],
    dependencies: [
        .package(url: "https://github.com/InsertKoinIO/koin-spm", from: "3.5.0"),
        .package(url: "https://github.com/firebase/firebase-ios-sdk", from: "10.0.0"),
        .package(url: "https://github.com/Alamofire/Alamofire", from: "5.9.0")
    ],
    targets: [
        .target(
            name: "iosApp",
            dependencies: [
                .product(name: "Koin", package: "koin-spm"),
                .product(name: "FirebaseAnalytics", package: "firebase-ios-sdk"),
                .product(name: "FirebaseAuth", package: "firebase-ios-sdk"),
                .product(name: "Alamofire", package: "Alamofire")
            ],
            path: "iosApp/Sources"
        ),
        .testTarget(
            name: "iosAppTests",
            dependencies: ["iosApp"],
            path: "iosApp/Tests"
        )
    ]
)