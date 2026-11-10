# 固定プロンプト生成のHTTP200／PROTOCOL_ERRORを切り分ける

後続のユーザー実機試験で、完全なresponse.completedの受信と回答本文の画面表示に成功した。アプリを閉じて開いた後の再ログインなしの回答取得と、接続解除後の生成禁止も確認済み。[受け入れ記録とAPK照合](chatgpt-device-acceptance.md)を最新の結果とする。以下の原因未確定・実機未確認の記述は、修正過程の時点記録である。

## 標準の認証と今回の範囲

ユーザー実機でブラウザからアプリへ戻るサインイン、モデル一覧取得、接続解除が成功した。この認証を標準として採用し、認証フロー・署名・applicationId・通信方式を変更しない。今回の調査は生成JSONとHTTP/SSE受信処理だけ。モデルはアカウントの一覧から選び、表示名ではなくslugを送信する。

## 2026-11-09の公式仕様との照合（APK再ビルド前）

| 項目 | 現行コードと仕様の照合 |
| --- | --- |
| エンドポイント・認証 | public Responses APIへOAuthアクセストークンをBearer送信。APIキー方式への変更はない |
| モデル | 同じアカウントのモデル一覧を取得し、選択されたslugをmodelに送る。表示名の誤送信は見つからない |
| JSON | model、instructions、store:false、stream:true、配列input内のrole:user／content。公式要件と一致 |
| 非対応フィールド | background、previous_response_id、temperature、max_output_tokens等を追加していない |
| 完了条件 | HTTP200や[DONE]だけで成功にしない。response.completedを完全なイベントとして受信し、completed状態と非空回答を検証する |
| エラー | response.failed、response.incomplete、error、途中EOF、読み取り例外を完了と分ける必要がある。従来の同じPROTOCOL_ERRORだけでは実機原因を区別できない |

参照：[SIWCモデルと生成](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)、[SIWC Preview制限](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)、[Responsesストリーミング](https://developers.openai.com/api/docs/guides/streaming-responses)、[WHATWG SSE解析仕様](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream)。

## APKを作らずに再現した受信処理の問題

既存ProtocolTestへ合成SSEの3ケースを加え、旧処理では18件中3件失敗をJVMで確認した。修正後は同じ18件が成功した。

- 最初のUTF-8 BOMがdataフィールド名に混ざると、有効なイベントを読み飛ばす。
- CR単独改行の複数イベントを正しく分割できない。
- イベントを閉じる空行がないままEOFになると、未完成の完了イベントをdispatchして成功扱いにする。

SSE専用の有界行読み取りでLF／CRLF／CRと先頭BOMを扱い、EOF時の未完成イベントは破棄して失敗にする。loopback HTTPリクエスト行の読み取りは変更しない。Content-Typeがある場合はprefix一致をやめ、パラメータを除くmedia typeの完全一致（大文字小文字を区別しない）で判定する。text/event-streaming等は受け入れない。取得できない場合の扱いは下記の実機結果を受けて修正した。

これらは再現できた不一致であり、ユーザー実機のHTTP200後の失敗原因と確定したものではない。実際のContent-Typeや最終イベントの情報が旧版にはないため、一度の診断版で以下を区別する。

## 実機で特定した拒否条件と修正

ビルド42551522059.1の実機診断は、HTTP200／content_type=MISSING／body=PRESENT／step=CONTENT_TYPE／end=MEDIA_REJECTEDだった。本文は受信していたが、Content-Typeを取得できず、SSE解析に入る前にアプリが拒否していた。サーバーが本当にヘッダーを送らなかったのか、途中経路・Androidの取得処理の影響かまでは、この情報だけでは断定しない。本文が有効なSSEだったかも未確認。

公式Python SDKの確認したrevisionは3774b7ed775b4bff99b45ef961c5d1f6c82b8e99。[BaseAPIResponse._parse](https://github.com/openai/openai-python/blob/3774b7ed775b4bff99b45ef961c5d1f6c82b8e99/src/openai/_response.py)は明示的なstream指定をContent-Type検査より先に処理し、[Stream](https://github.com/openai/openai-python/blob/3774b7ed775b4bff99b45ef961c5d1f6c82b8e99/src/openai/_streaming.py)へ渡してSSEを解析する。アプリへSDKを導入したり、APIキー方式へ変更するものではなく、明示したSSEの受信方針を照合した。

このアプリも、HTTP200かつContent-Typeが取得できない／空の場合は、明示的に要求したSSEとして同じ有界パーサーへ渡す。診断のMISSINGをEVENT_STREAMへ偽装しない。既知のJSON／HTML／OTHERは従来どおり拒否する。別のエンドポイント・形式へ再送したり、JSON本文を回答として読み替えたりしない。

ヘッダーがなくても、完全なresponse.completed・completed状態・非空回答を検証できた場合だけ回答をUIへ渡す。ヘッダー欠落＋有効SSE、ヘッダー欠落＋JSON／HTML／部分回答／空本文、ヘッダー欠落＋failedを合成テストで確認する。修正前のGenerationTestは21件中3件失敗を再現。今回の拒否条件を直しても、実機で回答表示まで成功したとはユーザー試験前に断定しない。

## 安全な生成診断

生成終了後、回答欄の下の「直近の生成診断」に表示し、「生成の安全な診断をコピー」で固定形式だけをコピーする。新しい生成では前回結果を消し、接続解除で診断も消す。端末内ファイル・ログへ保存しない。

| フィールド | 意味 |
| --- | --- |
| build／applicationId／variant | 検証したビルド。任意の文字列は反射しない |
| step／http | 固定処理段階と100〜599のHTTP状態、または未取得 |
| content_type | EVENT_STREAM／JSON／HTML／OTHER／MISSING／NOT_RECEIVED。生のヘッダー値を出さない |
| body | PRESENT／ABSENT／UNKNOWN。受信ストリームの有無で、回答の有無とは別。最初の1byteを確認して戻し、値は記録しない |
| last_known_event | 固定許可リストの最後の既知イベント。未解析の不完全フレームはイベント受信としない |
| unknown_event_seen | 許可リスト外のイベントを受けたかだけ。未知の名前を出さない |
| terminal | NONE／COMPLETED／INCOMPLETE／FAILED／ERROR |
| end／reason | 完了、未完了、失敗、完了なしEOF、フレーム途中EOF、IO切断、timeout、cancel、media拒否、JSON／構造不正、空回答、refusal、上限超過等の固定分類 |
| answer_present | 受信した回答テキストが非空か。テキストや文字数は出さない |
| completion_validated | 完全なresponse.completed、completed状態、非空回答を検証できたか |
| answer_in_ui | 検証後の回答を画面状態へ渡したか。ピクセル表示やユーザーが読めたことの自動証明ではない |

完了前の部分テキストは回答欄へ表示しない。プロンプト、人格文、回答本文、モデルからの拒否文、生の応答、トークン、任意のサーバーエラー文・未知のイベント名は診断に入れない。正しく完了した回答だけはアプリ本来の回答欄へ表示する。

HTTP200の途中でfailed／incompleteが来ても成功にしない。usage-limit等は既存の許可済みエラー分類を維持する。Content-Typeを拒否した場合は本文の有無だけを確認し、生の本文を収集しない。途中のIO例外やtimeoutでも最後の既知イベントとHTTP状態を保持する。新しいエンドポイント・JSONへのfallback・自動再試行は追加しない。

## Androidだけで行う一括試験と合格条件

1. 最新のhagemashi-diagnostic-apkを展開し、現在の「hagemashi 診断」へ上書きする。既存版をアンインストールせず、署名鍵を再生成しない。
2. 既に接続済みならそのまま利用。必要なら標準のContinue with ChatGPT→ブラウザで認証→受付表示後にブラウザのタブを閉じてアプリへ戻るで接続する。分割画面は使わない。
3. モデル一覧を取得し、前回と同じGPT-6.1-Solを選択。アプリを表示した状態で「ダミーデータで生成」を一度押し、終了を待つ。
4. **固定プロンプト送信→完全なresponse.completed受信→非空の回答本文がアプリ画面に表示**された場合だけ合格。HTTP200だけ、部分回答だけ、terminal=COMPLETEDだけでは合格にしない。
5. 「生成の安全な診断をコピー」で結果を報告する。成功時はlast_known_event=response.completed、end=COMPLETED、reason=NONE、completion_validated=true、answer_in_ui=trueが目安だが、回答欄を実際に確認する。回答本文やアカウント情報の撮影・貼り付けは不要。
6. 失敗時も同じコピーだけで、Content-Type・最後の既知イベント・未完了／途中切断・本文の有無を区別できる。モデル変更や繰り返し生成、認証情報を取り出したcurl試験は求めない。

JVM単体テストは合成データと偽のHTTPS接続で検証し、実アカウントや推論サービスへ送信しない。Android実機での生成合格はユーザー確認まで未確認。認証更新・利用上限・Androidへの正式適用・公開条件についても未検証事項を維持する。
