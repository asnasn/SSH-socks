RELEASE_DIR := release
APK_SRC     := app/build/outputs/apk/release/ssh-socks-unsigned.apk
APK_DEST    := $(RELEASE_DIR)/app-release.apk

.PHONY: build

build:
	chmod +x ./gradlew
	./gradlew assembleRelease
	mkdir -p $(RELEASE_DIR)
	cp $(APK_SRC) $(APK_DEST)
	@echo "APK copied to $(APK_DEST)"

.DEFAULT_GOAL := build
