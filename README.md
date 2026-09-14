# BOOX NeoReader Cover Sync

可側載到 BOOX／Onyx 裝置的 Android MVP。使用者手動選取 EPUB，App 會安全解析封面、產生 BOOX Page 尺寸的預覽與雙槽圖片（`current-a.jpg`／`current-b.jpg`），再依開關透過 BOOX 廣播同步休眠畫面（`type=16`）與關機畫面（`type=17`）。

目前版本是手動垂直切片：尚未把 NeoReader 自動定位接到 UI；`type=16` 休眠畫面與 `type=17` 完全關機畫面均已在 BOOX Page 實機驗證。

## 目前功能

- Android 檔案選擇器（Storage Access Framework）選取 EPUB。
- 解析 `META-INF/container.xml`、OPF manifest、EPUB 3 `properties="cover-image"` 與 EPUB 2 `meta name="cover"`。
- 防止 Zip Slip、重複路徑、過大 archive／entry／圖片、過高 compression ratio 與圖片解碼 OOM。
- Android 執行時使用 fail-closed 的 `XmlPullParser` 解析 container／OPF：拒絕 `DOCTYPE` 與 `ENTITY`，解析或 parser 初始化失敗就拒絕 EPUB；JVM 測試環境才使用具同等安全設定的 JAXP parser。
- 封面預覽與輸出固定為 `1264 × 1680`；預設 `FIT_CENTER` 白底留白，保留比例。
- 以同目錄暫存檔加 rename 原子寫入 `BookCover/current-a.jpg` 或 `BookCover/current-b.jpg`；每次成功寫入輪替絕對路徑，最多保留兩個槽位，避免 BOOX 依圖片路徑快取舊內容。
- 優先嘗試公共路徑 `/sdcard/Pictures/BookCover/current-a.jpg`／`current-b.jpg`；若 Android 11 儲存限制拒絕，fallback 到 app-specific Pictures 或 internal files 路徑。UI 會顯示實際輸出路徑。
- 依 UI 開關順序發送 `onyx.action.SCREENSAVER` 的 `type=16`、`type=17`；廣播由 Kotlin `Context.sendBroadcast()` 以 implicit intent 發送，不依賴 ADB，也不可加 `setPackage("com.onyx")`。
- `NeoReaderLocator`／`dumpsys activity recents` 僅為權限 spike；不會長期執行 ADB，也不會讀取或上傳書籍內容。

## 限制與安全界線

`onyx.action.SCREENSAVER`、`type=16/17` 與 `com.onyx` 是未公開 BOOX 介面。實機已證明 BOOX receiver 會依圖片絕對路徑快取：固定寫入 `current.jpg` 時，即使檔案內容更新，receiver 仍可能顯示舊圖；改用新路徑後，`type=16` 才能立即看到新內容。因此 CoverFileWriter 現在只在 `current-a.jpg`／`current-b.jpg` 兩個 app-owned 槽位間輪替，並透過回傳的實際路徑送出廣播，最多保留兩個圖片檔。

BOOX receiver 是 dynamic receiver，因此廣播必須保持 implicit；先前把 `type=16` 廣播設成 explicit `com.onyx` package 的實機嘗試曾失敗，現已移除 `setPackage`。雙槽與 implicit broadcast 修正後，已在 BOOX Page 由 App 選取合成 EPUB、輸出 `current-a.jpg` 並成功顯示新的休眠圖，確認 `type=16` 可用。2026-09-14 經使用者明確確認後，使用相同 `current-a.jpg` 重新送出 `type=17` 並讓 BOOX Page 完全關機；使用者目視確認關機畫面正確顯示白底、黑框、中間寫有 `CODEX` 的測試封面，因此 `type=17` 完全關機驗證亦已通過。

本 repo 目前沒有宣稱以下結果：

- NeoReader 自動定位已可在一般第三方 App 權限下運作。

後續若要再次進行完全關機驗證，仍須先由使用者明確確認。不得 root、不得修改 `/system` 或 `/data/local/assets`，不得把書籍、帳號、完整路徑或閱讀紀錄上傳。

## 建置與測試

需求：JDK 17、可用 Android SDK、Gradle wrapper。Windows PowerShell：

```powershell
Set-Location 'C:\Users\mustp\Documents\Codex\2026-09-14\boox-cover-sync'
.\gradlew.bat test
.\gradlew.bat assembleDebug
```

產物位於：

```text
app/build/outputs/apk/debug/app-debug.apk
```

若 Gradle 首次執行需要下載 wrapper 或 dependencies，請確認網路與 Android SDK 設定；不要把 APK、`local.properties` 或含私人資料的檔案提交到 Git。

## 側載到 BOOX

將 `<SERIAL>` 替換成使用者自行確認的 ADB serial。serial 只是開發／診斷辨識，不是 App 的必要設定，也沒有寫死在程式中。

```powershell
adb devices
adb -s <SERIAL> install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s <SERIAL> shell monkey -p tw.mustp.booxcoversync 1
```

BOOX 可能會自動凍結不常用 App。安裝後請從 BOOX launcher 開啟一次 App，長按 App 圖示選「解除凍結／Unfreeze」，並在 BOOX 設定中允許背景執行（若該韌體提供此選項）。若 App 被凍結，事件觸發與後續同步不會可靠執行。

## 使用流程

1. 開啟 `BOOX Cover Sync`。
2. 點「選擇 EPUB」，使用 Android 檔案選擇器選取書籍。
3. 確認書名與封面預覽；解析失敗時依畫面錯誤重新選取可讀且未損壞的 EPUB。
4. 確認「同步休眠畫面（type=16）」與「同步關機畫面（type=17）」開關。兩者預設開啟，至少要開啟一個。
5. 點「立即重新同步」。畫面會顯示成功／失敗、已送出的 type，以及實際輸出圖片路徑。
6. Android 11 若需要公共路徑，點「開啟公共儲存空間設定」並授予本 App 的「允許管理所有檔案」權限；拒絕時 App 仍會嘗試 app-specific fallback，但 `com.onyx` 系統服務是否能讀取該路徑不保證。

公共 `Pictures` 路徑是優先選項，因 BOOX 系統 receiver 通常需要讀取絕對路徑。app-specific fallback 可能位於類似 `/sdcard/Android/data/tw.mustp.booxcoversync/files/Pictures/BookCover/current-a.jpg` 或 `current-b.jpg`；不要假設 `com.onyx` 一定能讀取該路徑，請以 UI 顯示的實際路徑與裝置結果為準。

## MANAGE_EXTERNAL_STORAGE

Manifest 宣告 `android.permission.MANAGE_EXTERNAL_STORAGE`，僅用於 Android 11+ 讓使用者選擇是否授予公共儲存空間存取。App 會在 UI 顯示目前狀態，並使用系統設定頁引導授權：

```text
設定 → 應用程式 → BOOX Cover Sync → 允許管理所有檔案
```

這是側載工具的裝置權限取捨，不代表可直接上架 Google Play；請依裝置 Android 版本與政策自行評估。若不授權，仍可使用檔案選擇器的 URI grant 讀取所選 EPUB，但公共 `Pictures/BookCover` 寫入可能失敗，程式會依序嘗試 app-specific／internal fallback。

## NeoReaderLocator DUMP spike

`NeoReaderLocator` 的最低權限條件是兩層：

- `android.permission.DUMP`：通常是 `signature` protection，第三方 App 一般無法自行取得；本 spike 只允許以 ADB 嘗試一次性授予。
- `android.permission.PACKAGE_USAGE_STATS` 加上 `GET_USAGE_STATS` app-op：Manifest 已宣告，但仍須使用者在 Usage Access 設定頁授權 app-op。

正常第三方 App 沒有 DUMP 是預期狀態；沒有任一條件時 locator 會停止並回報權限錯誤，不執行後續定位。App UI 會顯示目前 Usage Access 狀態，並提供「開啟 Usage Access 設定」按鈕，可前往「設定 → 特殊應用程式存取權 → 使用狀況存取權」授權本 App。即使 DUMP 不可用，AccessibilityService 也只能偵測 `com.onyx.kreader` 進入前景；本次實機證據顯示閱讀頁 accessibility tree 沒有書名或檔案 URI，因此 Accessibility 無法取得 URI。

App 中的 locator 會先檢查 DUMP，再檢查 Usage Access；兩者通過後才嘗試一次受控的 `dumpsys activity recents`。它不是自動同步流程的必要依賴，也不會把 ADB 綁在 App 的長期執行路徑。

## ADB 診斷腳本

`scripts/boox-dump-diagnostics.ps1` 只讀取裝置狀態與 recents 證據，不讀取或上傳書籍 bytes。請在 PowerShell 執行：

```powershell
Set-Location 'C:\Users\mustp\Documents\Codex\2026-09-14\boox-cover-sync'
.\scripts\boox-dump-diagnostics.ps1 -Serial '<SERIAL>'
```

選項：

- `-GrantDump`：嘗試以 ADB 授予 `android.permission.DUMP`；signature 權限拒絕是預期結果，腳本會顯示 warning。
- `-GrantUsageStats`：嘗試以 ADB 設定 `android:get_usage_stats` app-op；若裝置政策拒絕，請改用 App 的 Usage Access 設定入口。
- `-RawRecents`：保留診斷流程選項，但現在刻意不輸出 recents 原文，以免洩漏真實 content URI 或檔案路徑。

建議先在 NeoReader 開啟 EPUB，再執行：

```powershell
.\scripts\boox-dump-diagnostics.ps1 -Serial '<SERIAL>' -GrantDump
.\scripts\boox-dump-diagnostics.ps1 -Serial '<SERIAL>' -GrantUsageStats
.\scripts\boox-dump-diagnostics.ps1 -Serial '<SERIAL>' -RawRecents
```

腳本只回報套件是否安裝、Usage Stats app-op 狀態、recents 是否被拒絕，以及是否找到 NeoReader `ACTION_VIEW` 記錄；真實 content URI 與檔案路徑一律不輸出。請勿自行改腳本輸出私人書籍路徑後公開或提交。

## 開發狀態與驗收

已涵蓋：手動選 EPUB、封面安全解析、預覽、圖片原子寫入、BOOX implicit 廣播 adapter、type 16/17 順序與單元測試，以及 DUMP + Usage Access locator/parser 測試。Android XML 路徑會 fail-closed 拒絕 `DOCTYPE`／`ENTITY`。

實機已完成 `type=16` 休眠畫面與 `type=17` 完全關機畫面驗證。尚待後續：把已完成的 NeoReader 定位權限 spike 整合成事件觸發、自動同步、去重與重開機恢復；未完成前，不要把手動 MVP 描述成「自動同步」。

## 參考

- [BOOX：將書封設為 screensaver](https://booxsupport.zendesk.com/hc/en-us/articles/4577439831828-How-to-set-a-book-cover-as-a-screensaver)
- [KOReader Cover image](https://github.com/koreader/koreader/wiki/Coverimage)
- [BOOX sleep/shutdown image PoC](https://github.com/lukebatchelor/koreader-boox-shutdown-image)
- [技術細節](https://github.com/lukebatchelor/koreader-boox-shutdown-image/blob/main/docs/technical-details.md)
