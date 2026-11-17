# AdMob公開向けIDの設定と成果物検査

## 依存・ビルド構成

未マージのテストバナーPR #19 最新先端 `a46058bf4ec4c5652776899f56b93bb5921db188` から分岐しました。本PRのbaseは `feat/admob-test-banner`。依存順は #17 → #18 → #19 → 本PRです。先行PR・本PRのマージ、Google Playへのアップロード・公開、AdMobの本番配信開始は行いません。

| ビルド | 広告モード | アプリ／バナーユニット |
| --- | --- | --- |
| debug（通常検証APK） | 常にtest | GoogleのデモID |
| diagnostic（実機検証APK） | 常にtest | GoogleのデモID |
| release（既定、内部・クローズドテスト用AAB） | test | GoogleのデモID |
| release `-Phagemashi.releaseAds=off` | off | SDKなし・広告なし |
| release `-Phagemashi.releaseAds=production` | production | 下記の指定ID・UMPによる可否確認 |

`-Phagemashi.releaseAds=test` を明示しても構いません。値の省略はtestで、本番にはなりません。不正な値は構成エラーです。本番プロパティはreleaseだけに作用し、debug／diagnosticへは伝播しません。アプリの設定・バックアップでモードを変更する仕組みはありません。

本番用アプリID：`ca-app-pub-3595062014413653~8954573313`

本番用バナーユニットID：`ca-app-pub-3595062014413653/1814449890`

Googleのデモ用アプリID：`ca-app-pub-3940256099942544~3347511713`

Googleの適応バナーテストユニットID：`ca-app-pub-3940256099942544/9214589741`

これらは公開IDでありパスワード・秘密鍵ではありません。SDKは25.5.0のまま、UMP4.0.0を明示的に固定します。UMPはAPI21以上、GMAはAPI24／compileSdk35以上なので、既存minSdk26／compileSdk36・JDK17等を維持します。SDKを使うソース・Manifestを共通化し、PR #19の表示と破棄処理を再利用します。release offでは空スロットだけをコンパイルします。

## 広告の条件とOpenAIからの分離

2026-11-17にGoogle公式の[UMP導入](https://developers.google.com/admob/android/privacy)、[EEA向け開示](https://developers.google.com/admob/android/privacy/gdpr)、[認定CMP要件](https://support.google.com/admob/answer/13554116)、[米国州法向けGPP/RDP](https://developers.google.com/admob/android/privacy/us-states)、[SDK導入](https://developers.google.com/admob/android/quick-start)を確認しました。

- EEA・英国・スイスでは、対象となる端末保存・個人データの扱いについて開示と必要な同意が求められます。パーソナライズ配信にはGoogle認定CMP／TCF要件があります。UMPは広告用の選択を扱い、OpenAIへの送信同意を読みません。
- 米国の対象州についても配信先に応じたメッセージ・GPP／RDP設定の確認が必要です。本実装はUMPが保存するGoogle向け信号を使い、独自の地域推定や架空の同意文字列は作りません。
- 本番モードでは起動ごと（Activity再作成時も含む）にrequestConsentInfoUpdateし、必要なフォームをloadAndShowConsentFormIfRequiredで表示します。更新・フォームが成功し、canRequestAdsがtrueの場合だけSDKの初期化／バナー読み込みへ進みます。更新失敗・フォーム失敗・状態不明では広告を読み込みません。古い同意をアプリ独自の保存値で代用しません。
- 必要な場合は設定に「広告のプライバシー設定」を表示し、UMPのprivacy optionsフォームを開きます。変更中は新規読み込みを止め、完了後のcanRequestAdsを確認します。確認失敗時は設定から再確認できます。独自の「広告に同意済み」フラグをバックアップに保存しません。
- テストモードは公開アカウントのUMPメッセージ設定を要求せず、GoogleデモIDだけで検証できます。このテスト経路を本番IDへ流用しません。
- PPTによるパーソナライズ無効設定は維持しますが、これだけで地域ごとの同意条件が満たされるとは扱いません。AdMob管理画面での地域・メッセージ設定と配信前レビューは未実施です。

体重・体脂肪率・歩数・目標・人格・補足・本文・認証情報をSDK／広告リクエストへ渡す引数や保存先への参照はありません。広告リクエストは空のBuilder、固定ユニットIDとサイズを使います。UMPに渡すのはActivityと空のConsentRequestParametersだけです。OpenAIへの同意・Health Connect権限・ChatGPT認証とは別の状態です。広告SDKの技術情報収集は[公式データ開示](https://developers.google.com/admob/android/privacy/play-data-disclosure)を参照してください。通信ゼロとは報告しません。

通知と履歴は同じ回答詳細を使います。本文は広告処理と待ち合わせず、広告なし・未許可・オフライン・失敗時は高さ0で空枠を残しません。UMPで案内が必要な場合の公式フォームは広告とは別に表示されます。本文のスクロール・戻る・過去データ展開を維持し、画面幅に合わせたバナーは下部に1枠だけです。

## 作成手順（アップロードしない）

通常の開発・実機試験：

```sh
./gradlew --no-daemon :app:assembleDebug :app:assembleDiagnostic
```

内部・クローズドテスト向けに、テスト広告のままrelease AABを作る：

```sh
./gradlew --no-daemon :app:bundleRelease -Phagemashi.releaseAds=test
```

広告を含めないrelease：

```sh
./gradlew --no-daemon :app:assembleRelease :app:bundleRelease -Phagemashi.releaseAds=off
```

**公開向けに本番IDを明示的に組み込む（表示・クリックの検証はしない）**：

```sh
./gradlew --no-daemon :app:assembleRelease :app:bundleRelease -Phagemashi.releaseAds=production
```

成果物は `app/build/outputs/apk/release/app-release-unsigned.apk` と `app/build/outputs/bundle/release/app-release.aab`。現在release署名は未設定で、CIの成果物も未署名の静的検査専用です。Play提出前には所有者のアップロード鍵で署名し、versionCode・対象API等も提出時点の要件へ整える必要があります。既存の診断APK署名鍵を無断で公開用鍵に転用しません。鍵・パスワード・署名ファイルはGitやArtifactsへ含めないでください。

例えば、所有者が安全な環境で用意したアップロード鍵でAABを署名する場合は、パスワードを引数へ書かず対話入力します（この作業では実行しません）：

```sh
jarsigner -keystore /private/upload-key.p12 -signedjar /private/hagemashi-signed.aab app/build/outputs/bundle/release/app-release.aab YOUR_UPLOAD_ALIAS
```

## 成果物の広告設定を確認する

すべてのAPKの `assets/ad-config.json`、AABの `base/assets/ad-config.json` に、mode・applicationId・bannerUnitId・privacyGateを格納します。実際のSDKアプリIDはManifest、ユニットIDはBuildConfigから設定され、同じビルド変数で記述子を生成します。

Androidのファイル管理／ZIP対応アプリだけでもAPK／AABをZIPとして開き、このJSONでテスト／本番／広告なしを確認できます。本番ID入りの成果物を実機へインストールしたり起動したりする必要はありません。

CIは記述子だけでなく、APKのaapt出力とDEX、AABのbase ManifestとDEXに実際のIDが含まれることも検査します：

```sh
python3 .github/scripts/ad-artifact.py --mode production --apk app/build/outputs/apk/release/app-release-unsigned.apk --aab app/build/outputs/bundle/release/app-release.aab --report ad-config-verification.json
```

test／offでは `--mode` を対応する値に変更します。開発用成果物に本番IDが見つかる、不正なID・モード、必要なSDK／UMPがない、除外した広告ID／AdServices権限が混入する場合はCIを失敗させます。本番フラグを付けてもdebug／diagnosticの実APKがtestのままであることも別途確認します。検査は実広告の表示・クリック・SDK起動を一切しません。

Actionsの `release-off-static-inspection`、`release-test-static-inspection`、`release-production-static-inspection` に未署名APK／AAB／検査JSONを保存します。これらは静的確認専用です。通常の `hagemashi-debug-apk` と `hagemashi-diagnostic-apk` だけが署名付きの実機試験用で、いずれもテスト広告です。

## Android実機試験

1. PRの署名付き通常版または診断版テストAPKを既存版へ上書き更新。ZIPで広告記述子がtest・GoogleデモIDであり、本番IDではないことを確認。
2. 履歴から保存済み回答を開き、本文が先に読め、テストバナーが下部に1枠表示されることを確認。詳細を繰り返し開き、回転・分割画面でも重複や重なりがないことを確認。
3. 診断版の「通知を試す」で既存回答を通知し、通知をタップして元の回答本文・タイトルとテストバナーが開くことを確認。追加AI送信はありません。通常版では既存の朝夜通知からも同じ詳細へ進みます。
4. 一時的な機内モードで履歴／既存通知の詳細を開く。本文・スクロール・戻るは使え、広告の空枠は残らないことを確認。通信を戻して詳細を開き直す。
5. OpenAIの同意を取り消した状態でも保存済み回答とテスト広告を開け、広告表示でOpenAIへの同意が有効にならないことを確認。

本番構成は静的検査だけです。実広告の表示・クリックは試験手段にしません。UMPの実サーバー・地域別フォーム・選択変更・オフライン失敗の実機確認は未実施で、配信前に別のテスト設計とAdMobメッセージの準備が必要です。CIのビルド／テストAPKコンパイルを実機成功とは扱いません。

## 残作業（このPRでは行わない）

- 配布する国・地域と対象年齢に応じたAdMob Privacy & messagingの設定、必要なUMPフォーム・プライバシー選択導線の検証、ポリシー／データセーフティ開示の見直し。
- 所有者の公開用署名・Play提出設定、Google Playの公開操作。
- 公開後のAdMobへのストア紐付け、ストア掲載の開発者ウェブサイトとapp-ads.txtの到達・内容確認（[公式手順](https://support.google.com/admob/answer/9363762)、[検出の仕組み](https://support.google.com/admob/answer/9776740)）。ドメインや管理権限はこの作業で推測しません。
- AdMobのアプリ確認／審査・配信制限・アプリの準備状況の確認。ID組み込み成功は審査完了・配信開始を意味しません。
