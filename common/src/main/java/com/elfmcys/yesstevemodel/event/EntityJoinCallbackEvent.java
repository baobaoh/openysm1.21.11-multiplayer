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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 把模型包推迟到对应实体真正出现在客户端世界里再应用。
 * <p>
 * 复活（以及任何重建实体的情况）之后，客户端手里的实体 id 可能和服务端不一致，
 * 所以除了 entityId 之外还按玩家 UUID 兜底匹配，并且每 tick 重试一次，
 * 避免只依赖实体加入事件而漏包。
 */
public class EntityJoinCallbackEvent {

    private static final long TTL_SECONDS = 30L;

    private static final Cache<Integer, List<Pending>> pendingById = CacheBuilder.newBuilder()
            .expireAfterAccess(TTL_SECONDS, TimeUnit.SECONDS).build();

    private static final Cache<UUID, List<Pending>> pendingByUuid = CacheBuilder.newBuilder()
            .expireAfterAccess(TTL_SECONDS, TimeUnit.SECONDS).build();

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
            List<Pending> byId = pendingById.getIfPresent(entity.getId());
            if (byId != null) {
                YesSteveModel.LOGGER.info("[YSM-JOIN] entity={} uuid={} class={}",
                        entity.getId(), entity.getUUID(), entity.getClass().getSimpleName());
            }
            drainFor(entity);
            return EventResult.pass();
        });
        ClientTickEvent.CLIENT_POST.register(client -> retryPending());
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
            Entity entity = findEntity(clientLevel, entityId, uuid);
            if (entity != null) {
                YesSteveModel.LOGGER.info("[YSM-QUEUE] entity={} requestedId={} resolvedId={} viaUuid={}",
                        entityId, entityId, entity.getId(), entity.getId() != entityId);
                consumer.accept(entity);
            } else {
                YesSteveModel.LOGGER.info("[YSM-QUEUE] entity={} queued (uuid={})", entityId, uuid);
                Pending pending = new Pending(consumer);
                addToList(pendingById, entityId, pending);
                if (uuid != null) {
                    addToList(pendingByUuid, uuid, pending);
                }
            }
        });
    }

    /**
     * 每 tick 兜底：实体也许已经存在，但加入事件没赶上或者被别的模组挡掉了。
     */
    public static void retryPending() {
        if (pendingById.size() == 0 && pendingByUuid.size() == 0) {
            return;
        }
        ClientLevel clientLevel = Minecraft.getInstance().level;
        if (clientLevel == null) {
            return;
        }
        for (Integer id : new ArrayList<>(pendingById.asMap().keySet())) {
            Entity entity = clientLevel.getEntity(id);
            if (entity == null) {
                continue;
            }
            YesSteveModel.LOGGER.info("[YSM-RETRY] entity={} resolved by id", id);
            drainFor(entity);
        }
        for (UUID uuid : new ArrayList<>(pendingByUuid.asMap().keySet())) {
            Entity entity = findPlayerByUuid(clientLevel, uuid);
            if (entity == null) {
                continue;
            }
            YesSteveModel.LOGGER.info("[YSM-RETRY] uuid={} resolved id={}", uuid, entity.getId());
            drainFor(entity);
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

    private static void drainFor(Entity entity) {
        List<Pending> byId = pendingById.asMap().remove(entity.getId());
        List<Pending> byUuid = pendingByUuid.asMap().remove(entity.getUUID());
        if (byId == null && byUuid == null) {
            return;
        }
        Set<Consumer<Entity>> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (byId != null) {
            for (Pending pending : byId) {
                if (seen.add(pending.consumer())) {
                    pending.consumer().accept(entity);
                }
            }
        }
        if (byUuid != null) {
            for (Pending pending : byUuid) {
                if (seen.add(pending.consumer())) {
                    pending.consumer().accept(entity);
                }
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
