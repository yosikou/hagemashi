# hagemashi

Kotlin / Jetpack Compose の単一Activityアプリです。画面中央に「Hello Android」を表示します。
Android 8.0（API 26）以上に対応し、Android Studioは不要です。

## Android端末だけで開発する

1. Android端末のブラウザでGitHubを開き、作業ブランチを作成します。
2. GitHubのファイル編集機能、またはChatGPT Work + Codexで変更し、Pull Requestを作成します。
3. PRの「Android debug APK」チェックが成功したら、そのActions実行を開きます。
4. ログインした状態で実行画面下部のArtifactsから `hagemashi-debug-apk` をダウンロードします。
5. ZIPを展開して `app-debug.apk` を開きます。必要に応じてブラウザやファイル管理アプリの「不明なアプリのインストール」を許可します。

手動実行するには、まずこのワークフローをmainに取り込む必要があります。その後はActions → Android debug APK → Run workflowからブランチを選んで手動実行もできます。
PR作成時・PR更新時と各ブランチへのpushでも自動実行します。Actionsが無効な場合はリポジトリ設定で有効にしてください。
Artifactsは14日間保持します。期限切れの場合は再実行してください。

ビルドはGitHubのLinuxランナーで行うため、Android端末にJDK・SDKを入れる必要はありません。
CIはUbuntu 24.04ランナーに導入済みのAndroid SDKを利用します。
デバッグAPKは自動生成したデバッグ鍵で署名します。配布用のリリース署名ではなく、別の鍵で作られたAPKへの上書きインストールは失敗することがあります。
その場合は既存アプリをアンインストールして再インストールします（アプリのデータは削除されます）。

## 採用バージョンと互換性

| ツール・設定 | バージョン |
| --- | --- |
| JDK / Java・Kotlin JVMターゲット | 17 |
| Gradle Wrapper | 8.11.1 |
| Android Gradle Plugin（AGP） | 8.10.1 |
| Kotlin / Compose compiler Gradle plugin | 2.2.21 |
| Compose BOM | 2025.04.01 |
| Activity Compose | 1.10.1 |
| compileSdk / targetSdk | 35 |
| minSdk | 26 |
| Android SDK Build Tools | 35.0.0 |

確認根拠:

- [Kotlin公式互換性表](https://github.com/JetBrains/kotlin-web-site/blob/52e6b6cafda7e0542d2166ea7fb62c990ede8a11/docs/topics/gradle/gradle-configure-project.md): Kotlin 2.2.20–2.2.21はGradle 7.6.3–8.14、AGP 7.3.1–8.11.1をサポートします。
- [AGP 8.10公式リリース情報](https://developer.android.com/build/releases/past-releases/agp-8-10-0-release-notes): Gradle 8.11.1・JDK 17に対応する系列です。公式配布のAGP 8.10.1 JAR内の `VersionCheckPlugin.GRADLE_MIN_VERSION` も8.11.1であることを確認しています。
- [AndroidのKotlin対応表](https://developer.android.com/build/kotlin-support): Kotlin 2.2に必要なAGP 8.10以上という条件も満たします。
- [Gradle 8.11.1の公式互換性情報](https://github.com/gradle/gradle/blob/v8.11.1/platforms/documentation/docs/src/docs/userguide/releases/compatibility.adoc): JDK 17で実行できます。
- [Kotlin公式Compose移行ガイド](https://github.com/JetBrains/kotlin-web-site/blob/52e6b6cafda7e0542d2166ea7fb62c990ede8a11/docs/topics/compose-compiler-migration-guide.md): Kotlin 2.0以降は同じバージョンのComposeコンパイラプラグインを使います。BOMはComposeライブラリを管理し、コンパイラのバージョンは管理しません。
- [Google MavenのCompose BOM定義](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2025.04.01/compose-bom-2025.04.01.pom)でComposeライブラリを揃えています。

## コマンドラインでビルドする

Linux / macOSではJDK 17とAndroid SDK Command-line Toolsを用意します。
SDKのライセンスに同意して、必要なパッケージを導入してください。
`JAVA_HOME` はJDK 17、`ANDROID_HOME` はSDKの実際の配置先に設定します。
SDKの `cmdline-tools/latest/bin` と `platform-tools` をPATHに追加します。

```sh
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
./gradlew --no-daemon :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
```

Windowsでは `./gradlew` を `gradlew.bat` に置き換えます。
Gradle本体やKotlinコンパイラの別途インストールは不要です。WrapperがGradleを取得し、配布ZIPのSHA-256を検証します。
初回はGoogle Maven・Maven Central・Gradleの配布元へのネットワーク接続が必要です。

- アプリAPK: `app/build/outputs/apk/debug/app-debug.apk`
- UIテストAPK: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Lintレポート: `app/build/reports/lint-results-debug.html`

## テスト

CIはAndroid Lint、アプリAPKとUIテストAPKのコンパイルを行います。エミュレーターは起動しません。
`MainActivityTest` は起動後の「Hello Android」の表示を確認する実機・エミュレーター用テストです。
API 26以上のデバイスをADBで接続した環境で実行します。

```sh
adb devices
./gradlew :app:connectedDebugAndroidTest
```

UIテストAPKのビルド成功は、UIテストの実行成功を意味しません。
単体テストは現時点ではありません。画面中央への配置やシステムバーとの見え方は実機でも確認してください。
