# 開発ルール

- KotlinとJetpack Composeを使用し、単一の `MainActivity` を維持する。
- Android Studioを必須にしない。Android端末のブラウザとGitHub Actionsで編集・ビルド・APK取得を続けられるようにする。
- minSdkは26。APIや依存関係を追加するときはAPI 26で動作するか確認する。
- 文字列は `app/src/main/res/values/strings.xml` に置く。
- この環境はタスクごとに隔離済み。既存チェックアウトを使い、ユーザーの指示なしにGit worktreeを作成しない。

## 変更と依存関係

- mainに直接コミットしない。作業ブランチで変更し、Pull Requestで提出する。
- 作業前にGitの状態を確認し、既存のユーザー変更を保持する。
- バージョンは固定する。JDK・Gradle・AGP・Kotlinの更新は公式互換性表を確認し、Compose compiler pluginをKotlinと同じバージョンにする。
- 互換性を確認した根拠と採用バージョンをREADMEに反映する。
- Wrapper JARを手作業で作らない。公式Gradleのwrapperタスクを使い、配布ZIPのSHA-256を指定する。`gradlew` の実行権限を維持する。
- SDK、キャッシュ、APK、`local.properties`、署名鍵、認証情報をコミットしない。
- アプリの権限・サービス・外部通信や依存関係は必要になってから追加する。

## 検証

JDK 17とAPI 35 / Build Tools 35.0.0を準備し、リポジトリルートで実行する。

```sh
./gradlew --no-daemon :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
git diff --check
```

- UIを変更したら既存の `MainActivityTest` を確認し、API 26以上の接続デバイスがある場合は `./gradlew :app:connectedDebugAndroidTest` を実行する。
- 表示位置やシステムバーとの重なりは実機でも確認する。
- 成功・失敗・未実行を区別する。ゼロ件のテストやテストAPKのコンパイルをテスト実行成功と報告しない。
- CIではLintとAPKビルドを維持し、Artifactsの `hagemashi-debug-apk` にAPKが含まれることを確認する。
- PRに変更目的、検証コマンドと結果、未検証事項を記載する。CI成功を確認してからマージする。
