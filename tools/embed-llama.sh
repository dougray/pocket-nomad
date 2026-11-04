#!/bin/sh
# Xcode build phase: copy and sign llama.framework into the app for device builds.
[ "$PLATFORM_NAME" = "iphoneos" ] || exit 0
src="$PROJECT_DIR/Vendor/llama.xcframework/ios-arm64/llama.framework"
dst="$TARGET_BUILD_DIR/$FRAMEWORKS_FOLDER_PATH"
mkdir -p "$dst"
rsync -a --delete "$src" "$dst/"
if [ -n "$EXPANDED_CODE_SIGN_IDENTITY" ]; then
  codesign --force --sign "$EXPANDED_CODE_SIGN_IDENTITY" --preserve-metadata=identifier,entitlements "$dst/llama.framework"
fi
