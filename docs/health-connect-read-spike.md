# Health Connect読み取り検証（レビュー用PR・未マージ）

## 範囲と既存機能

PR #1〜#4を順にmainへマージした後、そのmain（5403c7be7b260f981b9d01c15d0a763e08f76f9f）からfeat/health-connect-read-spikeを作成した。PR #4の[実機受け入れ結果](chatgpt-device-acceptance.md)と標準のブラウザ→アプリ認証を維持する。確認済み3点の再試験は求めない。このHealth Connect PRはレビュー用に提出し、マージしない。

読み取り対象は体重・体脂肪率・歩数の3種類だけ。健康データをChatGPTへ送る処理、通知、書き込み、CSV、履歴保存、バックグラウンド読み取りは対象外。既存のChatGPT生成テストは固定の架空データを使用し、読み取った値をプロンプトへ接続しない。

## 実装と表示

- 「Health Connect 読み取り検証」画面はChatGPTへのサインインなしで開ける。
- 体重（kg）と体脂肪率（%）：取得時刻から過去7日間の最新20件。各値に測定日時とdataOrigin.packageNameを表示する。古い測定値の日付を隠さず、ほかの記録がある場合は最新20件までの表示と明記する。
- 歩数：端末の今日0時から取得時刻まで、StepsRecord.COUNT_TOTALをaggregateで取得。生の歩数レコードを全提供元で単純加算せず、Health Connect側の優先順位・重複調整に従う。集計期間と返された提供元を表示する。
- 全ての日時は端末タイムゾーンとUTCオフセット付きで表示。期間の終端は取得時点で、再取得すると更新する。
- 種類ごとに「権限なし」「権限あり・この期間の記録なし」「記録あり」「アクセス拒否」「取得失敗」を区別する。記録なしを0や推測値で補完しない。APIが実際に0歩を返した場合だけ0歩を表示する。
- 取得前後に権限を確認し、途中で取り消された種類の値を表示しない。Health Connect設定から戻った場合も再確認する。画面を離れると読み取りをキャンセルし、画面の値を破棄する。
- 読み取った値はメモリ内の画面用状態だけに保持し、ログ・ファイル・履歴・クリップボード・診断・ネットワーク送信へ入れない。例外の生メッセージも表示・記録しない。

## 権限とプライバシー画面

要求する健康権限はREAD_WEIGHT、READ_BODY_FAT、READ_STEPSのみ。WRITE、READ_HEALTH_DATA_HISTORY、READ_HEALTH_DATA_IN_BACKGROUND、通知権限は要求しない。
Android13以前のACTION_SHOW_PERMISSIONS_RATIONALEと、Android14以降のVIEW_PERMISSION_USAGE／HEALTH_PERMISSIONS用aliasを同じMainActivityへ追加し、利用目的・非送信・非保存・解除方法を静的な画面で説明する。新しいActivityクラスは追加しない。戻る認証Intentはそのまま維持し、健康データや認証情報をIntentに入れない。

## SDKと互換性

安定版androidx.health.connect:connect-client:1.1.0を固定。明示的に使用するCoroutine APIはkotlinx-coroutines-android:1.7.3（同SDKの宣言バージョン）を固定する。
Health Connect1.1.0のAAR metadataはminCompileSdk=36／minAndroidGradlePluginVersion=8.9.1。公式手順も同じ要件を明記するため、この別PRではcompileSdkだけ36へ上げる。targetSdk35・minSdk26・AGP8.10.1・Gradle8.11.1・Kotlin2.2.21・JDK17・Build Tools35.0.0は維持。AGP8.10がAPI36まで対応することを公式リリース情報で確認した。CIはAPI35を残しAPI36もインストールする。Android Studioは不要。

Health Connect SDKはAPI26以上に対応するが、実際のHealth Connect利用はAPI28以上と端末側の提供が必要。Android14以降はOS側のHealth Connectを使い、Android13以前は提供アプリが必要。端末・profile・更新状況により利用できない場合があるため、Android15というだけで利用可能とは断定せずgetSdkStatusで判定する。

公式資料：[Health Connect導入](https://developer.android.com/health-and-fitness/health-connect/get-started)、[安定版リリース](https://developer.android.com/jetpack/androidx/releases/health-connect#1.1.0)、[読み取り](https://developer.android.com/health-and-fitness/health-connect/read-data)、[集計](https://developer.android.com/health-and-fitness/health-connect/aggregate-data)、[データ型と権限](https://developer.android.com/health-and-fitness/health-connect/data-types)、[AGP8.10互換性](https://developer.android.com/build/releases/agp-8-10-0-release-notes)。Google Playで公開する際のHealthアプリ申告・プライバシーURL等は別途確認する。

## Androidだけで行うarboleaf到着確認

1. PRのCI成功後、Artifactsの **hagemashi-diagnostic-apk** をダウンロードしてZIPを展開しapp-diagnostic.apkを現在の「hagemashi 診断」へ上書きする。通常版も診断版もアンインストールしない。署名Secretを再生成しない。
2. 「Health Connect 読み取り検証」を開く。ChatGPTの再ログインや生成は不要。利用不可なら表示された状態を確認する。更新が必要な場合のボタンは公式の提供アプリを開くためのもので、Android14以降のOS内蔵機能を古いAPKへ置き換えない。
3. 「体重・体脂肪率・歩数の読み取りを許可」を押す。Health Connectのシステム画面で許可する。拒否した種類は「権限なし」と表示され、記録なしとは区別される。
4. arboleafの連携設定を確認する。「マイページ／サードパーティ連携／Health Connect」等の名前はバージョンで異なる。arboleafがHealth Connectへ体重・体脂肪率を**書き込む**設定と権限を確認する。hagemashi側の読み取り許可だけではarboleafからの転送は有効にならない。
5. arboleafで新しく測定し、arboleafアプリ内に体重と体脂肪率の両方が保存されたこと、測定時刻を確認する。体脂肪率がarboleaf自体にない場合、こちらでも推測しない。
6. Health Connectの「データとアクセス／身体測定」等から体重・体脂肪率の最新記録と提供元を確認する。arboleafの[公式プライバシーポリシー](https://www.arboleaf.com/en-jp/pages/privacy-policy)にはHealth Connectとの連携の記載があるが、全端末・全バージョン・全データ型の転送成功を保証する根拠とはしない。メニューがない場合や書き込まれない場合は、arboleafの対応・設定側を先に確認する。非公開APIやCSVで代替しない。
7. hagemashiへ戻り「権限を確認して再取得」。体重・体脂肪率に、新しい測定の値、測定日時、実際の提供元パッケージ名が表示されるか確認する。表示された提供元をarboleafと推測で置き換えない。間に別アプリがある場合はその提供元が表示されることがある。
8. 歩数は表示された集計期間と提供元を確認する。歩数の提供元がなければ「記録なし」でよく、体重・体脂肪率の到着失敗とは扱わない。
9. 任意の1種類だけ読み取りを取り消し、戻ってその種類の値が消え「権限なし」になること、ほかの許可済み種類が独立して読めることを確認する。これはHealth Connectの新機能試験であり、既存のChatGPT3項目の再試験ではない。

切り分けは「arboleaf内に値があるか→Health Connectに値があるか→hagemashiに読取権限があるか→表示期間内か→アプリに届くか」の順に行う。提供元が書いていない記録をこのアプリから取得することはできない。まず新しい測定1回と再取得で確認し、端末設定を推測で変更しない。

成功報告は「利用可能／各権限／新しい体重・体脂肪率が届いたか／測定日時・提供元を確認できたか」で十分。実際の体重・体脂肪率・アカウント情報の撮影やチャット貼り付けは不要。

## 結果の扱い

自動テストは合成値と偽のデータソースだけで、実機Health Connectやarboleafに接続しない。CI成功・APK生成を実データ到着の成功と扱わない。現時点でHealth Connectの実機読み取り・権限画面・arboleaf新測定の到着は未実行。ユーザーが試すまで未確認とする。テスト件数、CI・APKリンク、署名／Manifest検査は提出PRに記録する。

## 後続の手動生成検証

ユーザーからHealth Connect実データ取得成功の報告を受領した。取得時のAPK識別子等は未指定のため推測しない。上記は読み取りPR #5の範囲であり、後続の依存PRでは[実データの手動生成](health-manual-generation.md)として明示的な送信前確認を追加する。旧読み取り画面は表示のみ、後続の専用画面だけが確認したデータを送信する。
