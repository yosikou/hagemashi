# 手動ChatGPT接続の検証版

このブランチには手動サインイン・暗号化保存・固定ダミーデータの回答生成・接続解除を実装する。**Android実機の動作と、AndroidへのSIWCの正式適用は未確認**。MITの採用やCI成功をサービスの利用承認と扱わない。

## 基点と対象範囲

開始時にPR #3の最新headをGitHubとfetch後のGitで確認した。headは `3fe01e9e4b326da9f3db3d6d03ed334665709f03` で、既存の作業領域に変更はなかった。その最新headから `feat/manual-chatgpt-connection` を作成した。実装PRのbaseは `docs/mit-license-siwc-conditions`。依存順は main → #1 → #2 → #3 → 実装PRで、マージは行わない。

今回の対象は前景での手動操作のみ。予約・定期生成、通知、Health Connect、目標・人格編集、履歴・CSVは実装しない。画面を閉じた状態の生成継続を保証しない。アプリプロセスが終了した未完了認証は保存せず、新しい認証からやり直す。

## 採用した正式フローと制限

2026-10-30に以下の公式資料と認証メタデータを確認した。

- [Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in): システムブラウザ、HTTP loopback、動的client登録、PKCE S256、state、nonceを使用。初回発行client IDと安定したhost IDを保存する。再接続は保存登録を選択し、検証済み本人と一致するまで有効化しない。
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference): アカウントの`/v1/models`から表示可能モデルを取得し、そのslugで`/v1/responses`を呼ぶ。固定モデルへのfallbackはない。SSEの`response.completed`と非空の回答を確認して成功とする。
- [Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations): `store=false`、`stream=true`を指定。`background`・`max_output_tokens`・`temperature`・過去のresponse IDは送らない。
- [Accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions): 手動要求の際に期限が近ければ更新し、更新を直列化して回転refresh token・期限・scopeを原子的に置換する。解除はdiscoveryのrevocation endpointへ送信する。
- [OpenID discovery](https://auth.openai.com/.well-known/openid-configuration): 取得したメタデータでissuer、RS256、JWKS URI、失効先を確認。実行時も公式discovery・JWKSをHTTPSで取得する。

Androidへ明示的に適用されるという確認は得ていない。HTTP loopbackを検証対象とし、App Linksや独自schemeへ置き換えない。外部ブラウザが`127.0.0.1`へ戻るため、ユーザーはブラウザの応答画面からアプリへ手動で戻る。アプリの`usesCleartextTraffic=false`は維持する（ブラウザ自身のloopbackアクセスは別）。

無操作の定期生成条件はこの版では検証しない。APIのbackground非対応とAndroidの予約実行は区別する。非公開API・Cookie・WebViewによるログイン・画面自動操作・APIキー方式は使わない。

## 保存・認証・通信の保護

- トークン・本人情報・登録対応表・host IDを、Android Keystoreの256-bit AES鍵によるAES-GCMで暗号化し、`noBackupFilesDir`へAtomicFileで保存する。書込みごとに新しいIVを使う。
- Androidのバックアップと端末間転送を除外する規則を追加し、`allowBackup=false`も維持する。SDK・キャッシュ・鍵・APKはGitへ追加しない。
- 保存領域の復号や認証タグ検証に失敗しても平文へfallbackしない。読めない保存領域の削除は画面の確認操作を経て行う。実際のKeystore・バックアップ／転送の動作は実機未検証。
- ID tokenはRS256に限定し、公式JWKSで署名、issuer、audience／azp、期限、発行時刻、nonceを検査する。再認証・更新では登録済みsubとの一致も確認する。
- callbackはloopbackにのみbindし、state・path・重複パラメータ・発行client IDを確認する。最大5分で待機を終了し、処理の終了・キャンセルでlistenerを閉じる。1回のローカル接続にも読取り上限とtimeoutを設ける。
- HTTPSリダイレクトは追跡しない。資格情報を別hostへ転送しない。コード交換・更新・失効はform、生成はOAuth bearerで送信する。
- パスワードをアプリへ入力する欄はない。ログやUIへ生の応答・token・認可URL・callbackを出さない。診断はHTTP状態と既知のエラーコードのみ。回答はメモリ内だけで、履歴として保存しない。
- キャンセル・解除時は処理を無効化し、古い処理による保存・表示更新を防ぐ。ネットワーク終了処理はUIスレッドで待たない。更新と同時のキャンセルでは、回転tokenの応答を受け取れず再認証が必要になる可能性があるため、別途実機確認が必要。
- 接続解除は1回の時間制限つき失効要求を試し、確認できなくても端末のtokenを削除する。未確認時はChatGPT設定でも解除するよう案内する。再接続用の非token登録情報とhost IDは保持する。バックグラウンド再試行はない。

## Android端末だけで実施する手順

前提: API 26以上のAndroid端末、外部ブラウザ、対象となるChatGPTアカウント。公式に明記されたeligibleなPlus／Proでも、実際の利用可否はアカウント・workspace・地域・利用枠等に依存する。プラン名だけでは成功を保証しない。

1. 実装PRのChecksから成功した「Android debug APK」を開く。GitHubへログインした状態でArtifactsの `hagemashi-debug-apk` をダウンロードし、ZIP内の `app-debug.apk` をインストールする。Artifactsは14日保持。署名が異なる既存APKへの上書きは失敗する場合があり、アンインストールはアプリの接続情報も消す。
2. 「ChatGPT 手動接続の検証」を開く。「未接続」と検証版の注意表示を確認し、生成ボタンが無効であることを確認する。
3. `Continue with ChatGPT` を押す。外部ブラウザで本人の認証と同意を行う。ブラウザがloopbackの応答を表示したら、最近使ったアプリ等からhagemashiへ戻る。最大5分待機。プロセスが終了した場合は再度認証する。
4. 接続状態、確認可能な本人情報、ChatGPTプラン利用の説明を確認する。名前・メールがtokenに含まれない場合は空欄を推測で埋めない。「生成権限なし」の場合は成功と混同せず、解除・アカウント／同意内容を確認する。
5. 「モデル一覧を取得・更新」を押し、使用するモデルを選ぶ。「ダミーデータで生成」を押し、「成功：回答の完了を確認しました。」と回答が表示されることを確認する。実測データは送信しない。短文・全文の文字数はプロンプトによる依頼で、この版では厳密な形式検査や再生成を行わない。
6. もう一度生成し、前回の回答を文脈へ引き継がないことを送信内容の設計と合わせて確認する。端末を再起動後、再認証なしで接続状態が復元され、モデルを取得して再生成できるかを確認する。接続表示だけを保持できても生成成功とはしない。
7. 生成中に「接続解除」を押す。旧回答が後から表示されないこと、解除後は生成できないこと、再起動後も未接続であることを確認する。サーバー失効確認済み／未確認の文言を記録する。
8. 再接続時は保存登録を選択してContinue with ChatGPTを押す。別登録は「別のアカウント／workspaceを登録」を選ぶ。異なる登録の資格情報が混ざらないことを確認する。
9. 通信を切ってモデル取得／生成を試し、失敗と表示されることを確認する。接続情報はネットワーク障害だけでは削除しない。オフライン解除はローカル削除とサーバー失効未確認を表示し、ChatGPT設定での解除を別途確認する。
10. 認証中のキャンセル、同意拒否、画面回転、ブラウザ滞在中のプロセス終了も別の結果として記録する。1時間程度をまたぐ更新、ChatGPT設定からの失効、利用上限、端末ロック・バックアップ／端末間転送は、それぞれ実施するまで未検証とする。

認可URL、callbackのアドレスバー、token、パスワードをスクリーンショットへ含めない。報告時は端末／Android／ブラウザ、試験日時、選択モデル、手順番号、成功・失敗・未実行、機密情報を除いたHTTP状態・既知エラーコードを記録する。アカウント情報は必要に応じて伏せる。

## 検証コマンドと扱い

JDK 17・API 35・Build Tools 35.0.0で実行する。

```sh
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest
git diff --check
```

CIは上記の単体テスト・Lint・APKビルドを行い、`hagemashi-check-reports`へ結果を保存する。JUnit 4.13.2とorg.json 20240303はJVM単体テスト用のみで、アプリへの新しい実行時依存はない。Java Base64、JCA、Keystore、ネットワークAPIはAPI 26以上を前提にする。

単体テストはPKCEの公式ベクトル、callbackの拒否条件、RS256署名とclaims検証、SSEの完了／途中切断／失敗、キャンセル後の保存禁止を対象とする。Android用テストはUIの初期状態とKeystore暗号化・改ざん拒否を対象とする。Keystoreテストは合成値と独立したファイル・鍵を使い、本番保存ファイルを変更しない。UIテストは通常のアプリ画面を起動するため、端末に既存の保存状態があればそれを読み込むが、認証・生成は操作しない。

接続デバイスがある場合のみ次を実行する。

```sh
./gradlew :app:connectedDebugAndroidTest
```

成功・失敗の実行結果とCI／APKリンクは実装PRに記録する。Android用テストAPKのコンパイルをテスト実行成功としない。実機のサインイン・生成・保存・解除・更新・上限はユーザーが試すまで未確認。
