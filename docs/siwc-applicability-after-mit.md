# MIT採用後に残るSIWCの適用条件

公式資料の再確認日: 2026-10-30（UTC）。本資料は初回の[接続調査](chatgpt-connection-spike.md)に対する更新。

## ライセンスの確認結果

ユーザーの指示でhagemashiの独自コード・ドキュメントにMITを採用する。MITの許諾とOpenAIサービスへのアクセス権は別であり、**MIT採用だけでSIWCの利用条件を満たしたとは判断しない**。特定のライセンスをOpenAIが必須指定しているという根拠は、今回確認した資料にはない。

変更前の追跡ファイルを調べたところ、アプリ固有の著作権表示とLICENSE / COPYINGはなかった。Gitの作者名は権利者確定の根拠にせず、ユーザーに確認した表記 `K.` を採用した。開発履歴の開始年は2026年で、追加するLICENSEの表示は `Copyright (c) 2026 K.` とした。

既存の第三者表示は次のとおり。これらは変更・置換しない。

| ファイル | 既存表示 |
| --- | --- |
| `gradlew` | Copyright © 2015-2021 the original authors. / Apache License 2.0 / SPDX Apache-2.0 |
| `gradlew.bat` | Copyright 2015 the original author or authors. / Apache License 2.0 / SPDX Apache-2.0 |

Gradle Wrapper JARや依存ライブラリをhagemashiのMITへ再ライセンスするものではない。READMEでMITの対象と第三者コードの扱いを区別する。MIT本文の照合元は[Open Source Initiative](https://opensource.org/license/mit)。

## 公式に確認できた共通条件

- [OSS Overview](https://developers.openai.com/siwc/token-sharing-open-source): OSS／ローカルホストアプリ向けにChatGPTプラン利用のフローがある。有料・リモートホストでの提供には掲載のinterest formが案内される。MITが商用利用を許諾していても、SIWC商用提供の承認を意味しない。
- [Quickstart](https://developers.openai.com/siwc/quickstart): 対象としてeligibleなPlus／Proを明記。本人確認だけのscopeでは推論できず、プラン利用の権限とユーザー同意が別途必要。他プランや全ユーザーの利用可能性は保証しない。
- アカウント／workspaceごとの登録・権限・利用枠、地域・モデル・ルートの制限は残る。実際のトークン応答とAPI応答で確認する。上限に達してもAPIキー課金へ自動切替しない。

## Androidのloopback認証

仕様の根拠: [Registration and sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in) の Start authorization / Handle the callback。

| 区分 | 内容 |
| --- | --- |
| 公式確認済み | 外部のシステムブラウザで認可。開始前にcallback listenerを起動し、認証試行ごとにstate・nonce・PKCE S256を用意する |
| 公式確認済み | 初回から `http://127.0.0.1:<port>/auth/callback` のHTTP loopbackを使う。後の認証で変えられるのはport。scheme・host・pathは維持し、`localhost`に置換しない。認可とコード交換で同一URIを使う |
| 公式確認済み | 初回の動的登録で発行されたclient IDを交換・再認証に使い、安定したhost IDを保持する。stateとID tokenを検証し、付与された推論scopeを確認する |
| 未確認（適用範囲） | このフローのAndroid public clientへの明示的な対応表明・公式Android実装例は、調査した資料では確認できなかった。Androidが禁止／対象外だとする記述も確認できていない |
| 未確認（代替callback） | AndroidのApp LinksやカスタムURI schemeをこの動的登録フローで使える根拠は未確認。一般のAndroid OAuth手法から流用可能とは判断しない |
| 未検証（動作） | 外部ブラウザからloopbackへの到達性、ブラウザ滞在中のlistener維持、プロセス終了・回転・再起動、port競合、認証キャンセル、callback重複や不正stateへの対応 |

仕様がAndroidを明記していないことと、実機で動作しないことは別である。実装・実測が成功しても、公開配布時のサービス適用条件を自動的に確認したことにはならない。

## ユーザー操作なしの定期生成

| 区分 | 内容・根拠 |
| --- | --- |
| 公式確認済み | 認可scopeに `offline_access` があり、refresh tokenによる更新手順がある。同一sessionの更新は直列化し、回転するtokenをまとめて保存する。[Accounts and sessions](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions) |
| 公式確認済み | SIWCのHTTP推論は `store=false` と `stream=true` が必要。`background` は非対応フィールド。[Preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations) |
| 未確認（利用条件） | 初回の同意後にAndroidが毎日無操作で推論する用途について、明示的な許可・禁止、追加同意、実行頻度・長期無操作に固有の条件は、今回の資料から確定できなかった |
| 未確認（解釈） | `offline_access`は更新可能性の根拠であり、任意の無人処理全般の承認と解釈しない。数分後の単発予約が成功しても、毎日の反復利用条件を確認したことにはならない |
| 未検証（Android実行） | OSの実行遅延・電池制限・強制停止・ロック中・再起動後・通信失敗時の生成、通知権限、更新や失効・利用上限時の挙動 |

**API backgroundとAndroidバックグラウンド実行は別。** API backgroundはサーバー側の非同期生成・polling機能。Androidの予約処理は、後でOSが起動したworkerから通常のSSEリクエストを送る構成であり、APIの`background=true`を必要としない。ただし、この構成を技術的に考えられることは、SIWCの無操作反復利用条件を確認できたことにはならない。

## 残課題と判断

MIT採用に関するユーザーの選択は確定した。残る適用条件は次のように分ける。

1. **サービスの適用確認**: AndroidでのOSS public clientとHTTP loopback利用、同意後の無操作・反復推論、有料化やリモートホスト時の扱いを公式窓口／今後の公式仕様で確認する。問い合わせは未送信で、回答・承認も得ていない。
2. **実装による検証**: 適用範囲を踏まえ、Keystore保存、token検証・更新・解除、モデル取得、SSE完了判定と単発予約を最小版で試す。今回ライセンス変更のPRでは接続実装は追加しない。
3. **ユーザーの実機確認**: サインイン、回答、再起動後の接続維持、画面終了中の生成通知を確認する。更新・上限等は個別に試すまで未検証とする。

現状はSIWCを利用可能／Android動作確認済みとは報告しない。また、現行資料だけから実現不能とも断定しない。MIT採用を理由にこれらの確認を省略しない。
