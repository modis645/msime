package app.msime.android;

import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

/** JNI transport for an Android IME host. Session operations use the creating thread.
 * JSON response ownership is handled inside JNI. The host parses the envelope,
 * maps handled/commit/view to InputConnection and UI, and destroys its session.
 */
public final class NativeClient {
    private static final int EMOJI_QUERY_LIMIT = 16_384;
    private static final int EMOJI_RESOURCES_LIMIT = 4_096;
    private static final int EMOJI_RESPONSE_LIMIT = 1_048_576;
    private static final int GLOSS_REQUEST_LIMIT = 262_144;
    private static final int GLOSS_RESOURCES_LIMIT = 4_096;
    private static final int GLOSS_RESPONSE_LIMIT = 1_048_576;
    /** The shared provider FFI rejects an online query document larger than this. */
    private static final int ONLINE_QUERY_LIMIT = 16_384;
    private static final int ENGLISH_COMPLETION_RESPONSE_LIMIT = 262_144;
    private static final int ENGLISH_COMPLETION_RESOURCES_LIMIT = 4 * 1024;
    /** 候选栏的合并展示上限：原文加至多十一个前缀补全。 */
    private static final int ENGLISH_SUGGESTION_DISPLAY_LIMIT = 12;
    private static final int SHUANGPIN_PROFILE_LIMIT = 64;
    private static final int SHUANGPIN_HINT_RESPONSE_LIMIT = 65_536;
    /** 与 `client_jni.cpp` 的 `kSmallJsonRequestLimit` 一致。 */
    private static final int NINE_KEY_SHUANGPIN_REQUEST_LIMIT = 16 * 1024;
    private static final int SMART_PUNCTUATION_REQUEST_LIMIT = 4_096;
    private static final int GLIDE_REQUEST_LIMIT = 65_536;
    /** 与 `msime_client_set_key_geometry` 的上限一致。 */
    private static final int KEY_GEOMETRY_LIMIT = 16_384;
    /** 与 JNI 和 C 入口一致，为 8 MiB 原始词表的 JSON 转义及元数据留出空间。 */
    private static final int VOCABULARY_REQUEST_LIMIT = 17 * 1024 * 1024;
    /** 与 C 入口 {@code msime_client_personal_wordbook_add} 的 64 KiB 上限一致。 */
    private static final int PERSONAL_WORDBOOK_REQUEST_LIMIT = 65536;
    static { System.loadLibrary("msime_android"); }
    private NativeClient() {}
    public static String create(String options) { return TextPolicy.utf8(createRaw(options.getBytes(StandardCharsets.UTF_8))); }
    public static String prepareHost(String options) { return TextPolicy.utf8(prepareHostRaw(options.getBytes(StandardCharsets.UTF_8))); }
    /** Runs the shared `msime_client_refresh_host` on the runtime options file at the absolute `path`. */
    public static String refreshHost(String path) { return TextPolicy.utf8(refreshHostRaw(path.getBytes(StandardCharsets.UTF_8))); }
    /** Reads the current native dictionary version without creating a session. */
    public static String snapshotVersion(String options) {
        return TextPolicy.utf8(snapshotVersionRaw(options.getBytes(StandardCharsets.UTF_8)));
    }
    /** May block on the shared file lock. Call on a worker, without a session handle. */
    public static String loadPreferences(String directory) { return TextPolicy.utf8(loadPreferencesRaw(directory.getBytes(StandardCharsets.UTF_8))); }
    /** 旧布局迁移在共享锁内检查原始字段，不把未知样式误当默认值。 */
    public static String migrateKeyLayout(String request) { return TextPolicy.utf8(migrateKeyLayoutRaw(request.getBytes(StandardCharsets.UTF_8))); }
    /** The global theme picker entries: ids, titles, appearance and built-in palettes, in picker order. */
    public static String themeCatalog() { return TextPolicy.utf8(themeCatalogRaw()); }
    /** 共享候选选中文字色补丁；仅传当前主题和用户本次选择，不传整份偏好。 */
    public static String candidateColorUpdate(String request) {
        if (request == null) throw new IllegalArgumentException("invalid candidate color request");
        byte[] payload = request.getBytes(StandardCharsets.UTF_8);
        if (payload.length == 0 || payload.length > 1024)
            throw new IllegalArgumentException("invalid candidate color request");
        return TextPolicy.utf8(candidateColorUpdateRaw(payload));
    }
    /** 解析当前主题的单种明暗配色；带 `skins_directory` 的请求会校验包资源，必须在工作线程调用。 */
    public static String resolveTheme(String request) { return TextPolicy.utf8(resolveThemeRaw(request.getBytes(StandardCharsets.UTF_8))); }
    public static String skinPackage(String request) { return TextPolicy.utf8(skinPackageRaw(request.getBytes(StandardCharsets.UTF_8))); }
    public static String skinResource(String request) { return TextPolicy.utf8(skinResourceRaw(request.getBytes(StandardCharsets.UTF_8))); }
    /** 应用主题目录：`{app_themes:[{id,title,season,seasonal,light,dark}],default}`。纯计算。 */
    public static String appThemeCatalog() { return TextPolicy.utf8(appThemeCatalogRaw()); }
    /** 应用主题在本地月份与明暗模式下的颜色：`{id,season,accent,accent_soft,on_accent,background,card,hair,candidate_accent}`（`candidate_accent` 是触屏候选条首选的字色，浅色加深到 WCAG AA，深色等于 `accent`）。纯计算，可在主线程调用。 */
    public static String resolveAppTheme(String appTheme, int month, boolean dark) {
        validateMonth(month);
        try {
            JSONObject request = new JSONObject().put("app_theme", appTheme).put("month", month)
                .put("dark", dark);
            return TextPolicy.utf8(resolveAppThemeRaw(request.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (org.json.JSONException error) {
            throw new IllegalArgumentException("Invalid app theme request", error);
        }
    }
    /** 「重置所有设置」：把偏好按修订号比较并交换换回默认值，返回新的快照。读写文件，在工作线程调用。 */
    public static String restoreDefaultPreferences(String directory, long expectedRevision) {
        if (expectedRevision < 0) throw new IllegalArgumentException("Invalid preferences revision");
        return TextPolicy.utf8(restoreDefaultPreferencesRaw(utf8(directory), expectedRevision));
    }
    /** 本版本的默认偏好文档（Android 新装默认值）。纯计算。 */
    public static String defaultPreferences() { return TextPolicy.utf8(defaultPreferencesRaw()); }
    /** 触控板设置范围和默认值。纯计算，不重复定义 Java 侧规则。 */
    public static String textTrackpadOptions() { return TextPolicy.utf8(textTrackpadOptionsRaw()); }
    /** 指定宿主（如 "android"）的能力描述。纯计算。 */
    public static String hostCapabilities(String platform) { return TextPolicy.utf8(hostCapabilitiesRaw(utf8(platform))); }
    /** 词库管理请求 `{options,action}`（列表、搜索、编辑、导入、导出）。会等待词库锁，在工作线程调用。 */
    public static String dictionary(String request) { return TextPolicy.utf8(dictionaryRaw(utf8(request))); }
    /** 个人词库队列请求，键盘活着时也能写词条和导入。在工作线程调用。 */
    public static String personalDictionaryRequest(String request) {
        return TextPolicy.utf8(personalDictionaryRequestRaw(utf8(request)));
    }
    /** 每行一个纯汉字词，答复为按规范拼音生成的词条 `{entries}`。只读内置词库，在工作线程调用。 */
    public static String dictionaryHansEntries(String text, String resources) {
        return TextPolicy.utf8(dictionaryHansEntriesRaw(utf8(text), utf8(resources)));
    }
    /** 导入前预览：词库文件 `{kind,format,text}` 解析成可入队的词条与导入报告。在工作线程调用。 */
    public static String dictionaryImportEntries(String request, String resources) {
        return TextPolicy.utf8(dictionaryImportEntriesRaw(utf8(request), utf8(resources)));
    }
    /** 内置词库的版本信息 `{profile,sourceCommit}`。读文件，在工作线程调用。 */
    public static String dictionaryManifest(String resources) { return TextPolicy.utf8(dictionaryManifestRaw(utf8(resources))); }
    /** 插件包仓库与 @ 名单请求 `{state_root,sound_packs,action}`。读写文件，在工作线程调用。 */
    public static String plugins(String request) { return TextPolicy.utf8(pluginsRaw(utf8(request))); }
    /** 社区资源本地库（保留的回复模板等）请求。持有文件锁，在工作线程调用。 */
    public static String communityResourceLibrary(String request) {
        return TextPolicy.utf8(communityResourceLibraryRaw(utf8(request)));
    }
    /** 皮肤试用的结束与崩溃恢复。写偏好，在工作线程调用。 */
    public static String keyboardSkinTrial(String request) { return TextPolicy.utf8(keyboardSkinTrialRaw(utf8(request))); }
    /** 社区皮肤安装并开始试用。写偏好与两个加锁文件，在工作线程调用。 */
    public static String communitySkinInstall(String request) { return TextPolicy.utf8(communitySkinInstallRaw(utf8(request))); }
    /** 离线世界时钟 `{directory,action}`；加载、修改和快照统一在工作线程调用。 */
    public static String worldClock(String request) { return TextPolicy.utf8(worldClockRaw(utf8(request))); }
    /** 无编码常用语 `{directory,action}`，返回整份文档；设置进程和键盘进程都用。持有文件锁，在工作线程调用。 */
    public static String commonPhrases(String request) { return TextPolicy.utf8(commonPhrasesRaw(utf8(request))); }
    /** 命名词库 `{options,action}`，词条经个人词库队列送进 Engine。读写文件，在工作线程调用。 */
    public static String dictionaryCollections(String request) {
        return TextPolicy.utf8(dictionaryCollectionsRaw(utf8(request)));
    }
    /** 诊断包 `{state_root,include,sources,destination}`：写 zip，或不带 destination 时返回上传用的 sections。输入事件只保留白名单字段，配置快照已脱敏。在工作线程调用。 */
    public static String diagnosticBundle(String request) { return TextPolicy.utf8(diagnosticBundleRaw(utf8(request))); }
    /** 本机设置导出成账号设置文档的键值（可附带合并后的整份文档）；凭据与设备本地设置不导出。读偏好，在工作线程调用。 */
    public static String accountSettingsExport(String request) {
        return TextPolicy.utf8(accountSettingsExportRaw(utf8(request)));
    }
    /** 把云端设置文档应用到本机偏好并按修订号保存，返回保存后的快照、按键反馈、皮肤库与跳过的键。在工作线程调用。 */
    public static String accountSettingsApply(String request) {
        return TextPolicy.utf8(accountSettingsApplyRaw(utf8(request)));
    }

    private static void validateMonth(int month) {
        if (month < 1 || month > 12) throw new IllegalArgumentException("Month must be between 1 and 12");
    }

    /** Classifies committed text and adds batched per-key press counts in native memory, and persists only aggregate counts. Call on a worker. */
    /** 滑行的一笔；`request` 是 `msime_client_glide` 规定的 JSON，由 {@link GlideTypingPolicy#request} 生成。 */
    public static String glide(long session, String request) {
        if (request == null) throw new IllegalArgumentException("Missing glide request");
        byte[] payload = request.getBytes(StandardCharsets.UTF_8);
        if (payload.length > GLIDE_REQUEST_LIMIT)
            throw new IllegalArgumentException("Glide request is too large");
        return TextPolicy.utf8(glideRaw(session, payload));
    }
    public static String smartPunctuationArm(long session, String request) {
        return TextPolicy.utf8(smartPunctuationArmRaw(session, boundedSmartPunctuation(request)));
    }
    public static String smartPunctuationDecide(long session, String request) {
        return TextPolicy.utf8(smartPunctuationDecideRaw(session, boundedSmartPunctuation(request)));
    }
    private static byte[] boundedSmartPunctuation(String request) {
        if (request == null) throw new IllegalArgumentException("Missing smart punctuation request");
        byte[] payload = request.getBytes(StandardCharsets.UTF_8);
        if (payload.length > SMART_PUNCTUATION_REQUEST_LIMIT)
            throw new IllegalArgumentException("Smart punctuation request is too large");
        return payload;
    }
    public static String command(long session, int command) { return TextPolicy.utf8(commandRaw(session, command)); }
    public static String select(long session, long generation, long index) {
        if (index < 0) throw new IllegalArgumentException("Invalid candidate index");
        return TextPolicy.utf8(selectRaw(session, generation, index));
    }
    public static String selectAnyCandidate(long session, long generation, long index) {
        if (index < 0) throw new IllegalArgumentException("Invalid candidate index");
        return TextPolicy.utf8(selectAnyCandidateRaw(session, generation, index));
    }
    public static String pinCandidate(long session, long generation, long index) {
        if (index < 0) throw new IllegalArgumentException("Invalid candidate index");
        return TextPolicy.utf8(pinCandidateRaw(session, generation, index));
    }
    public static String fixCandidatePosition(long session, long generation, long index, int position) {
        if (index < 0) throw new IllegalArgumentException("Invalid candidate index");
        return TextPolicy.utf8(fixCandidatePositionRaw(session, generation, index,
            CandidateManagementAction.validatePosition(position)));
    }
    public static String clearCandidatePosition(long session, long generation, long index) {
        if (index < 0) throw new IllegalArgumentException("Invalid candidate index");
        return TextPolicy.utf8(clearCandidatePositionRaw(session, generation, index));
    }
    public static String removeCandidate(long session, long generation, long index) {
        if (index < 0) throw new IllegalArgumentException("Invalid candidate index");
        return TextPolicy.utf8(removeCandidateRaw(session, generation, index));
    }
    /**
     * 以词定字: ask the Engine for one Han character from a candidate on the current page.
     *
     * <p>Unhandled for a candidate with no Han text, which leaves the composition untouched; the
     * caller falls back to the candidate's own text, as the source's host does.
     */
    public static String selectEdge(long session, long generation, long index, int edge) {
        if (index < 0) throw new IllegalArgumentException("Invalid candidate index");
        if (edge != 0 && edge != 1) throw new IllegalArgumentException("Invalid candidate edge");
        return TextPolicy.utf8(selectEdgeRaw(session, generation, index, edge));
    }
    public static String chooseNineKeySpelling(long session, long generation, long index) {
        if (index < 0) throw new IllegalArgumentException("Invalid nine-key spelling index");
        return TextPolicy.utf8(chooseNineKeySpellingRaw(session, generation, index));
    }
    /**
     * 全拼九键组字时的候选筛选（`msime_client_set_nine_key_filter`）：`singleCharacter` 只留单字，`strokes` 是首字的笔顺前缀（h 横、s 竖、p 撇、n 点、z 折），空串表示不按笔画筛选。返回与其他输入调用相同的响应。
     */
    public static String setNineKeyFilter(long session, boolean singleCharacter, String strokes) {
        if (strokes == null) throw new IllegalArgumentException("Missing nine-key strokes");
        byte[] payload = strokes.getBytes(StandardCharsets.UTF_8);
        if (payload.length > NineKeyPanelPolicy.MAX_STROKES)
            throw new IllegalArgumentException("Nine-key strokes are too long");
        return TextPolicy.utf8(setNineKeyFilterRaw(session, singleCharacter, payload));
    }
    public static String allCandidates(long session) { return TextPolicy.utf8(allCandidatesRaw(session)); }
    /** 座位 {@code start} 起最多 {@code count} 行候选（{@code msime_client_candidate_range}）：横向候选条每键只取它建得出来的一批，不整代复制。 */
    public static String candidateRange(long session, int start, int count) {
        if (start < 0 || count < 0) throw new IllegalArgumentException("Invalid candidate range");
        return TextPolicy.utf8(candidateRangeRaw(session, start, count));
    }
    public static String applyTranslations(long session, long generation, String translations) {
        if (generation < 0) throw new IllegalArgumentException("Invalid candidate generation");
        byte[] payload = translations.getBytes(StandardCharsets.UTF_8);
        if (payload.length > GLOSS_RESPONSE_LIMIT)
            throw new IllegalArgumentException("Candidate translations are too large");
        return TextPolicy.utf8(applyTranslationsRaw(session, generation, payload));
    }
    /** What the optional cloud and AI providers should be asked for, or null when neither applies. */
    public static String onlineQuery(long session) { return TextPolicy.utf8(onlineQueryRaw(session)); }
    /** The cloud candidate URL for a copied online query. Credentials never leave the session. */
    public static String cloudRequestUrl(String query) {
        return TextPolicy.utf8(cloudRequestUrlRaw(boundedQuery(query)));
    }
    /** A validated AI HTTPS descriptor for a copied online query, or null when it no longer applies. */
    public static String aiRequestForQuery(long session, String query) {
        return TextPolicy.utf8(aiRequestForQueryRaw(session, boundedQuery(query)));
    }
    /** 使用当前 AI 凭据构造一次候选翻译请求。 */
    public static String aiTranslationHttpRequest(String config, String text, String source,
            String target) {
        if (config == null || text == null || source == null || target == null)
            throw new IllegalArgumentException("Missing AI translation request");
        JSONObject request;
        try {
            request = new JSONObject(config)
                .put("input", new JSONObject().put("text", text)
                    .put("source_language", source).put("target_language", target));
        } catch (JSONException error) {
            throw new IllegalArgumentException("Invalid AI translation configuration", error);
        }
        return TextPolicy.utf8(aiTranslationHttpRequestRaw(request.toString().getBytes(StandardCharsets.UTF_8)));
    }
    public static String parseAiTranslationResponse(byte[] body) {
        if (body == null || body.length > GLOSS_RESPONSE_LIMIT) throw new IllegalArgumentException("AI response is too large");
        return TextPolicy.utf8(parseAiTranslationResponseRaw(body));
    }
    /** 判断 AI 翻译服务回复是否失败；合法的空译文不算失败。 */
    public static boolean aiTranslationReplyFailed(byte[] body) {
        if (body == null || body.length > GLOSS_RESPONSE_LIMIT) return true;
        return aiTranslationReplyFailedRaw(body);
    }
    public static String applyCloudResponse(long session, String query, String body) {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        if (payload.length > OnlineCandidatePolicy.MAX_CLOUD_RESPONSE_BYTES)
            throw new IllegalArgumentException("Cloud response is too large");
        return TextPolicy.utf8(applyCloudResponseRaw(session, boundedQuery(query), payload));
    }
    /** Source 0 is the cloud provider and source 1 is the AI assistant. */
    public static String applyOnlineCandidates(long session, String query, String candidates,
            int source) {
        if (source != 0 && source != 1)
            throw new IllegalArgumentException("Unknown online candidate source");
        byte[] payload = candidates.getBytes(StandardCharsets.UTF_8);
        if (payload.length > ONLINE_QUERY_LIMIT)
            throw new IllegalArgumentException("Online candidates are too large");
        return TextPolicy.utf8(applyOnlineCandidatesRaw(session, boundedQuery(query), payload, source));
    }
    /** AI 回答为空、失败或被拒时调用（source 只认 1）。共享内核已经不放 AI 占位行，这个调用答 applied:false、不改候选列表；调用和接口留着不变。 */
    public static String resolveOnlineCandidates(long session, String query, int source) {
        if (source != 0 && source != 1)
            throw new IllegalArgumentException("Unknown online candidate source");
        return TextPolicy.utf8(resolveOnlineCandidatesRaw(session, boundedQuery(query), source));
    }
    /** Remove cached and visible rows for source 0 (cloud) or 1 (AI). */
    public static String clearOnlineCandidates(long session, int source) {
        if (source != 0 && source != 1)
            throw new IllegalArgumentException("Unknown online candidate source");
        return TextPolicy.utf8(clearOnlineCandidatesRaw(session, source));
    }
    private static byte[] boundedQuery(String query) {
        byte[] payload = query.getBytes(StandardCharsets.UTF_8);
        if (payload.length > ONLINE_QUERY_LIMIT)
            throw new IllegalArgumentException("Online query is too large");
        return payload;
    }
    public static String view(long session) { return TextPolicy.utf8(viewRaw(session)); }
    public static String updatePreferences(long session, String snapshot) { return TextPolicy.utf8(updatePreferencesRaw(session, snapshot.getBytes(StandardCharsets.UTF_8))); }
    public static String destroy(long session) { return TextPolicy.utf8(destroyRaw(session)); }
    private static native byte[] createRaw(byte[] options);
    private static native byte[] prepareHostRaw(byte[] options);
    private static native byte[] refreshHostRaw(byte[] path);
    private static native byte[] snapshotVersionRaw(byte[] options);
    private static native byte[] migrateKeyLayoutRaw(byte[] request);
    private static native byte[] loadPreferencesRaw(byte[] directory);
    private static native byte[] noticesRaw(byte[] request);
    private static native byte[] noticeDismissRaw(byte[] request);
    private static native byte[] themeCatalogRaw();
    private static native byte[] candidateColorUpdateRaw(byte[] request);
    private static native byte[] resolveThemeRaw(byte[] request);
    private static native byte[] skinPackageRaw(byte[] request);
    private static native byte[] skinResourceRaw(byte[] request);
    private static native byte[] appThemeCatalogRaw();
    private static native byte[] resolveAppThemeRaw(byte[] request);
    private static native byte[] restoreDefaultPreferencesRaw(byte[] directory, long expectedRevision);
    private static native byte[] defaultPreferencesRaw();
    private static native byte[] textTrackpadOptionsRaw();
    private static native byte[] hostCapabilitiesRaw(byte[] platform);
    private static native byte[] dictionaryRaw(byte[] request);
    private static native byte[] personalDictionaryRequestRaw(byte[] request);
    private static native byte[] dictionaryHansEntriesRaw(byte[] text, byte[] resources);
    private static native byte[] dictionaryImportEntriesRaw(byte[] request, byte[] resources);
    private static native byte[] dictionaryManifestRaw(byte[] resources);
    private static native byte[] pluginsRaw(byte[] request);
    private static native byte[] communityResourceLibraryRaw(byte[] request);
    private static native byte[] keyboardSkinTrialRaw(byte[] request);
    private static native byte[] communitySkinInstallRaw(byte[] request);
    private static native byte[] worldClockRaw(byte[] request);
    private static native byte[] commonPhrasesRaw(byte[] request);
    private static native byte[] dictionaryCollectionsRaw(byte[] request);
    private static native byte[] diagnosticBundleRaw(byte[] request);
    private static native byte[] accountSettingsExportRaw(byte[] request);
    private static native byte[] accountSettingsApplyRaw(byte[] request);
    private static native byte[] vocabularyReviewRaw(byte[] request);
    private static native byte[] personalWordbookAddRaw(byte[] request);
    private static native byte[] readAloudTextRaw(byte[] request);
    private static native byte[] emojiCatalogRaw(byte[] query, byte[] resources);
    private static native byte[] candidateGlossesRaw(byte[] request, byte[] resources);
    private static native byte[] candidatePronunciationsRaw(byte[] request, byte[] resources);
    private static native byte[] englishCompletionsRaw(byte[] request, byte[] resources);
    private static native byte[] polishPromptRaw(byte[] id, byte[] custom1, byte[] custom2,
        byte[] custom3);
    private static native byte[] mobileClipboardHistoryRaw(byte[] request);
    private static native byte[] cloudClipboardE2eRaw(byte[] request);
    private static native byte[] cloudClipboardSourceRaw(byte[] directory);
    private static native byte[] cloudClipboardRelayRaw(byte[] request);
    private static native byte[] cloudClipboardRelayExchangeRaw(byte[] request);
    private static native byte[] cloudClipboardWebDavProbeRaw(byte[] request);
    private static native byte[] doubaoDecodeFrameRaw(byte[] frame);
    private static native byte[] doubaoStartFrameRaw(boolean itn, boolean punctuation, boolean ddc,
        byte[] boostingTableId);
    private static native byte[] doubaoAudioFrameRaw(int sequence, byte[] pcm, int length,
        boolean finalChunk);
    private static native byte[] shuangpinKeyHintsRaw(byte[] profile);
    private static native byte[] nineKeyShuangpinLayoutRaw(byte[] request);
    private static native byte[] savePreferencesRaw(byte[] directory, long expectedRevision, byte[] snapshot);
    private static native byte[] personalDictionarySyncRaw(byte[] options);
    private static native byte[] focusRaw(long session, boolean focused);
    private static native byte[] setNineKeyModeRaw(long session, boolean enabled);
    private static native byte[] setKeyGridRaw(long session, int grid);
    private static native byte[] gridKeyRaw(long session, int letter);
    private static native byte[] gridHelpcodeRaw(long session, int op, int letter);
    private static native byte[] setPrivateSessionRaw(long session, boolean enabled);
    private static native byte[] setEnglishModeRaw(long session, boolean enabled);
    private static native byte[] mobileVoiceConfigurationRaw(byte[] directory);
    private static native byte[] mobileVoiceStatusRaw(byte[] provider, boolean resolved,
        boolean platformAvailable, boolean modelReady, boolean runtimeReady);
    private static native byte[] mobileVoicePolishStatusRaw(boolean enabled, boolean configured,
        boolean hasToken);
    private static native byte[] mobileVoicePolicyRaw(byte[] provider, byte[] authMode);
    private static native byte[] voiceProviderCatalogRaw();
    private static native byte[] aiProviderCatalogRaw();
    private static native byte[] aiProviderUpdateRaw(byte[] request);
    private static native byte[] aiModelsRaw(byte[] request);
    private static native byte[] voiceEndpointIssueRaw(byte[] endpoint, boolean websocket);
    private static native byte[] voiceCredentialIssueRaw(byte[] provider, byte[] value);
    private static native byte[] voiceWebSocketEndpointRaw(byte[] endpoint);
    private static native byte[] voiceProviderUpdateRaw(byte[] request);
    private static native byte[] simplifiedToTraditionalRaw(byte[] text);
    private static native byte[] resetCacheRaw(long session);
    private static native byte[] startSettledRerankRaw(long session);
    private static native byte[] pollSettledRerankRaw(long session, long token, boolean accept);
    private static native void releaseProcessCacheRaw();
    private static native byte[] setChinesePunctuationRaw(long session, boolean enabled);
    private static native byte[] setCharacterWidthRaw(long session, boolean fullwidth);
    private static native byte[] characterRaw(long session, int ascii, boolean shift);
    private static native byte[] setKeyGeometryRaw(long session, byte[] json);
    private static native byte[] touchCharacterRaw(long session, int ascii, boolean shift, int geometryId,
        float x, float y);
    private static native byte[] balancePairedPunctuationAfterAutoCloseRaw(long session, int opening);
    private static native byte[] punctuationWithContextRaw(long session, int ascii,
        int precedingCodePoint);
    private static native byte[] glideRaw(long session, byte[] request);
    private static native byte[] smartPunctuationArmRaw(long session, byte[] request);
    private static native byte[] smartPunctuationDecideRaw(long session, byte[] request);
    private static native byte[] commandRaw(long session, int command);
    private static native byte[] selectRaw(long session, long generation, long index);
    private static native byte[] selectAnyCandidateRaw(long session, long generation, long index);
    private static native byte[] pinCandidateRaw(long session, long generation, long index);
    private static native byte[] fixCandidatePositionRaw(long session, long generation, long index,
        int position);
    private static native byte[] clearCandidatePositionRaw(long session, long generation, long index);
    private static native byte[] removeCandidateRaw(long session, long generation, long index);
    private static native byte[] selectEdgeRaw(long session, long generation, long index, int edge);
    private static native byte[] chooseNineKeySpellingRaw(long session, long generation, long index);
    private static native byte[] setNineKeyFilterRaw(long session, boolean singleCharacter,
        byte[] strokes);
    private static native byte[] allCandidatesRaw(long session);
    private static native byte[] candidateRangeRaw(long session, int start, int count);
    private static native byte[] applyTranslationsRaw(long session, long generation,
        byte[] translations);
    private static native byte[] onlineQueryRaw(long session);
    private static native byte[] cloudRequestUrlRaw(byte[] query);
    private static native byte[] aiRequestForQueryRaw(long session, byte[] query);
    private static native byte[] aiTranslationHttpRequestRaw(byte[] request);
    private static native byte[] parseAiTranslationResponseRaw(byte[] body);
    private static native boolean aiTranslationReplyFailedRaw(byte[] body);
    private static native byte[] applyCloudResponseRaw(long session, byte[] query, byte[] body);
    private static native byte[] applyOnlineCandidatesRaw(long session, byte[] query,
        byte[] candidates, int source);
    private static native byte[] clearOnlineCandidatesRaw(long session, int source);
    private static native byte[] resolveOnlineCandidatesRaw(long session, byte[] query, int source);
    private static native byte[] viewRaw(long session);
    private static native byte[] updatePreferencesRaw(long session, byte[] snapshot);
    private static native byte[] voiceHotwordsRaw(byte[] request);
    private static native byte[] voiceHotwordCorrectRaw(byte[] request);
    private static native byte[] voiceNormalizeNumbersRaw(byte[] request);
    private static native byte[] voicePunctuationRaw(byte[] request);
    private static native boolean localSpeechAvailableRaw();
    private static native void localSpeechRuntimeRaw(byte[] path);
    private static native byte[] resourcePacksRaw(byte[] request);
    private static native byte[] resourcePackInstallRaw(byte[] request, ResourcePackProgress listener);
    private static native void resourcePackCancelRaw(byte[] pack);
    private static native byte[] resourcePackRemoveRaw(byte[] request);
    private static native byte[] resourcePackVerifyRaw(byte[] request);
    private static native byte[] resourcePackAdoptRaw(byte[] request);
    private static native byte[] testVoiceCredentialRaw(byte[] request);
    private static native byte[] customTranslationHttpRequestRaw(byte[] request);
    private static native byte[] niutransTranslationHttpRequestRaw(byte[] request);
    private static native byte[] tencentTranslationHttpRequestRaw(byte[] request);
    private static native byte[] parseCustomTranslationResponseRaw(byte[] body);
    private static native byte[] parseNiuTransTranslationResponseRaw(byte[] body);
    private static native byte[] parseTencentTranslationResponseRaw(byte[] body, int expected);
    private static native boolean customTranslationReplyFailedRaw(byte[] body);
    private static native boolean niutransTranslationReplyFailedRaw(byte[] body);
    private static native byte[] translationEndpointIssueRaw(byte[] endpoint);
    private static native byte[] aiEndpointCheckRaw(byte[] endpoint);
    private static native byte[] testApiCredentialRaw(byte[] request);
    private static native byte[] voiceLocalModelsRaw(byte[] request);
    private static native byte[] voiceLocalModelInstallRaw(byte[] request, LocalModelProgress listener);
    private static native byte[] voiceLocalModelCancelRaw(byte[] request);
    private static native byte[] voiceLocalModelRemoveRaw(byte[] request);
    private static native long localSpeechCreateRaw();
    private static native byte[] localSpeechStartRaw(long handle, byte[] model, byte[] language,
                                                     byte[] hotwords, int threads, int vadSensitivity);
    private static native byte[] localSpeechAcceptRaw(long handle, short[] pcm, int count);
    private static native boolean localSpeechSpeakingRaw(long handle);
    private static native byte[] localSpeechFinishRaw(long handle);
    private static native void localSpeechCancelRaw(long handle);
    private static native void localSpeechDestroyRaw(long handle);
    private static native int localSpeechReleaseRaw(long idleMillis);
    private static native byte[] destroyRaw(long session);
    // 账号与云端（#8201）：会话由 Rust 持有，经 SessionStore 注入的存储读写；登录、令牌和刷新经 AccountStore 调用，其余入口经上面各自的包装调用（sessionStoreUnregisterRaw 只有设备冒烟用）；aiSkinJobRaw、cloudDictionaryRaw 是 5a 按头文件接好的入口，产品还没有调用方，只有设备冒烟核对请求不合法码，是否删由负责人定。
    private static native byte[] sessionStoreRegisterRaw(SessionStore store, byte[] clientAgent);
    private static native byte[] sessionStoreUnregisterRaw(long sessionStore);
    private static native byte[] clientAgentRaw(byte[] request);
    private static native byte[] accountNameRaw(byte[] request);
    private static native byte[] accountRaw(byte[] request);
    private static native byte[] accountAccessTokenRaw(byte[] request);
    private static native byte[] accountCloudClipboardRaw(byte[] request);
    private static native byte[] accountChatStreamRaw(byte[] request, ChatDelta listener);
    private static native byte[] accountChatCancelRaw(byte[] request);
    private static native byte[] accountGlossRaw(byte[] request);
    private static native byte[] communityRaw(byte[] request);
    private static native byte[] aiSkinGenerateRaw(byte[] request, AiSkinProgress listener);
    private static native byte[] aiSkinCancelRaw(byte[] request);
    private static native byte[] aiSkinJobRaw(byte[] request);
    private static native byte[] cloudDictionaryRaw(byte[] request);
    private static native byte[] snapshotQueueRaw(byte[] request);
    private static native byte[] cloudSyncRaw(byte[] request);
}
