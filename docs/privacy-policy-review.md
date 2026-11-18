# プライバシーポリシー：実装照合と公開手順

確認日：2026-11-18。公開名 **hagemashi - AI体重管理**／package **com.yosikou.hagemashi**。公開用問い合わせ先 **yosikou.createapp@gmail.com** は利用者が今回指定したものです。Git作者情報や個人用メールから推測していません。

## 作業基点と公開状態

最新の未マージPR #21 `feat/play-closed-test-aab`／`53bb5f00f103fedaefed22e4936e4fc355453fa7` を基点に `feat/privacy-policy` を作成。ローカルの同一tree `8ae22d5b65bb3314181cdeacc15b9c5cac3fb989` と照合しました。依存順は #17（バックアップ）→ #18（送信同意）→ #19（テスト広告）→ #20（広告ビルド設定）→ #21（AAB）→ 本PR。PRのbaseは #21 のブランチで、今回固有の差分をレビューします。先行PRを含めマージしません。

既存のPages用workflow、CNAME、Jekyll設定はありません。GitHubリポジトリAPIで `has_pages=false`／public／default_branch=main を確認しました。管理画面の公開ソース設定は閲覧・変更していません。予定URL `https://yosikou.github.io/hagemashi/privacy.html` はWeb取得ツールではアクセスできず、**公開ページを閲覧できたとは報告しません**。この取得エラーをHTTP404等と断定しません。プレビューArtifactやローカルHTMLの成功も、本番URLの公開成功とは区別します。

## 本文と実装の対応

|対象|確認した実装|ポリシーへの反映|
|---|---|---|
|健康権限|main/AndroidManifest.xml、HealthConnectViewModel.kt、BackgroundHealthPermissionControl.kt|READ_WEIGHT／READ_BODY_FAT／READ_STEPS／READ_HEALTH_DATA_IN_BACKGROUNDのみ。書き込み・健康記録削除なし。背景権限は別操作|
|期間・データ|HealthRead.kt、HealthConnectSource（HealthConnectViewModel.kt内）、HealthGeneration.kt、GenerationPreparation.kt|生成は体重／体脂肪率30日、歩数7現地日。朝は当日開始を上限として前日まで、夜・手動は取得時点まで。日時・期間・提供元package・取得状態を含む。確認画面は測定7日／歩数当日。欠損のゼロ補完なし|
|生成リクエスト|ConnectionRepository.generate、Protocol.generationRequest、GenerationPreparation.kt|目標体重・任意の日付・維持指示、人格prompt・共通補足、言語・単位・朝夜手動ルール、モデル。人格名は保存表示用で、その名前を別フィールドとして送らない。過去回答・ChatGPT会話／メモリを送受信しない|
|手動・予約|ManualGenerationViewModel.kt、ScheduledGeneration.kt、DataConsent.kt、DataSending.kt|手動操作、同意と明示オン後の朝夜背景送信。書込み直前に最新の同意・自動オン・枠versionを再確認。失敗は当日の枠内再試行。時刻変更による同日再生成も記載|
|認証|ConnectionRepository、CredentialVault.kt|外部ブラウザPKCE。アカウントID・名前・メール・登録ID・host ID・tokenをローカル保護。接続解除は失効を試行しtokenを消すが、accounts/client mapping・host IDは維持。失効が確認できない場合を区別。ブラウザCookieは消さない|
|端末保存|CredentialVault.kt、SettingsStore.kt、AnswerStore.kt、ReservationSupport.kt|AES256-GCM＋Android Keystore：設定、認証、回答・使用データ、予約台帳。noBackupFilesDir。すべての保存ファイルが暗号化されるとは書かない|
|平文メタデータ|HistoryIndex.kt、LanguageContext.kt、ReservationDiagnostics.kt、ReservationTrace.kt|アプリ専用領域に履歴ID・生成日時・削除ID、起動用言語コピー、最大128件の処理診断。診断は健康値・prompt・回答・token・生応答なし、自動アップロードなし|
|Androidバックアップ|allowBackup=false、backup_rules.xml、data_extraction_rules.xml|アプリデータをOS自動バックアップ／移行から除外。手動バックアップとは区別|
|バックアップ|BackupCodec.kt、BackupRepository.kt、BackupViewModel.kt|パスフレーズPBKDF2-HMAC-SHA256（600000回、256bit）＋AES-GCM、salt16byte・nonce12byte。設定＋任意履歴、SAFでDrive等へ。認証・鍵・権限・実行待ち予約・同意・表示済み・自動オンを除外。復元後再同意・再開|
|人格共有|PersonaFile.kt、PersonaShareViewModel.kt|名前とpromptだけの平文JSON。prompt内に本人が書いた情報も含まれる。バックアップとは別|
|削除|HistoryViewModel.remove、AnswerStore.delete/deleteAll、HistoryIndex.remove、ConnectionRepository.disconnect|履歴削除は回答ファイルと一覧を対象、削除済みIDは残る。設定・接続・Health Connect元記録は消さない。全設定消去はアプリ内に未実装。Androidストレージ消去／アンインストール、外部ファイル・サービスは別管理。安全な物理消去は保証しない|
|同意取り消し|AppViewModel.recordConsent、SettingsStore、DataSending|新規送信を停止、既に送ったデータの回収ではない。自動だけ停止と区別。重要変更はDATA_CONSENT_VERSIONを上げ再同意|
|通知|Encouragement.notificationShort、AnswerNotifications.kt|測定値／目標値を含む短文は固定文へ。通知・履歴は同じ保存済み回答を開く。手動は通知なし。ロック画面はOS設定|
|広告への入力|ads/DetailTestBanner.kt、AdvertisingPrivacy.kt|空のAdRequest.Builder().build()。健康・設定・prompt・本文・tokenを渡す引数やキーワードなし。PERSONALIZATION DISABLEDは固定値。OpenAI同意を読み取らず、UMPは独立|
|広告の構成|app/build.gradle.kts、ads/AndroidManifest.xml|GMA25.5.0／UMP4.0.0。debug／diagnosticはtest、release既定test、明示productionだけ本番。offはSDKなし。AD_IDとAdServices ID／attribution／topics権限をremove。ただし他の情報収集まで無いとはしない|

これはコードの確認結果です。外部SDK内部のすべての挙動や、サービス側での保存・削除を動的に観測した結果ではありません。履歴や設定に自動TTLはありません。古い暗号化バックアップを復元すれば、削除済みの回答を再び取り込む場合があります。削除はフラッシュ媒体の安全な消去や、復元前の残存ファイルの完全消去を保証しません。

## 公式資料（2026-11-18確認）

- [Google Mobile Ads Android data disclosure](https://developers.google.com/admob/android/privacy/play-data-disclosure)：掲載対象が採用中の25.5.0（Legacy SDK）と一致。SDKのIP（おおまかな位置の推定に利用可能）、操作、診断、端末／アカウント識別子等の自動収集と用途、TLS。アプリが健康値を渡さないこととSDK自動収集を区別。
- [Google広告プライバシー／UMP](https://developers.google.com/admob/android/privacy)、[Google Privacy Policy](https://policies.google.com/privacy)：地域等に依存する条件とGoogle側の管理。テストIDでも無通信にはならない。
- [SIWC OSS overview](https://developers.openai.com/siwc/token-sharing-open-source)、[Sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)、[Accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)：実装が採用するChatGPT plan用OAuth、local token、アカウント情報、失効とローカルsign-outの違い。
- [Models and inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)、[Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)：Responses HTTPでstore=false／stream=true、完了イベントまで読む契約。**store=falseはすべての保持・学習なしの根拠にしない**。
- [SIWC Terms](https://openai.com/policies/sign-in-with-chatgpt-terms/)（2026-11-04発効と表示）：利用者のlocal runtimeと明示承認したautomation/backgroundの条件・事前同意。背景実行が無条件に許可されるとはしない。
- [ChatGPT plan in other apps and sites](https://help.openai.com/en/articles/20001542-using-your-chatgpt-plan-in-other-apps-and-sites)：会話／メモリに自動アクセスしないこと、接続解除とデータ削除が別であること。商用参加アプリとOSSの条件を同一視しない。
- [OpenAI Privacy Policy](https://openai.com/policies/privacy-policy/)：一般の保持・利用・権利とサービス別適用の違いを確認。**このSIWC経路の具体的な保持日数、学習への使用可否、ChatGPT側の学習設定の正確な適用範囲は、今回確認したSIWC資料では特定できない**。通常APIキー向けのno-training既定や30日、Zero Data Retentionをこのアプリに転用しない。政策本文も保証せず公式案内／問い合わせを参照させる。
- [GitHub Pages publishing source](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site)、[GitHub Privacy Statement](https://docs.github.com/en/site-policy/privacy-policies/github-privacy-statement)：main/docsの静的公開とホスティング時の通信情報。ページに独自の解析・広告・Cookie・フォームは追加しない。

## ページとアプリ内導線

`docs/privacy.html` に6言語すべての本文を静的HTMLとして同梱。外部JS／CSS／フォント／翻訳サービス・ログイン不要です。ローカルの言語切替だけをCSP hash付きinline scriptで実行し、通信・Cookie・localStorageは使いません。JS無効でも全6言語の本文とアンカーリンクを読めます。日本語・英語・フランス語・ドイツ語・ヒンディー語・簡体字中国語は同じ10セクションを持ちます。

`PrivacyPolicyButton` が設定、OpenAI送信同意、Health Connectの説明画面から同じURLをACTION_VIEWで開きます。アプリの現在言語のみをfragment（#ja等）に付け、ユーザー識別子や健康データ、クエリは付けません。fragmentはHTTPリクエスト先には送られません。既存のOpenAI公式ポリシーリンクは別の補助リンクとして残します。Health Connectの権限説明Intentは引き続きアプリ内説明を開き、そこから本ポリシーへ進めます。同意の成立条件やDATA_CONSENT_VERSIONは変更しません（送信先・目的・送る項目も変更なし）。

## GitHub PagesをAndroidブラウザだけで公開する（利用者の確認後）

このPRは公開を実行しません。Pagesを作業ブランチに向ける操作もしません。

1. PR本文とメール掲載、全6言語、実装照合と未確認事項をレビュー。先行PRを依存順に統合し、本PRをmainへ取り込む判断をする。
2. AndroidのChromeでGitHubにログインし、yosikou/hagemashi → Settings → Pages。必要ならブラウザメニューの「PC版サイト」を使用。管理者／maintainer権限が必要。
3. Build and deployment → Sourceを **Deploy from a branch** にし、Branch **main**、folder **/docs** → Save。docs/.nojekyllでJekyll処理を不要にする。既に別の公開方式が設定されていたら変更前に用途を確認し、その方式に合わせる。
4. GitHubのPages deploymentが成功するまで待ち、表示されたURLから `https://yosikou.github.io/hagemashi/privacy.html` を開く。独自ドメインは追加しない。
5. ログアウト状態／シークレットウィンドウでも表示を確認。6言語、問い合わせメール、改定日、スマートフォン表示を確認する。その後アプリからの3導線を確認し、Play ConsoleのポリシーURLに同じURLを設定する判断をする。
6. 公開成功とURL到達を確認するまで、Play Console提出用の公開URLが有効になったとは扱わない。

公開後のページ取得はGitHubへのアクセスになります。開発者の独自サーバーにアプリデータを保存しない説明と矛盾しません。

## 検証とAndroid手順

必須：`./gradlew --no-daemon :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`／`git diff --check`。既存MainActivityTestを確認し、同意画面やHealth Connect説明の画面遷移を維持。Android実機／adb接続はなくinstrumentationはコンパイルまでです。

追加チェック：

- `python3 scripts/check-localizations.py`：新しいブラウザ失敗案内を含む6言語のキー・書式の整合。
- `python3 scripts/check-privacy-page.py`：10セクション／26段落の各言語の構造、権限・期間に関わる主要語と連絡先、リンクの一致、重複IDなし、CSP hash、外部依存なし、JSなしで本文が隠れないこと。
- `scripts/check-privacy-browser.py`：CIのみPlaywright1.56.0＋Chromium／Noto fontsで320／360／412pxの6言語、横はみ出し、言語アンカー、JSエラー、本文取得の外部通信なしを確認。JS無効時の6本文、未対応言語→英語、fr-FR→仏語も確認。スクリーンショットは日本語・独語・ヒンディー語を保存。
- ローカルChromiumはサンドボックスのsocket制限で起動不可。ローカルGradleは初回の既定キャッシュ書込み不可後、workspace配下キャッシュで再試行しても配布ZIP取得時のsocket制限で失敗。最新CIでの結果とArtifactリンクはPRに追記する。ブラウザの幅を模した試験はAndroid実機での操作成功とは別。

Androidのみでの確認：

1. 公開前はPRの `privacy-page-preview` Artifactを展開しHTMLと画面画像を確認可能。これはPages公開ではない。GitHubのraw表示を公開HTMLページと取り違えない。
2. Pages公開後、APKの設定 → プライバシーポリシー、OpenAIへのデータ送信 → 同リンク、Health Connect → データの利用とプライバシー → 同リンクの3か所から、同じホスト／パスをブラウザで開けることを確認。戻っても設定や同意が変わらないこと、未同意でも読めることを確認。
3. アプリの言語を6言語それぞれに設定してリンクの初期言語を確認し、Web側でも6言語を切替。ドイツ語の長文、ヒンディー文字、文字拡大、縦横表示、メールリンクを確認。問い合わせメールは試験のために送信する必要はない。
4. 測定値画面を見るだけでは送信しないこと、同意と朝夜オンの説明、履歴削除・接続解除で残る情報、バックアップと人格JSONの違いを、本人の端末上で照合。診断コピーに健康値・回答・tokenがないことを確認し、これらをPRへ貼らない。
5. ネット接続なしで保存済みHTMLを読めることを確認。ただしアプリの外部URLは初回オンライン取得が必要で、アプリにWebページをオフライン同梱する変更ではない。

## 公開前の残確認

- メールアドレスの公開指定は確認済み。受信可能性、運用・保持方針の所有者による最終確認は必要。
- SIWCの具体的な保持期間と学習設定の適用範囲、広告付きで公開する場合のSIWC参加・条件、対象地域／年齢／ストア申告の確定。ポリシーを置くだけで条件・法令・審査を満たすとは判断しない。
- Google側の実際の収集・保存をパケット解析で網羅したものではない。文面は25.5.0公式説明とアプリ設定を根拠にする。
- 6言語のネイティブ／法務レビュー、Android実機での表示とリンク、公開後の本番URL到達は未確認。履歴削除・失効・クラウドファイル削除を新たに実行した検証でもない。
