.PHONY: vendor project test kit-test app sim clean

SIM ?= iPhone 18 Pro

vendor:
	tools/fetch-vendor.sh

project: vendor
	xcodegen --quiet

kit-test:
	cd NomadKit && swift test

test: project
	xcodebuild test -project NomadPocket.xcodeproj -scheme NomadPocket -destination "platform=iOS Simulator,name=$(SIM)" -derivedDataPath DerivedData -quiet

app: project
	xcodebuild -project NomadPocket.xcodeproj -scheme NomadPocket -destination "generic/platform=iOS" -derivedDataPath DerivedData -allowProvisioningUpdates build -quiet

sim: project
	xcodebuild -project NomadPocket.xcodeproj -scheme NomadPocket -destination "platform=iOS Simulator,name=$(SIM)" -derivedDataPath DerivedData build -quiet

clean:
	rm -rf DerivedData NomadPocket.xcodeproj NomadKit/.build
