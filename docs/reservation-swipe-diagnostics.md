# 最近使ったアプリ一覧からのスワイプ終了と予約通知

## 確認できた事実

ユーザー実機で、ホームボタンで離れた場合は到着、Recentsからスワイプした場合は未到着。設定からの強制停止はしていないとの報告を受けた。今回の基点はPR #9の最新先端 `0a1d5e66166c0b13fc8f829e1e63285e9ce1898d`。報告時のインストール済みビルドとの一致は未確認。

- 既存実装は `AlarmManager.setAndAllowWhileIdle(RTC_WAKEUP, ..., PendingIntent.getBroadcast(...))`。Listener・画面内タイマーを予約に使っていない。
- Manifestに明示Receiverを宣言。`goAsync()` と専用executorで端末内の設定・保存回答・通知だけを扱う。ChatGPTやHealth Connectクライアントは呼ばない。
- ActivityのonStop/onPause、ViewModel破棄、詳細画面の破棄に予約取消はない。Service/onTaskRemovedやprocess終了を予約の存続条件にもしていない。
- 取消は設定世代変更、オフ／通知不可／対象回答が読めない場合、予約再計算、旧1枠の移行。これらの既存条件を今回も維持する。
- onResume／初回refreshは既存台帳に基づいて再登録する。このため、開き直した後の到着は、閉じていた間に配信できた証明にはならない。
- 既存「予約登録済み」はアプリ側の台帳を見ていた。OS内の予約存続を読み取った結果ではない。表示を「予約APIへの登録要求完了・台帳保存、予約存続／通知到着は未確認」に修正した。

コードからスワイプ時の直接の取消・プロセス内タイマー依存は見つからず、原因は未確定。OS／メーカーの終了時処理、配信の遅延・抑止、Receiver開始後の状態読込・通知エラーなどを次の一度の診断版で切り分ける。正確アラーム・Foreground Service・常駐化・端末設定変更を推測の修正として追加しない。

## 公式仕様（2026-11-11確認）

- [AlarmManagerの概要と遅延](https://developer.android.com/develop/background-work/services/alarms)：アラームはアプリの実行期間から独立する。不正確な予約で、Android 12以降は省電力制限がない場合でも指定から最大1時間の幅がある。省電力制限があればこの幅を到着保証として使えない。
- [AlarmManager API](https://developer.android.com/reference/android/app/AlarmManager)：PendingIntentを使う方式と、コンポーネントがなくなると取り消され得るListener方式を区別する。現実装は前者。
- [ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo)：API 30以降の終了履歴を補助情報に使う。理由コード10（USER_REQUESTED）はRecentsからの除去と強制停止の双方を含み、これだけで強制停止したと判断しない。履歴がないこともプロセス終了なしの証明にはならない。

一般アプリからOS内の任意アラーム一覧を照会するAPIは使っていない。`FLAG_NO_CREATE` によるPendingIntentの存在は、そのトークンが存在する確認であり、AlarmManager内の予約存続の証明ではない。

## 永続診断

設定→診断→「予約通知の診断」。更新・コピーは読取だけで再予約・AI送信を起こさない。ただし、アプリ起動／復帰時の既存refreshは再予約するため、履歴を比較する。

- 端末内noBackupFilesDirのAtomicFileへ直近128イベントを保存。データは許可したenum・ビルド識別子・時刻・プロセスID・朝夜枠・予約世代・予定時刻だけ。健康値、目標値、人格、回答・短文、回答ID、認可URL、Intent、トークン、生の例外文は含まない。安全な運用メタデータのため診断ファイルは平文で、健康データ・認証情報の暗号化保存先とは別。バックアップ対象外。
- APP_OPEN／APP_VISIBLE／APP_HIDDENでアプリの表示を区別。新規ViewModelの最初のrefresh前にPendingIntentの有無をNO_CREATEで確認し、その後の再登録と混同しない。
- REGISTER_STARTED → REGISTER_RETURNED → LEDGER_SAVEDでAPI受付と台帳保存を分ける。REGISTER_FAILED／LEDGER_FAILED、REGISTER_SKIPPEDも固定理由で記録。
- CANCEL_STARTED／CANCEL_RETURNED／CANCEL_FAILEDは取消経路と固定理由を残す。終了しただけで取消したかをイベントで確認できる。
- RECEIVER_ENTEREDをonReceive冒頭で記録し、HANDLER_STARTED → DELIVERY_STARTED → SETTINGS_LOADED → LEDGER_LOADED → ANSWER_LOAD_STARTED → ANSWER_LOADED → CLAIM_STARTED → CLAIM_SAVED → NOTIFY_STARTED → NOTIFY_RETURNEDを残す。時刻・世代・重複・通知無効などによるスキップも固定分類。
- NOTIFY_RETURNED/POSTEDはnotifyの呼出しが返った状態。続いてOSの自アプリactiveNotificationsに対象タグがあるかを真偽だけ確認する。本文やタグを診断にコピーしない。これも表示・音・ユーザーへの到着を保証しない。
- API 30以降の直近5件のプロセス終了時刻・理由コード・importance、現在の背景制限・省電力除外・Doze・通知可否を表示。端末識別子や生の終了説明／トレースは取得しない。
- 診断の保存失敗を予約や通知の失敗に波及させない。診断はbest effortで、突然の終了・ディスク障害等により最後のイベントが残らない場合はある。イベント不在だけで処理が絶対に実行されていないとは断定しない。

## Androidだけの再試験

1. PRの最新診断APKを既存の「hagemashi 診断」に上書きする。アンインストール・データ消去・署名鍵の再生成は不要。端末の電池・通信・アプリ制限設定は比較の途中で変更しない。
2. 設定→通知で、保存済みのテスト回答Aを対象にする。追加生成やChatGPTサインインは不要。目標期限が切れて再設定待ちなら通常予約は案内通知へ変わるため、今回の回答通知試験は期限待ちでない状態で行う。通知権限・チャンネルが有効であることを確認する。
3. 当日まだ処理されていない朝または夜の1枠だけを有効にし、現地の数分先へ設定する。当日末まで十分な時間を確保する（少なくとも90分程度）。同じ日付＋同じ枠は一度だけで、時刻変更では処理済みを解除しない。既に処理済みなら別の未使用枠か翌日を使う。
4. 設定→診断→予約通知の診断で、現在ビルド・対象枠のREGISTER_RETURNEDとLEDGER_SAVEDを確認してコピーする。予定時刻はtrigger（Unixミリ秒）。この時点の表示だけで到着成功とはしない。
5. 最近使ったアプリ一覧へ移り、hagemashi 診断をスワイプで閉じる。設定からの強制停止・端末再起動はしない。待っている間にアプリを開き直さない。
6. 指定時刻以降の到着時刻を確認する。不正確アラームなので、数分来ないだけで失敗確定にせず、指定時刻と待った時間を記録する。省電力制限なしでも最大1時間の遅延幅がある。制限がある場合はさらに遅れる可能性があり、翌日の持越しはアプリ側で破棄する。
7. 届いたら通知をタップし、Aの全文・生成日時・当時のデータが開くことを確認する。別の新しい回答へ切り替わらないことを確認する。これが実機の合格条件。
8. 届かなければ、待った時間を控えてからアプリを開き直す。設定→診断→予約通知の診断→更新→コピー。起動前のREGISTER_RETURNED／Receiver／通知イベントと、その後のAPP_OPEN・APP_VISIBLE・再登録を比較する。開き直した後に届いた場合は、そのことも区別して報告する。
9. 比較する場合は、別の未処理枠／翌日に同じ手順をホームボタンで離れる操作で試す。両試験で電池・ネットワーク・通知・制限設定を揃え、朝夜枠と待機時間を併記する。健康値・回答・認証画面のスクリーンショットは不要。診断テキストとスワイプ／ホーム、到着有無・待機時間だけでよい。

## 結果の読み方

| 最後の到達点 | 分かること／次の切り分け |
| --- | --- |
| REGISTER_FAILED / LEDGER_FAILED | アプリ側で予約APIか台帳保存に失敗。固定分類から調べる |
| REGISTER_RETURNED / LEDGER_SAVEDがあり、復帰前のReceiver記録なし | OS配信がアプリに到達した記録がない。取消イベント、遅延、制限・終了理由を比較。OSが消したと断定しない |
| RECEIVER_ENTEREDのみ | OSはReceiverへ到達。executor開始前の終了・遅延等を調べる |
| HANDLER_STARTED〜CLAIM_STARTEDで停止 | 状態・回答読込／保存失敗、プロセス中断等を固定分類と照合 |
| DELIVERY_SKIPPED | 世代違い・時間窓終了・重複・通知無効などをreasonで確認 |
| NOTIFY_RETURNED/POSTED・NOTIFICATION_ACTIVE | 通知API成功とOS内のactive状態まで確認。実際の到着は実機で確認する |
| APP_OPEN／APP_VISIBLEの後だけ配信 | 閉じた状態での成功とはしない。起動時の再予約と区別する |

## 検証状態

単体テストでは安全な許可リスト、128件の保持、保存JSONの往復、部分到達点・復帰後の追記、既存配信条件と固定理由の整合を合成データで確認する。Androidテストには別保存先からの永続読取と診断画面の導線を追加。CI結果とAPKリンクはPRに記録する。

今回の実機合格条件は未実施。スワイプ終了後の通知到着を修正済み・動作確認済みとは報告しない。ホーム離脱の到着成功と、今回の診断版の実機成功を区別する。
