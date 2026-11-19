# Moodle 課程小幫手（MoodlePulse Android）

一個專為**大學生與學校 Moodle 系統**設計的外部 Android 伴侶 App 與手機桌面小工具，支援**學校 SSO 跳轉登入**、自動彙整這學期所有作業／測驗期限與課程公告摘要，並自動寫入你的手機／Google 日曆。

---

## 核心功能

1. **支援學校 SSO 跳轉登入（免手動找安全金鑰）**
   - 支援 Moodle 官方行動端 `tool_mobile` SSO 流程（`launch.php`）。
   - 內建 **App 登入視窗（攔截 `moodlepulse://` 與 `moodlemobile://` 回傳）**，並支援切換 **Chrome Custom Tabs 外部瀏覽器跳轉**。
   - 取得後的 `wstoken` 使用 Android Keystore AES-256-GCM 加密儲存於裝置本機。

2. **這學期作業期限彙整＋繳交狀態偵測**
   - 整合 `mod_assign_get_assignments`、`mod_assign_get_submission_status` 與 `core_calendar_get_action_events_by_timesort`（涵蓋作業與線上測驗）。
   - 自動篩選這學期進行中的課程，也能在「課程」分頁手動勾選／取消舊課程。

3. **課程通知摘要引擎**
   - 自動抓取各課程「公告／最新消息」討論區與 Moodle 系統通知。
   - **預設離線智慧摘要**：自動清理 HTML、排除系統罐頭問候語、辨識標籤（`考試`、`調課/停課`、`作業`、`點名/成績`、`重要`）並提取含日期／教室的重點句。
   - **選填 AI 摘要**：可在設定頁填入 Gemini API Key，將長篇公告濃縮為 1～2 句繁體中文重點。

4. **手機桌面小工具（Home Screen Widget）**
   - 可調整大小的滾動清單桌面小工具，顯示作業倒數顏色標示與通知摘要。
   - 上方支援一鍵切換檢視模式（`全部` → `作業` → `通知`）與一鍵手動刷新。

5. **自動寫入日曆 & `.ics` 匯出**
   - 透過 Android `CalendarContract` 自動將作業期限寫入你選擇的日曆帳號（選擇 Google 帳號即自動同步至 Google Calendar），含期限異動自動更新與去重機制，預設提前 1 天與 2 小時提醒。
   - 另附一鍵匯出 `.ics` 行事曆檔。

6. **純單機 GitHub Releases 自我更新**
   - 開啟 App 時自動（或在設定分頁手動）檢查 GitHub Releases 是否有新版本，有新版即直接在 App 內下載 `.apk` 並呼出系統覆蓋安裝。
   - 支援公開 Repo（免 Token）與私人 Repo（設定頁可選填加密儲存的 GitHub PAT）。

---

## 如何編譯成 APK 安裝到手機

### 方法一：免裝電腦工具（使用 GitHub Actions 雲端自動編譯，推薦）
本專案已內建 [`.github/workflows/build-apk.yml`](.github/workflows/build-apk.yml)：
1. 將這個資料夾推上你自己的 GitHub 私有或公開 Repo。
2. 到 GitHub Repo 的 **Actions** 分頁，點選 **Build MoodlePulse Android APK**（每次 push 會自動跑，也可點 **Run workflow** 手動觸發）。
3. 跑完後在下方 **Artifacts** 下載 `MoodlePulse-apk`，傳到手機安裝即可。

---

## 如何發布新版讓手機「App 內自我更新」

1. **設定簽章金鑰（重要：確保手機可直接覆蓋安裝不衝突）**
   Android 規定覆蓋更新時新舊 APK 必須使用同一把簽章金鑰。若用 GitHub Actions 雲端編譯，建議擇一設定 Repo Secrets：
   - **正式 Release 簽章（推薦）**：在 GitHub Repo 的 **Settings → Secrets and variables → Actions** 加入 `ANDROID_KEYSTORE_BASE64`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`。
   - **或固定 Debug 簽章**：把任一次編譯用的 `debug.keystore` 轉成 Base64 存成 `DEBUG_KEYSTORE_BASE64` Secret。
   > ⚠️ 若未設定固定金鑰，每次 GitHub Actions 重新產生的預設 Debug 金鑰會不同，屆時覆蓋更新會被系統阻擋，需先移除舊版再裝。
2. **建立 Release Tag 發布新版**
   - 推送標籤（例如 `git tag v1.0.1 && git push origin v1.0.1`），或在 GitHub Actions 手動執行 workflow 時於 `release_tag` 欄位填入 `v1.0.1`。
   - Actions 會自動編譯對應版本號的 APK 並建立 GitHub Release。
3. **手機端更新**
   - 確認 App 設定分頁「GitHub 自我更新」中的 Repo 名稱正確（例如 `Senerity-234/moodle-pulse`）；若是私人 Repo 請填入有 `Contents: Read` 權限的 Token。
   - 開啟 App 即會自動提示有新版本，點「下載並更新」並允許安裝未知應用程式即可完成覆蓋更新。

### 方法二：本機使用 Android Studio 編譯
1. 安裝 [Android Studio](https://developer.android.com/studio)（內含 JDK 17 與 Android SDK 34）。
2. 用 Android Studio 開啟本資料夾，等待 Gradle 同步完成。
3. 接上手機（開啟 USB 偵錯）點 **Run**，或點選 **Build → Build Bundle(s) / APK(s) → Build APK(s)**。

---

## 首次使用步驟
1. 在 App 輸入學校 Moodle 首頁網址（例如 `https://moodle.ntu.edu.tw`），點 **前往學校 SSO 登入**。
2. 登入成功自動跳回 App 後，切換到 **課程** 分頁確認這學期要追蹤的課程。
3. 到 **設定** 分頁：
   - 點 **一鍵釘選桌面小工具** 加到手機桌面。
   - 點 **授予日曆權限** 並選擇你的 Google 日曆帳號，之後每次背景同步就會自動更新日曆。
