# KOReader／BOOX 封面流程研究

研究日期：2026-09-15

## 結論

KOReader 的關鍵不是偵測其他閱讀器，而是它本身就是閱讀器，因此能在文件完成載入後收到 `ReaderReady` 事件，直接取得 `ReaderUI.document`。它把處理後的封面寫入一個固定檔案；在 BOOX 上，系統的 Screensaver 設定再去讀那個檔案。

這個專案可以效法「開書完成後只做一次同步」的事件時序、封面處理與匿名去重；不能直接移植 KOReader 的 `ReaderReady`，因為本專案不是 NeoReader 內部。BOOX 的睡眠／關機顯示仍需系統端介面或廣播，不能只靠 KOReader 的內部 screensaver 模組。

## 1. KOReader 怎麼知道目前的書

KOReader 在 `ReaderUI:init()` 以 `self.document.file` 保存目前文件；完成初始化、載入與渲染後，`ReaderUI` 發送 `Event:new("ReaderReady", self.doc_settings)`。外掛以同名的 `onReaderReady(doc_settings)` 收到事件。

- `ReaderUI:init()`、`ReaderReady`：<https://github.com/koreader/koreader/blob/c4f11272c6b6c8d98ff947a75efe23e212b3686e/frontend/apps/reader/readerui.lua#L114-L131>、<https://github.com/koreader/koreader/blob/c4f11272c6b6c8d98ff947a75efe23e212b3686e/frontend/apps/reader/readerui.lua#L503-L521>
- 封面外掛的 `CoverImage:onReaderReady()`：<https://github.com/koreader/koreader/blob/c4f11272c6b6c8d98ff947a75efe23e212b3686e/plugins/coverimage.koplugin/main.lua#L222-L247>

因此 KOReader 沒有掃描 NeoReader、查外部 ContentProvider 或監控檔案來判斷目前書。它在自己的開書生命週期內拿到精確事件。回到檔案管理器或關書時，`ReaderUI:onClose()` 發送 `CloseDocument`，封面外掛可在 `onCloseDocument()` 清除或還原 fallback。

## 2. 封面怎麼產生與寫入

`CoverImage:createCoverImage()` 從 `FileManagerBookInfo:getCoverImage(self.ui.document)` 取得目前書封面，依螢幕尺寸縮放、補背景、依旋轉狀態旋轉，再寫到 `cover_image_path`。這是「固定輸出路徑」策略，不是每次產生新檔名。

- `CoverImage:createCoverImage()`：<https://github.com/koreader/koreader/blob/c4f11272c6b6c8d98ff947a75efe23e212b3686e/plugins/coverimage.koplugin/main.lua#L123-L220>
- `cover_image_path`、格式、品質、灰階等設定：同檔案 <https://github.com/koreader/koreader/blob/c4f11272c6b6c8d98ff947a75efe23e212b3686e/plugins/coverimage.koplugin/main.lua#L75-L101>
- KOReader 官方 Coverimage wiki 的 BOOX 設定：<https://github.com/koreader/koreader/wiki/Coverimage>

官方 wiki 對 Onyx BOOX 的做法是：在 KOReader 開啟 Save book cover，設定輸出檔案，再到 BOOX 的 Storage／Pictures 將該檔案設為 screensaver。這表示 KOReader 主要負責「寫檔」，不是直接控制 BOOX 的休眠畫面。

## 3. KOReader 的 cache 與檔名策略

KOReader 有自己的「避免重算」快取，但快取檔與系統要讀的輸出檔是兩件事：

- 快取目錄預設為 `DataStorage:getDataDir() .. "/cache/cover_image.cache/"`，前綴是 `cover_`。
- `getCacheFile()` 以文件 basename、客製封面 modification time、品質、stretch limit、背景、格式、灰階、旋轉模式與 `_rotated_` 組成 key，再以 MD5 產生快取檔名。
- cache hit 時，KOReader 將快取內容複製回固定的 `cover_image_path`，並更新快取時間；超過數量或容量上限時刪除最舊檔案。

來源：`CoverImage:getCacheFile()` 與 `createCoverImage()`：<https://github.com/koreader/koreader/blob/c4f11272c6b6c8d98ff947a75efe23e212b3686e/plugins/coverimage.koplugin/main.lua#L128-L140>、<https://github.com/koreader/koreader/blob/c4f11272c6b6c8d98ff947a75efe23e212b3686e/plugins/coverimage.koplugin/main.lua#L249-L335>。

重要限制：KOReader 不會為 BOOX 的固定輸出路徑做 `current-a.jpg`／`current-b.jpg` 輪替，也不會主動處理 BOOX 關機圖的路徑快取。這正是本專案保留雙槽檔名與 `type=17` 廣播的理由。

## 4. 睡眠／關機畫面是否由 KOReader 自己控制

KOReader 的通用 `Screensaver:setup()` 會讀 `lastfile`，再由目前的 `ReaderUI`／`bookinfo` 取書封面；這是 KOReader 自己的 UI screensaver。

- `Screensaver:setup()`、`lastfile` 與目前文件封面：<https://github.com/koreader/koreader/blob/c4f11272c6b6c8d98ff947a75efe23e212b3686e/frontend/ui/screensaver.lua#L346-L414>
- `Screensaver:show()` 的畫面 widget：同檔案 <https://github.com/koreader/koreader/blob/c4f11272c6b6c8d98ff947a75efe23e212b3686e/frontend/ui/screensaver.lua#L504-L535>
- Android device capability：`canSuspend = no`：<https://github.com/koreader/koreader/blob/c4f11272c6b6c8d98ff947a75efe23e212b3686e/frontend/device/android/device.lua#L75-L89>

所以在 Android／BOOX 上不能假設 KOReader 會接管電源鍵或系統關機流程。KOReader 官方 wiki 也採用「輸出檔案，再在 BOOX 系統 Screensaver 選取該檔案」的整合方式。

BOOX 官方 Android demo 另外公開了 SDK 層的 `ScreenResourceManager.setScreensaver()` 與 `setShutdown()` 呼叫，見 `ScreensaverActivity.setScreensaver()`／`setShutdown()`：<https://github.com/onyx-intl/OnyxAndroidDemo/blob/3fb2b55646eda97e1f8993bd980f6d9821df379c/app/OnyxAndroidDemo/src/main/java/com/android/onyx/demo/ScreensaverActivity.java#L27-L40>。這是官方 SDK sample，但不代表所有 BOOX 韌體都暴露相同能力給第三方 app；本專案目前的廣播 adapter 仍需以實機行為驗證。

## 5. 可以效法什麼、不能效法什麼

可以效法：

1. 把「目前書已確定、封面已可讀」視為唯一同步時機；本專案對應為 Accessibility／ContentProvider 事件後 debounce，再查目前 EPUB。
2. 封面處理完成後才發布到系統端；不要在偵測到視窗事件的瞬間就送廣播。
3. 以文件識別值與影像參數做去重；KOReader 的 cache key 也包含文件與輸出參數，而不是只看固定輸出檔名。
4. 對系統端失敗採明確 fallback；KOReader 封面外掛本身也有 fallback image 與 `onCloseDocument()` 清理行為。

不能直接效法：

1. 不能在獨立 app 直接使用 KOReader 的 `ReaderReady`／`self.ui.document`，因為那些是 KOReader 內部事件與物件。
2. 不能把 KOReader 的固定輸出檔名當成 BOOX 關機圖的可靠 cache-busting；BOOX 關機圖可能另行處理並快取。
3. 不應把 `FileObserver` 當主要的「目前書」來源。相關 BOOX companion plugin 的技術文件明確記錄：`/sdcard` 的 FUSE／inotify 事件在部分 Android 裝置可能延遲或遺漏；因此主流程採 KOReader 開書完成後的 explicit deep link，FileObserver 只作無法修改 KOReader 時的 MacroDroid fallback。

參考（這是獨立的 BOOX companion 實作，不是 KOReader 官方元件）：

- `BooxShutdownImage:onReaderReady()`、2 秒延遲與 deep link：<https://github.com/lukebatchelor/koreader-boox-shutdown-image/blob/43628f1d012a0eac4192ee3e6cabafd8753a4ad2/booxshutdownimage.koplugin/main.lua#L44-L85>
- FileObserver 取捨：<https://github.com/lukebatchelor/koreader-boox-shutdown-image/blob/43628f1d012a0eac4192ee3e6cabafd8753a4ad2/docs/technical-details.md#why-not-fileobserver-instead-no-explicit-signal-from-the-plugin>

## 對本專案的直接建議

保留目前的 ContentProvider／Accessibility 事件驅動流程，把它視為「模擬 KOReader `ReaderReady` 的外部版本」；不要改成 3 秒輪詢，也不要把 FileObserver 升級成主要偵測器。同步順序應固定為：

```text
NeoReader 事件
→ debounce
→ 查目前 EPUB
→ 讀封面並產生圖片
→ 原子寫入雙槽之一
→ 只送一次對應的 BOOX 廣播
```

若未來能讓使用者從 Cover Sync 開書，才可能取得比外部監控更接近 KOReader 的精確事件；否則 ContentProvider／Accessibility 是本專案可用的邊界，而不是 KOReader 內部 hook。
## 0. 先給答案：KOReader 有沒有直接呼叫 BOOX `onyx.action.SCREENSAVER`

在本次核對的 KOReader 官方 Android device、`coverimage.koplugin`、`ReaderUI` 與官方 Coverimage wiki 中，沒有看到 `onyx.action.SCREENSAVER` 的直接呼叫。KOReader 的 Android 封面流程是「寫檔案」；BOOX 系統 Screensaver 再讀檔案。這也解釋了為什麼 KOReader 本身不能保證 BOOX 的關機圖會隨固定檔案更新。

官方 Onyx Android demo 則示範 SDK 的 `ScreenResourceManager.setScreensaver()`／`setShutdown()`，但那是 BOOX SDK sample，不是 KOReader 的實作；不同 BOOX 韌體是否提供相同 SDK 行為仍需個別驗證。
