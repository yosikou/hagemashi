# Google Playクローズドテスト用の署名済みAAB

## 基点・版・公開操作

未マージのPR #20先端 `5b9af066ecfd094d44cf5df95ffcdbee19cd4c69` を基点にしています。依存順は #17（バックアップ）→ #18（送信同意）→ #19（広告）→ #20（公開向け広告設定）→ 本PR。先行PR・本PRとも自動でマージしません。

利用者から2026-11-17に「Play Consoleは未提出」「アップロード鍵は未作成」と確認しました。既存APKはversionCode 1／versionName 1.0、GitHub Releasesもありません。本PRは **versionCode 2／versionName 1.0.1** とし、パッケージは **com.yosikou.hagemashi**（診断サフィックスなし）です。Consoleへのアクセス権はなく、未提出の確認根拠は利用者の回答です。これ以降に別のファイルをアップロードした場合は、次回はConsoleの最大値より大きいversionCodeを指定してください。

Google Playへのアップロード・テスター配信・公開は利用者が行います。CIはPlayへ接続せず、配信開始も行いません。

## 現行の対象APIとリリース設定

[Google Play公式要件](https://support.google.com/googleplay/android-developer/answer/11926878)を2026-11-17に確認しました。一般Androidの新規アプリ／更新は2026-08-31以降API36以上が必要で、示された延長期限2026-11-01も経過しています。従来target35では現行の新規提出要件を満たさないため、**targetSdk36**へ更新します。compileSdk36／minSdk26、JDK17・Gradle8.11.1・AGP8.10.1・Kotlin2.2.21等は維持します。

releaseは **minifyEnabled=false／shrinkResources=false** を明示し、R8やリソース削除を新規に有効にしません。認証のJSON・署名検証、Health Connect、JobService／Receiver、通知、履歴・同意の保存処理に削除／難読化を加えません。バンドル内の主要クラスの存在も検査します。アプリ内で選択する6言語が未ダウンロードで欠けないよう、AABのlanguage splitを無効化し、全言語を同梱します。BundleConfigでも無効設定を検査します。ただしコード存在やユニット試験は実機動作の証明ではありません。

[Android16の変更](https://developer.android.com/about/versions/16/behavior-changes-16)ではedge-to-edge、戻る操作、大画面の回転・サイズ変更などが重要です。既存UIはsafeDrawingPaddingとCompose BackHandlerを使い、onBackPressedや固定画面向きに依存していません。固定間隔scheduleAtFixedRateも使わず、予約は既存のAlarmManager／JobSchedulerです。target36での実機レイアウト、ブラウザ復帰、バックグラウンド実行は再確認が必要です。予約時刻は目安で、OSの制限がなくなるとは扱いません。

## アップロード鍵：診断鍵と区別する

既存の `HAGEMASHI_SIGNING_BUNDLE` は診断／開発APK専用（alias `hagemashi-diagnostic`）で、そのまま残します。クローズドテストAABには別の **HAGEMASHI_UPLOAD_SIGNING_BUNDLE**（alias `hagemashi-upload`）を使います。新しい鍵を毎回CIで生成したり、診断鍵を無断で流用したりしません。

GitHub接続にはSecretを書き込む機能がないため、最初の登録だけ所有者のTermuxで行います。JDK21が既にある端末ではそのkeytoolを使用できます（CIはJDK17）。登録スクリプトは既存Secretを上書きせず、初回にRSA3072・10000日有効のPKCS12を生成して、以後は同じローカル復旧ファイルを利用します。

```sh
pkg install python gh openjdk-21 curl
```

gh未認証の場合だけ `gh auth login` で所有者アカウントに接続します。ブラウザ／デバイス認証を使い、認証トークンをチャットやGitへ貼らないでください。

```sh
umask 077
curl -fL https://raw.githubusercontent.com/yosikou/hagemashi/feat/play-closed-test-aab/scripts/register-upload-secret.py -o "$HOME/register-hagemashi-upload.py"
python "$HOME/register-hagemashi-upload.py"
```

必要なSecretは上記1個です。JSONの中には形式版、用途play-upload、alias、PKCS12、パスワード、公開証明書SHA-256が含まれ、スクリプトが標準入力経由でgh secret setへ渡します。秘密値を表示するコマンドは不要です。

復旧用ファイルはTermuxの私有領域 `~/.local/share/hagemashi-upload-signing/upload-signing-bundle.json` に権限600で保存します。鍵とパスワードが入るため、Git・Downloads・チャット・公開Artifactsへ置かないでください。将来の更新用に、所有者が管理する暗号化された別の安全な保管先にも保管してください。アプリの機種変更バックアップにこの開発用鍵は含まれません。Scriptのファイルだけは公開コードで、秘密情報を内蔵しません。

登録後は「Closed test signed AAB」の該当実行をRe-run all jobs、またはRun workflowでこのブランチを選びます。version_code=2、version_name=1.0.1が今回の値です。鍵の登録前のCIが成功しても署名済みAABがあるとは限りません。鍵がない場合は明示的な案内と準備レポートだけを残し、署名済みArtifactを作りません。

## CIと確認

既存のAndroid debug APKワークフローとAPKの署名・配布は維持します。新しい `.github/workflows/closed-test-aab.yml` はpush／手動実行で次を行います。fork PRへアップロード鍵を渡すpull_requestイベントは設けません。

1. test広告を明示してreleaseの単体テスト・Lint・APK／AABを生成。
2. 公式bundletool1.18.3を[公式リリース](https://github.com/google/bundletool/releases/tag/1.18.3)から取得し、固定SHA-256で検証。構造validate、base Manifest、package・版・min／target、非debuggable、テストID、主要クラス、生成元コミットを確認します。
3. [16KBの公式案内](https://developer.android.com/guide/practices/page-sizes)に沿って、64bitネイティブライブラリがあればELFとバンドルの16KB配置設定を確認します。16KB端末での起動試験は未実施です。
4. SecretをRUNNER_TEMPの権限600のファイルへ復元し、証明書のSHA-256とalias・用途を検査。診断用bundle形式は拒否します。
5. Android公式[コマンドライン署名方式](https://developer.android.com/tools/bundletool)に従いjarsignerでAABを署名します。apksignerはAAB署名に使いません。
6. AAB全ペイロードを実際に読み、欠けた署名・改変・別証明書・未署名の追加ファイルを拒否。署名後もbundletoolと各メタデータを再検査します。
7. 成功したAAB、検査JSON、**公開**アップロード証明書だけをArtifactへ保存。秘密鍵／パスワード／keystoreは保存せず、実行終了時に私有ファイルを削除します。

**取得するArtifact：hagemashi-closed-test-signed-aab**

展開後にアップロードするファイル： **hagemashi-closed-test-testads.aab**

`aab-verification.json` にコミットSHA・applicationId・versionCode・versionName・targetSDK・ファイルSHA-256・アップロード証明書SHA-256・テストID・署名検査結果を記録します。readyForUploadがtrue、uploadSignatureVerifiedがtrueであることを確認してください。これはストア審査に必ず合格するという意味ではなく、ここで実装したビルド／署名検査に成功したという意味です。

`closed-test-aab-readiness` は署名前の準備レポートで、アップロードするAABではありません。既存の `release-*-static-inspection` は未署名検査用で、Playへアップロードしません。APKは端末へインストールするためのもので、今回の提出ファイルはAABです。

## 広告設定

今回のAABは **test** を強制し、Google公式アプリID `ca-app-pub-3940256099942544~3347511713` とバナーID `ca-app-pub-3940256099942544/9214589741` を使います。公開パッケージ名でも本番広告にはなりません。本番用IDの変更・実広告の表示／クリックは検証に使いません。APK／AABのassets/ad-config.json（AABはbase/assets）でも確認できます。OpenAI同意と広告条件の分離、健康値を広告SDKへ渡さない処理は先行PRのままです。

## Play App Signingと署名依存の確認

[Android公式署名説明](https://developer.android.com/studio/publish/app-signing)のとおり、アップロード鍵はAAB提出者を確認する鍵で、Playが配布APKに使うアプリ署名鍵とは別です。新規アプリではGoogle管理のアプリ署名鍵を選ぶ構成を想定します。Consoleの「アプリの完全性／App integrity」で以下を区別してください。

- アップロード証明書：本Artifactのupload-certificate.pem／検査JSONのSHA-256と一致。
- アプリ署名証明書：Play App SigningでGoogleが管理し、端末に配信するAPKの署名。公開前にConsoleでSHA-256を確認。

コード調査ではChatGPT連携はブラウザ・PKCE・動的クライアント登録・loopback callbackを使い、Android用OAuthクライアントやAPK署名fingerprintの登録、google-services.jsonはありません。ID tokenのRSA署名検証はOpenAIの鍵を検証するもので、APK証明書ではありません。公開パッケージ名は変えません。Play署名変更で登録を差し替えるローカル設定は見つかりませんでしたが、サービス側の適用条件とPlayから入れた実機での認証は未確認です。

通知・予約のIntent URI／actionはpackageNameから作り、公開用com.yosikou.hagemashiで生成します。Health Connectも公開パッケージと権限宣言を使い、SDK内へのローカルfingerprint登録はありません。Play ConsoleのHealth Connect／健康アプリの権限申告・データセーフティ等は別途必要です。

## 既存APKからの移行

既存の通常APKはpackageが同じでも診断／開発用証明書で署名され、Googleが新しく管理するPlayのアプリ署名鍵とは一致すると仮定できません。アップロード証明書が一致しても、端末の更新可否は配布APKの**アプリ署名証明書**で決まります。署名不一致のまま上書きしようとしても更新できません。無断でアプリを削除する手順にはしません。

1. 既存アプリで履歴付きの暗号化バックアップを作り、Drive等に保存。パスフレーズを保管し、ファイルを読み戻して復号プレビュー・履歴件数まで確認し、キャンセルする。生成・通知の設定時刻も確認。
2. 診断版com.yosikou.hagemashi.diagnosticで移行を試す場合は、公開版と併存可能。診断版は残したままPlay版を入れて、同じバックアップを復元できる。
3. 同一パッケージの通常APKがあり、Consoleのアプリ署名証明書と既存APKの証明書が違う場合は、そのバックアップを確保・確認した後にだけ、利用者が削除・Play版への切替を判断する。削除前に復元可能性を確認し、不要なアンインストールはしない。
4. Play版で復元。ChatGPTの認証・端末鍵・OS権限・送信同意・自動生成有効状態は移さない。再ログインし、Health Connect／通知権限、OpenAI送信同意、モデル・時刻を確認。予約再開確認後、自動生成と使用枠を明示的にオンにする。

Playのアプリ署名証明書はまだ取得しておらず、最終的な同一／不一致は未確認です。同じ署名であるという理由で既存鍵をGoogleへ提供する設定には変更しません。

## Android端末だけでConsoleへアップロード（利用者の操作）

1. AndroidブラウザでGitHubへログインし、PRに記載する成功したActions実行→hagemashi-closed-test-signed-aabを取得。ZIPを展開し、検査JSONのコミット・package・版・署名・test広告を確認する。
2. Chrome等で[Play Console](https://play.google.com/console/)を開く。必要ならブラウザの「PC版サイト」を選ぶ。対象アプリを作成／選択し、正式packageがcom.yosikou.hagemashiであることを確認。
3. Play App Signingを設定する。新規のGoogle管理アプリ署名鍵と、AABのアップロード証明書を区別する。既存の別アプリ署名設定を上書きしない。
4. 「テストとリリース」→「テスト」→「クローズドテスト」でトラックとリリースを作る。ファイル選択から**展開したhagemashi-closed-test-testads.aab**を選ぶ。ZIP・APK・検査JSON・公開証明書をAAB欄へアップロードしない。
5. Consoleに表示されたpackage、versionCode 2／1.0.1、target36、署名の受け入れを確認。別の版を既にアップロードした場合は、最大値を確認してCIを新しいversionCodeで再実行する。
6. プライバシーポリシー、健康データ・外部送信・広告のデータセーフティ、Health Connect権限、対象年齢、広告有無、ストア情報・テスター・地域等の申告を利用者が確認する。テスト広告でもSDKを含むことを踏まえて申告する。
7. リリースの確認・テスターへの配信開始は利用者が判断して実行する。作業者はアップロード／配信を代行しない。

[Google公式のテストトラック手順](https://support.google.com/googleplay/android-developer/answer/9845334)も参照してください。新規個人アカウントの製品版公開の試験要件等は別です。クローズドテストへのAAB作成だけで製品版公開資格が得られるとは扱いません。

## 実機確認・未確認

target36とPlayからの配布では、次を確認します。署名済みAABの生成・構造検証をこれらの成功とは報告しません。

- ブラウザでChatGPT認証→アプリに戻って接続、再起動後の手動回答、接続解除。
- Health Connectの体重・体脂肪率・歩数と未許可／記録なし、arboleaf到着。
- 朝夜の予約：ホームへ離れる場合／スワイプで閉じる場合、回答保存→通知→当時の詳細、翌日への持ち越しなし。
- 履歴・古い通知・詳細、広告の失敗中も本文が読めること、測定値を広告へ渡さないこと。
- 設定初回の説明、未同意・取り消し・復元直後の送信停止、再開前の自動生成停止。
- edge-to-edge、戻る、縦横／分割、6言語、バックアップ・復元後の設定と履歴。

この作業では実機、Playアップロード／配布、Play署名の実証明書、地域別広告フォーム、サービス側の署名依存条件は未確認です。CIのテストAPKはコンパイルのみです。
