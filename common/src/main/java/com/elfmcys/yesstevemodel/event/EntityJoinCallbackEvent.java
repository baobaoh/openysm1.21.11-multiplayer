package com.elfmcys.yesstevemodel.event;

import com.elfmcys.yesstevemodel.YesSteveModel;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.event.events.common.EntityEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.Entity;
import rip.ysm.api.PlatformAPI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 把模型包推迟到对应实体真正出现在客户端世界里再应用，并且记住「这个玩家穿的什么」。
 * <p>
 * 需要兜底的原因有三类：
 * <ul>
 *   <li>复活/克隆实体后，客户端手里的实体 id 可能和服务端不一致 → 按玩家 UUID 兜底匹配；</li>
 *   <li>实体离开视野再回来时是全新的实体对象，能力(capability)丢了 → 每次实体加入都按 UUID 重放一次；</li>
 *   <li>包先到、实体后到（相隔很远、传送、跨维度）→ 先缓存，实体出现时再应用。</li>
 * </ul>
 * 所以按 UUID 的记录**不会过期**，只在退出服务器时清空。
 */
public class EntityJoinCallbackEvent {

    /**
     * 按实体 id 的缓存只是一次性的「包比实体先到」，给个短 TTL 兜底即可。
     */
    private static final long PENDING_TTL_SECONDS = 60L;

    private static final Cache<Integer, List<Pending>> pendingById = CacheBuilder.newBuilder()
            .expireAfterAccess(PENDING_TTL_SECONDS, TimeUnit.SECONDS).build();

    /**
     * 玩家 UUID → 当前模型。跨视野进出、跨维度都靠它重放，因此不过期（退出服务器时清空）。
     */
    private static final Cache<UUID, List<Pending>> modelsByUuid = CacheBuilder.newBuilder()
            .maximumSize(4096).build();

    /**
     * 已经应用过模型的实体对象，避免每 tick 重复应用。实体被移除后自动消失。
     */
    private static final Set<Entity> applied = Collections.newSetFromMap(new WeakHashMap<>());

    private static ClientLevel cachedLevel;

    private EntityJoinCallbackEvent() {
    }

    private record Pending(Consumer<Entity> consumer) {
    }

    public static void register() {
        if (PlatformAPI.isServer()) {
            return;
        }
        EntityEvent.ADD.register((entity, level) -> {
            if (!YesSteveModel.isAvailable() || !level.isClientSide()) {
                return EventResult.pass();
            }
            if (pendingById.getIfPresent(entity.getId()) != null
                    || modelsByUuid.getIfPresent(entity.getUUID()) != null) {
                YesSteveModel.LOGGER.info("[YSM-JOIN] entity={} uuid={} class={}",
                        entity.getId(), entity.getUUID(), entity.getClass().getSimpleName());
            }
            drainById(entity);
            applyKnownModel(entity, true);
            return EventResult.pass();
        });
        ClientTickEvent.CLIENT_POST.register(client -> {
            resetIfLevelChanged(client);
            retryPending();
        });
    }

    public static void addCallback(int entityId, Consumer<Entity> consumer) {
        addCallback(entityId, (UUID) null, consumer);
    }

    public static void addCallback(int entityId, UUID uuid, Consumer<Entity> consumer) {
        Minecraft.getInstance().execute(() -> {
            ClientLevel clientLevel = Minecraft.getInstance().level;
            if (clientLevel == null) {
                YesSteveModel.LOGGER.info("[YSM-QUEUE] entity={} DROPPED (level==null)", entityId);
                return;
            }
            Pending pending = new Pending(consumer);
            if (uuid != null) {
                // 同一个玩家只保留最新的模型，切换模型时直接覆盖，避免列表越堆越长。
                modelsByUuid.put(uuid, new ArrayList<>(List.of(pending)));
            }
            Entity entity = findEntity(clientLevel, entityId, uuid);
            if (entity != null) {
                applied.add(entity);
                YesSteveModel.LOGGER.info("[YSM-QUEUE] entity={} requestedId={} resolvedId={} viaUuid={}",
                        entityId, entityId, entity.getId(), entity.getId() != entityId);
                consumer.accept(entity);
            } else {
                YesSteveModel.LOGGER.info("[YSM-QUEUE] entity={} queued (uuid={})", entityId, uuid);
                addToList(pendingById, entityId, pending);
            }
        });
    }

    /**
     * 每 tick 兜底：
     * <ul>
     *   <li>按 id 缓存里实体已经出现的，直接应用；</li>
     *   <li>按 UUID 记住的模型，如果实体已经在世界里但还没应用过（加入事件被别的模组挡了、或者先有存档再有事件），补一次。</li>
     * </ul>
     */
    public static void retryPending() {
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null) {
            return;
        }
        if (pendingById.size() > 0) {
            for (Integer id : new ArrayList<>(pendingById.asMap().keySet())) {
                Entity entity = clientLevel.getEntity(id);
                if (entity == null) {
                    continue;
                }
                YesSteveModel.LOGGER.info("[YSM-RETRY] entity={} resolved by id", id);
                drainById(entity);
                applyKnownModel(entity, false);
            }
        }
        if (modelsByUuid.size() == 0) {
            return;
        }
        for (UUID uuid : new ArrayList<>(modelsByUuid.asMap().keySet())) {
            Entity entity = findPlayerByUuid(clientLevel, uuid);
            if (entity == null || applied.contains(entity)) {
                continue;
            }
            YesSteveModel.LOGGER.info("[YSM-RETRY] uuid={} resolved id={}", uuid, entity.getId());
            applyKnownModel(entity, false);
        }
    }

    /**
     * 换世界/退出时清掉一次性的东西；只有真的离开服务器（level == null）才丢掉记住的模型。
     */
    private static void resetIfLevelChanged(Minecraft client) {
        if (client.level == cachedLevel) {
            return;
        }
        cachedLevel = client.level;
        pendingById.invalidateAll();
        applied.clear();
        if (client.level == null) {
            modelsByUuid.invalidateAll();
        }
    }

    /**
     * 实体进入客户端世界时，把它上次穿的模型重新贴上去。
     * 实体对象是新建的，能力已经从零开始，所以必须重放。
     *
     * @param force true 表示「刚进世界，无条件重放」；false 表示每 tick 兜底，已经贴过就跳过
     */
    private static void applyKnownModel(Entity entity, boolean force) {
        List<Pending> list = modelsByUuid.getIfPresent(entity.getUUID());
        if (list == null || list.isEmpty()) {
            if (force && entity instanceof AbstractClientPlayer) {
                YesSteveModel.LOGGER.info("[YSM-JOIN] entity={} uuid={} no known model",
                        entity.getId(), entity.getUUID());
            }
            return;
        }
        boolean first = applied.add(entity);
        if (!force && !first) {
            return;
        }
        YesSteveModel.LOGGER.info("[YSM-JOIN] entity={} uuid={} replay model (first={})",
                entity.getId(), entity.getUUID(), first);
        for (Pending pending : list) {
            pending.consumer().accept(entity);
        }
    }

    private static Entity findEntity(ClientLevel clientLevel, int entityId, UUID uuid) {
        Entity entity = clientLevel.getEntity(entityId);
        if (entity != null) {
            return entity;
        }
        return uuid == null ? null : findPlayerByUuid(clientLevel, uuid);
    }

    private static Entity findPlayerByUuid(ClientLevel clientLevel, UUID uuid) {
        for (AbstractClientPlayer player : clientLevel.players()) {
            if (player.getUUID().equals(uuid)) {
                return player;
            }
        }
        return null;
    }

    private static void drainById(Entity entity) {
        List<Pending> byId = pendingById.asMap().remove(entity.getId());
        if (byId == null) {
            return;
        }
        Set<Consumer<Entity>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Pending pending : byId) {
            if (seen.add(pending.consumer())) {
                applied.add(entity);
                pending.consumer().accept(entity);
            }
        }
    }

    private static <K> void addToList(Cache<K, List<Pending>> cache, K key, Pending pending) {
        List<Pending> list = cache.getIfPresent(key);
        if (list == null) {
            list = new ArrayList<>(3);
            cache.put(key, list);
        }
        list.add(pending);
    }
}
