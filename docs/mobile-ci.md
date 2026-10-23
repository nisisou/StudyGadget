# スマホからCIビルドする

スマホでソースを編集・コミット → GitHub Actionsでビルド → 更新ファイルをスマホに保存
→ Study Companionで取り込み → スマホ版と3DS版を更新できます。ビルド中にPCを起動しておく必要はありません。

## このプロジェクトの実行ページ

ソースとCI設定は [nisisou/StudyGadget](https://github.com/nisisou/StudyGadget) に登録済みです。
スマホでは [Mobile CI](https://github.com/nisisou/StudyGadget/actions/workflows/mobile-update.yml) を開き、
**Run workflow → target: 3ds → Run workflow** でビルドできます。
`version_code` と `version_name` は空欄のままで使えます。
Androidを含む `android` / `both` は、下の「初回：Androidの署名キー」を一度設定してください。
ChatGPTの「自動レビュー」のリポジトリ選択は、このCIの実行には不要です。

## 今回用意したもの
- .github/workflows/mobile-update.yml：手動ビルド・main/masterへのpushビルド。
- 3ds / android / both の選択。最初は署名設定不要の3dsを選んでください。
- Android versionCodeの自動採番（100000 + GitHubの実行番号）。手動指定も可能です。
- SHA-256検証付きの StudyGadget-update.sgupdate、APK、3DSX、検証情報。
- 成功した実行のSummaryにスマホ用ダウンロードリンクを表示。
- Android APKは現在のアプリと同じ署名で作成。異なるキーはビルド前に拒否します。
- 公開Releaseの作成や、自宅サーバーへの接続は行いません。

## 別のリポジトリへ登録する場合
この作業フォルダーにはまだGitリポジトリとremoteの設定がありません。
ChatGPTのGitHub連携を使う場合も、GitHubの保存先リポジトリを選ぶ必要があります。
GitHub連携の失敗と、GitHub Actionsのビルド失敗は別です。

GitHubに既存リポジトリがあるなら、そのリポジトリにこのプロジェクトのソースを入れます。
新規作成なら公開範囲を自分で選び、通常はprivateで始められます。
release/StudyGadget-mobile-ci-source.zip は登録用のソース一式です。
ZIPをそのままGitHubへアップロードしても展開はされません。
展開した中身をGit対応エディターでコミットするか、PCでgit pushしてください。
.gitignore / .gitattributes / .github ディレクトリも必要です。
特に .github/workflows/mobile-update.yml をデフォルトブランチ（通常main）へ入れてください。

GitHub公式：
- 手動実行：https://docs.github.com/actions/managing-workflow-runs/manually-running-a-workflow
- GitHub連携：https://help.openai.com/en/articles/11145903-connecting-github-t-chatgpt-deep-research

## スマホから手動ビルド
1. スマホのブラウザーでGitHubへログインし、対象リポジトリを開きます。
2. Actions → Mobile CI → Run workflow。
3. targetは3ds / android / bothから選択します。
4. version_code / version_name は空欄で自動採番します。
   既に手動で大きなversionCodeを使った場合は、それ以上の値を指定してください。
5. Run workflowを押して待ちます。
6. 成功した実行のSummaryから StudyGadget-update.sgupdate を保存します。
   操作欄が見えない場合はブラウザーのPC版表示も試してください。

デフォルトではmain/masterへのソース変更のpushでも3DS版をビルドします。
Androidも自動ビルドする場合は、後述の署名設定後に
Settings → Secrets and variables → Actions → Variablesで
MOBILE_CI_TARGET=both を登録できます。

## 初回：Androidの署名キー
3DSだけなら、この節は不要です。

現在スマホに入っているアプリを上書き更新するには、同じ署名キーが必要です。
このPCで使っていたdebug.keystoreを、保存先リポジトリの
Settings → Secrets and variables → Actions → New repository secret
へ次の名前で登録します。

ANDROID_SIGNING_KEYSTORE_BASE64

このPCでは下記を実行すると、キーのBase64をクリップボードにコピーします。
スクリプトはGitHubへ送信しません。自分でSecrets欄へ貼り付けます。

powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/copy-ci-signing-key.ps1

既定は assets/.android-local/debug.keystore です。別の場所なら -Keystore に絶対パスを指定します。
キーそのものをソース、チャット、Issueへ貼り付けないでください。
ci/android-signing-certificate.sha256 は既存APKから取り出した公開証明書の指紋だけです。
CIはこの指紋と照合し、一致したキーだけを使います。
秘密キーは一時ディレクトリに置き、成果物へ含めません。

## スマホと3DSへ更新を反映
1. StudyGadget-update.sgupdate を親機スマホへ保存します。
   対応するダウンロードではそのまま保存できます。
   ZIPとして保存された場合は外側のZIPだけ展開し、中の.sgupdateは展開しません。
2. Study Companion → アプリの更新・アップロード → 更新ファイルを選ぶ。
3. スマホ版を含む場合は「スマホ版を更新」からAndroidの確認画面へ進みます。
4. 3DS版を含む場合は親機サーバーON、3DSでUPDATE → INSTALL → EXIT → HBLから起動し直します。

既存のVS Code自動更新予約を使うこともできますが、今回のCIダウンロードだけで
3DSへの自動適用コマンドが発行されるわけではありません。
CIAのビルド・自動更新はこのCIの対象外です。
3DSX・APKの更新対応版を最初に通常の方法で導入しておく必要があります。

## 接続できませんと表示される場合
GitHubの追加とアカウント接続は別です。
ChatGPT側のGitHub設定からアカウント接続状態を確認し、必要なら接続／再接続を行ってください。
接続済みでリポジトリが見えない場合は、GitHubアプリに対象リポジトリへのアクセスがあるか確認します。
このChatGPT連携を使わず、自分のGitHubブラウザーからActionsを実行することもできます。
キーやログイン情報をチャットへ送る必要はありません。

## 検証範囲
入力検証、3種類の更新パッケージ作成、署名照合、ワークフロー構文をローカルで検証します。
2026-10-23、[初回GitHub Actions実行](https://github.com/nisisou/StudyGadget/actions/runs/39875777511)で、
3DSのソースビルド、キャラクター検証、更新ファイル作成、成果物アップロードまで成功しました。
[初回の更新ファイル](https://github.com/nisisou/StudyGadget/actions/runs/39875777511/artifacts/12468800690)は14日間保存されます。
AndroidのCI実行と、クラウドで生成した3DS版の実機起動は未確認です。
devkitProコンテナーは公式 devkitpro/devkitarm:latest を使用します。
特定の版に固定したい場合はGitHub ActionsのRepository Variable DEVKITPRO_IMAGEへ
公式イメージの確認済みタグまたはdigestを登録してください。

