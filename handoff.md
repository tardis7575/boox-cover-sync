# BOOX NeoReader Cover Sync — Handoff

## 目標

製作可安裝在 **BOOX Page** 上的 Android App。當使用者用內建 **NeoReader** 開啟書籍時，自動擷取該書封面，並同步設定為：

1. 休眠／待機畫面（screensaver）
2. 完全關機畫面（shutdown image）

首版聚焦 EPUB；PDF 封面與其他格式列為後續功能。不要求 root，完成後輸出可側載的 APK。

## 實機環境

- 裝置：BOOX Page
- Android：11（API 30）
- 螢幕：1264 × 1680
- Launcher/system package：`com.onyx`
- NeoReader package：`com.onyx.kreader`
- Reader activity：`com.onyx.kreader.ui.ReaderTab1Activity`
- ADB 裝置序號：`6D65430F`（僅供開發機辨識，不可寫死在 App）

## 已驗證的完整 PoC

2026-09-14 已在此 BOOX Page 實機完成：

1. 從 `dumpsys activity recents` 的 NeoReader task 讀到 `ACTION_VIEW` intent 與書籍 URI。
2. 將 `content://com.onyx.kreader.onyx.fileprovider/external/...` 對應到 `/sdcard/...` 的 EPUB。
3. 從 EPUB 解出 `OEBPS/cover.jpg`。
4. 將封面寫入 `/sdcard/Pictures/BookCover/current.jpg`。
5. 發送 BOOX screensaver broadcast，休眠後封面正確顯示：

```sh
adb shell am broadcast \
  -a onyx.action.SCREENSAVER \
  --ei type 16 \
  --es file /sdcard/Pictures/BookCover/current.jpg \
  --ez show_result_hint false
```

實機截圖曾確認休眠畫面為《成為對的人，比找到對的人更重要》的正確封面。

## BOOX 廣播介面

參考實作與技術說明：

- https://github.com/lukebatchelor/koreader-boox-shutdown-image
- https://github.com/lukebatchelor/koreader-boox-shutdown-image/blob/main/docs/technical-details.md

`com.onyx` 動態註冊的 `ScreensaverReceiver` 接收：

```text
action = onyx.action.SCREENSAVER
extra type = Int
extra file = String（圖片絕對路徑）
extra show_result_hint = Boolean
```

類型：

| type | 功能 |
| ---: | --- |
| 16 | 固定休眠／待機圖片 |
| 17 | 完全關機圖片 |

App 內必須使用 Android `Context.sendBroadcast()`，不可從普通 App 執行 `am broadcast`；後者會受到 SELinux 限制。每次封面更新時依序送出 `type=16` 與 `type=17`。`type=17` 會讓 BOOX 重新處理並快取關機圖至內部路徑；不可直接寫該內部路徑。

## 關鍵技術風險

### 1. 找出 NeoReader 目前書籍

ADB 的 `dumpsys activity recents` 已驗證可取得完整 URI，但一般第三方 App 沒有 shell 權限。

建議按此順序做 spike：

1. 嘗試一次性 ADB 授予 `android.permission.DUMP`，確認 App 能否透過可用 API／受控 subprocess 取得 task intent。
2. 若不可行，研究 BOOX／NeoReader 是否有可讀的 exported provider、broadcast 或 library metadata。
3. AccessibilityService 只能偵測 `com.onyx.kreader` 進入前景；實測閱讀頁 accessibility tree 沒有書名或檔案 URI，所以不能單獨解決定位書籍。
4. 若系統資訊仍不可用，首版可採「使用者選擇書庫資料夾＋偵測最近變更／最近開啟」的降級方案，但 UI 必須明確顯示目前推定書籍並允許修正。

不要宣稱 AccessibilityService 本身能取得書名；實機已證明閱讀頁只暴露時間、電量、頁碼等資訊。

### 2. 儲存空間

- Android 11 優先使用 Storage Access Framework，讓使用者授權書庫根目錄。
- BOOX 雲端同步書可能位於 `/sdcard/.ksync/NetDisk/...`。
- 本機下載書可能位於 `/sdcard/Download/...`。
- 若採 `MANAGE_EXTERNAL_STORAGE`，必須說明用途，且不應假設能上 Play Store；首版可定位為側載工具。
- 不可把使用者 email、書籍路徑或 ADB serial 寫死。

### 3. EPUB 封面解析

不可只搜尋 `cover.jpg`。必須：

1. 解析 `META-INF/container.xml` 找 OPF。
2. 解析 OPF manifest。
3. 優先處理 EPUB 3 `properties="cover-image"`。
4. 相容 EPUB 2 `<meta name="cover" content="...">`。
5. 正規化相對路徑、URL encoding 與大小寫。
6. 防止 Zip Slip、zip bomb 與超大圖片造成 OOM。

輸出圖片要依 1264 × 1680 等比例縮放，預設 `fitCenter` 並補白；提供 `centerCrop` 選項。保留原始封面比例，不要拉伸。

### 4. BOOX 相容性

`onyx.action.SCREENSAVER` 是未公開介面，必須封裝在單一 adapter，不能散落在 UI 或 service 中。啟動時確認裝置 manufacturer/package，廣播失敗時顯示可操作錯誤。

BOOX 會自動凍結未常用 App。安裝後須引導使用者：

- 從 BOOX launcher 開啟 App 一次。
- 長按 App 圖示，選擇「解除凍結／Unfreeze」。
- 允許背景執行或停用該 App 的背景凍結。

## MVP 架構

建議 Kotlin＋Gradle Android 專案，package 暫定：

```text
tw.mustp.booxcoversync
```

模組／責任：

```text
app/
  reader/       偵測 NeoReader 狀態、解析目前書籍 URI
  epub/         安全解析 EPUB metadata 與封面
  image/        縮放、灰階預覽與輸出 current.jpg
  boox/         Screensaver broadcast adapter（type 16、17）
  service/      Accessibility／前景偵測與去重排程
  ui/           設定、權限引導、目前書籍、測試按鈕與錯誤狀態
```

資料流：

```text
NeoReader 進入前景
→ 解析目前書籍 URI
→ URI 與上次不同才處理
→ 讀取 EPUB metadata
→ 解出並縮放封面
→ 原子寫入 current.jpg
→ sendBroadcast(type=16)
→ sendBroadcast(type=17)
→ UI 記錄成功時間與書名
```

## MVP UI

單頁即可，至少包含：

- 目前偵測書籍與封面預覽
- 「同步休眠畫面」與「同步關機畫面」開關，預設皆開
- 「立即重新同步」按鈕
- 書庫資料夾、Accessibility、背景執行權限狀態
- 最近一次成功／失敗時間與具體錯誤

所有自動化都必須可關閉。不可持續輪詢；以事件觸發為主並做 debounce。

## 驗收條件

1. 在 NeoReader 開啟 EPUB A，10 秒內 App 顯示正確書名及封面。
2. 讓裝置休眠，顯示 EPUB A 封面。
3. 完全關機，顯示 EPUB A 封面。
4. 開機後改開 EPUB B，兩種畫面皆更新為 B。
5. 同一本書重複切回 NeoReader不重做圖片處理。
6. 書籍無封面、損壞 EPUB、路徑無權限時不 crash，UI 顯示修復方法。
7. 重開機後功能仍可恢復；若遭 BOOX 凍結，能診斷並提示解除凍結。

## 實作順序

1. 建立最小 Android App，做手動選 EPUB → 解析封面 → type 16＋17 廣播。
2. 在 BOOX Page 實機驗證休眠與完整關機畫面。
3. 對「取得 NeoReader 目前 URI」做獨立 spike，先驗證權限，不先做完整 UI。
4. 加入事件觸發、自動同步、去重與重開機恢復。
5. 補單元測試、錯誤 UI、README、ADB 安裝與權限腳本，輸出 debug APK。

## 禁止事項

- 不 root、不修改 `/system` 或 `/data/local/assets`。
- 不逆向或散布 BOOX 私有 APK／使用者書籍。
- 不記錄或上傳完整書籍內容、帳號、路徑或閱讀紀錄。
- 不把 ADB 當成 App 長期執行時的必要依賴；ADB 只可用於開發、授權與診斷。
- 未完成實機關機測試前，不可宣稱 `type=17` 已在 BOOX Page 完整驗證。

## 新 task 的立即工作

1. 初始化 Git 與 Android Gradle 專案。
2. 先完成「手動選 EPUB → 封面預覽 → 同步 type 16＋17」垂直切片。
3. 建立 `NeoReaderLocator` spike，列出每種方案的實機證據與最低權限。
4. 執行測試並側載 debug APK 到序號 `6D65430F` 的 BOOX Page。
5. 在需要完全關機驗證前停下，請使用者確認後再關機。

## 參考資料

- BOOX 手動將書封設為 screensaver：<https://booxsupport.zendesk.com/hc/en-us/articles/4577439831828-How-to-set-a-book-cover-as-a-screensaver>
- KOReader Cover image：<https://github.com/koreader/koreader/wiki/Coverimage>
- BOOX sleep/shutdown broadcast 實作：<https://github.com/lukebatchelor/koreader-boox-shutdown-image>
- 技術細節：<https://github.com/lukebatchelor/koreader-boox-shutdown-image/blob/main/docs/technical-details.md>
- Codex Worktrees／Handoff：<https://learn.chatgpt.com/docs/environments/git-worktrees>
