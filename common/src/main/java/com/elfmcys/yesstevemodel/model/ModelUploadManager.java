package com.elfmcys.yesstevemodel.model;

import com.elfmcys.yesstevemodel.YesSteveModel;
import com.elfmcys.yesstevemodel.config.ServerConfig;
import com.elfmcys.yesstevemodel.util.DigestUtil;
import com.elfmcys.yesstevemodel.util.YSMMessageFormatter;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 服务端收模型的会话管理：客户端把 .ysm 分片传上来，落到 config/yes_steve_model/custom/ 里。
 * 客户端上传界面（ModelUploadScreen）早就写好了，但三个 C2S 包原本是空实现，等于传了没人接。
 */
public final class ModelUploadManager {

    // 起始应答状态
    public static final byte OK = 0;
    public static final byte ERR_EXISTS = 1;
    public static final byte ERR_TOO_LARGE = 2;
    public static final byte ERR_NO_PERMISSION = 3;
    public static final byte ERR_BUSY = 4;
    public static final byte ERR_INVALID = 5;
    public static final byte ERR_DISABLED = 6;

    // 完成应答状态
    public static final byte RES_OK = 0;
    public static final byte RES_HASH_MISMATCH = 1;
    public static final byte RES_PARSE_FAILED = 2;
    public static final byte RES_STORAGE_ERROR = 3;
    public static final byte RES_SESSION_EXPIRED = 4;
    public static final byte RES_INCOMPLETE = 5;
    public static final byte RES_WRITE_REJECTED = 6;

    private static final int CHUNK_SIZE = 32000;
    private static final int CHUNKS_PER_TICK = 4;
    private static final long SESSION_TTL_MS = 5 * 60 * 1000L;
    private static final int MAX_ID_LENGTH = 96;

    private static final AtomicLong ID_SEQ = new AtomicLong(1);

    private static final Map<Long, Session> SESSIONS = new ConcurrentHashMap<>();

    private ModelUploadManager() {
    }

    private static final class Session {
        final long id;
        final UUID owner;
        final String fileName;
        final int totalBytes;
        final String sha256;
        final byte[] data;
        int filled;
        long expiresAt;

        Session(long id, UUID owner, String fileName, int totalBytes, String sha256) {
            this.id = id;
            this.owner = owner;
            this.fileName = fileName;
            this.totalBytes = totalBytes;
            this.sha256 = sha256;
            this.data = new byte[totalBytes];
            this.expiresAt = System.currentTimeMillis() + SESSION_TTL_MS;
        }
    }

    public record StartResult(long uploadId, byte status, String message) {
    }

    public record FinishResult(byte status, String modelId, String message) {
    }

    public static int chunkSize() {
        return CHUNK_SIZE;
    }

    public static int chunksPerTick() {
        return CHUNKS_PER_TICK;
    }

    public static int maxTotalBytes() {
        return Math.max(1, ServerConfig.MAX_UPLOAD_BYTES.get()) * 1024 * 1024;
    }

    /** 客户端点的「开始上传」：校验权限、大小、重名，然后回一个 uploadId。 */
    public static StartResult start(ServerPlayer player, String rawModelId, int totalBytes, String sha256) {
        if (!ServerConfig.ALLOW_UPLOAD.get()) {
            return new StartResult(0L, ERR_DISABLED, "Uploads are disabled on this server");
        }
        if (!YSMMessageFormatter.hasCommandPermission(player.createCommandSourceStack(), ServerConfig.UPLOAD_PERMISSION_LEVEL.get())) {
            return new StartResult(0L, ERR_NO_PERMISSION, "You are not allowed to upload models");
        }
        String fileName = sanitize(rawModelId);
        if (fileName == null) {
            return new StartResult(0L, ERR_INVALID, "Invalid model file name");
        }
        if (totalBytes <= 0 || totalBytes > maxTotalBytes()) {
            return new StartResult(0L, ERR_TOO_LARGE, "File exceeds server limit (" + maxTotalBytes() + " bytes)");
        }
        if (sha256 == null || sha256.length() != 64) {
            return new StartResult(0L, ERR_INVALID, "Missing sha256");
        }
        if (ServerModelManager.getServerModelInfo().containsKey(fileName)) {
            return new StartResult(0L, ERR_EXISTS, "A model with this id already exists");
        }
        // 同一个玩家同时只允许一个会话
        SESSIONS.values().removeIf(s -> s.owner.equals(player.getUUID()) || s.expiresAt < System.currentTimeMillis());
        Session session = new Session(ID_SEQ.getAndIncrement(), player.getUUID(), fileName, totalBytes, sha256.toLowerCase(Locale.ROOT));
        SESSIONS.put(session.id, session);
        YesSteveModel.LOGGER.info("[YSM-UPLOAD] start player={} file={} bytes={} id={}",
                player.getGameProfile().name(), fileName, totalBytes, session.id);
        return new StartResult(session.id, OK, "");
    }

    /** 一片数据。必须按顺序到达，乱序直接丢弃（客户端本来就是顺序发的）。 */
    public static void chunk(ServerPlayer player, long uploadId, int offset, byte[] data) {
        Session session = SESSIONS.get(uploadId);
        if (session == null || !session.owner.equals(player.getUUID())) {
            return;
        }
        if (session.expiresAt < System.currentTimeMillis()) {
            SESSIONS.remove(uploadId);
            return;
        }
        if (offset != session.filled || data == null || data.length == 0) {
            return;
        }
        int len = Math.min(data.length, session.totalBytes - session.filled);
        if (len <= 0) {
            return;
        }
        System.arraycopy(data, 0, session.data, session.filled, len);
        session.filled += len;
        session.expiresAt = System.currentTimeMillis() + SESSION_TTL_MS;
    }

    /** 收完：校验 sha256 与 YSGP 头，写进 custom/，然后重载模型表。 */
    public static FinishResult finish(ServerPlayer player, long uploadId) {
        Session session = SESSIONS.remove(uploadId);
        if (session == null || !session.owner.equals(player.getUUID())) {
            return new FinishResult(RES_SESSION_EXPIRED, "", "Upload session expired");
        }
        if (session.filled < session.totalBytes) {
            return new FinishResult(RES_INCOMPLETE, session.fileName,
                    "Incomplete: " + session.filled + "/" + session.totalBytes);
        }
        String actual = DigestUtil.sha256Hex(session.data);
        if (actual == null || !actual.equalsIgnoreCase(session.sha256)) {
            return new FinishResult(RES_HASH_MISMATCH, session.fileName, "sha256 mismatch");
        }
        if (!hasYsmHeader(session.data)) {
            return new FinishResult(RES_PARSE_FAILED, session.fileName, "Not a YSM model file");
        }
        Path target = ServerModelManager.CUSTOM.resolve(session.fileName).normalize();
        if (!target.startsWith(ServerModelManager.CUSTOM.normalize())) {
            return new FinishResult(RES_WRITE_REJECTED, session.fileName, "Refusing to write outside custom/");
        }
        try {
            Files.createDirectories(ServerModelManager.CUSTOM);
            Path tmp = target.resolveSibling(target.getFileName() + ".uploading");
            Files.write(tmp, session.data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            YesSteveModel.LOGGER.error("[YSM-UPLOAD] failed to write {}", target, e);
            return new FinishResult(RES_STORAGE_ERROR, session.fileName, e.getMessage());
        }
        YesSteveModel.LOGGER.info("[YSM-UPLOAD] stored {} ({} bytes) from {}", target, session.data.length,
                player.getGameProfile().name());
        try {
            ServerModelManager.loadModels(result -> {
                if (result.getErrorMessage() != null) {
                    YesSteveModel.LOGGER.warn("[YSM-UPLOAD] reload after upload failed: {}", result.getErrorMessage());
                } else {
                    YesSteveModel.LOGGER.info("[YSM-UPLOAD] reload done, models={}",
                            ServerModelManager.getServerModelInfo().size());
                }
            }, null);
        } catch (Throwable t) {
            YesSteveModel.LOGGER.warn("[YSM-UPLOAD] reload threw", t);
        }
        return new FinishResult(RES_OK, session.fileName, "");
    }

    /** 只保留文件名部分，禁止路径穿越和奇怪字符。必须带 .ysm 后缀。 */
    private static String sanitize(String rawId) {
        if (rawId == null) {
            return null;
        }
        String name = rawId.trim();
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) {
            name = name.substring(cut + 1);
        }
        if (name.isEmpty() || name.length() > MAX_ID_LENGTH) {
            return null;
        }
        if (name.contains("..") || name.contains(":") || name.contains("\u0000")) {
            return null;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || "\\/:*?\"<>|".indexOf(c) >= 0) {
                return null;
            }
        }
        if (!name.toLowerCase(Locale.ROOT).endsWith(".ysm")) {
            name = name + ".ysm";
        }
        return name;
    }

    private static boolean hasYsmHeader(byte[] data) {
        byte[] header = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'Y', 'S', 'G', 'P'};
        if (data.length < header.length) {
            return false;
        }
        for (int i = 0; i < header.length; i++) {
            if (data[i] != header[i]) {
                return false;
            }
        }
        return true;
    }
}
