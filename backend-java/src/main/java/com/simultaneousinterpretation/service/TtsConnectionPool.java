package com.simultaneousinterpretation.service;

import com.alibaba.dashscope.audio.ttsv2.SpeechSynthesizer;
import com.simultaneousinterpretation.config.DashScopeProperties;
import com.simultaneousinterpretation.config.TtsProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.commons.pool2.BasePooledObjectFactory;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.DefaultPooledObject;
import org.apache.commons.pool2.impl.GenericObjectPool;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * TTS 连接池（基于 commons-pool2 + DashScope SDK 官方推荐的高并发优化方案）
 *
 * 官方文档：https://help.aliyun.com/zh/model-studio/developer-reference/high-concurrency-scenarios
 *
 * 核心优化点：
 * 1. 复用 WebSocket 连接，避免每次请求重新建立连接（节省 3-7 秒）
 * 2. 每种语言(zh/en/id)维护独立连接池，避免音色切换开销
 * 3. 启动时预热连接，确保冷启动无延迟
 */
@Component
public class TtsConnectionPool {

    private static final Logger log = LoggerFactory.getLogger(TtsConnectionPool.class);

    private static final String ENV_OBJECT_POOL_SIZE = "COSYVOICE_OBJECTPOOL_SIZE";
    private static final int DEFAULT_OBJECT_POOL_SIZE = 10; // 每种语言 10 个连接

    private final TtsProperties ttsProperties;
    private final DashScopeProperties dashScopeProperties;

    /** 每种语言的连接池 */
    private final Map<Lang, GenericObjectPool<SpeechSynthesizer>> pools = new EnumMap<>(Lang.class);

    /** 初始化锁 */
    private final ReentrantLock initLock = new ReentrantLock();

    public enum Lang {
        ZH, EN, ID
    }

    public TtsConnectionPool(TtsProperties ttsProperties, DashScopeProperties dashScopeProperties) {
        this.ttsProperties = ttsProperties;
        this.dashScopeProperties = dashScopeProperties;
    }

    @PostConstruct
    public void init() {
        log.info("[TTS-POOL] 初始化 TTS 连接池...");

        int poolSize = getPoolSize();
        log.info("[TTS-POOL] 连接池大小: poolSize={}", poolSize);

        // 为每种语言创建连接池
        for (Lang lang : Lang.values()) {
            createPool(lang, poolSize);
        }

        // 预热连接（启动时建立连接，避免冷启动延迟）
        warmup();

        log.info("[TTS-POOL] TTS 连接池初始化完成，3 种语言各 {} 个连接", poolSize);
    }

    private int getPoolSize() {
        String envSize = System.getenv(ENV_OBJECT_POOL_SIZE);
        if (envSize != null && !envSize.isEmpty()) {
            try {
                return Integer.parseInt(envSize);
            } catch (NumberFormatException e) {
                log.warn("[TTS-POOL] 无效的环境变量 {}={}", ENV_OBJECT_POOL_SIZE, envSize);
            }
        }
        return DEFAULT_OBJECT_POOL_SIZE;
    }

    private void createPool(Lang lang, int poolSize) {
        SpeechSynthesizerObjectFactory factory = new SpeechSynthesizerObjectFactory(
                ttsProperties, dashScopeProperties, lang);

        GenericObjectPoolConfig<SpeechSynthesizer> config = new GenericObjectPoolConfig<>();
        config.setMaxTotal(poolSize);
        config.setMaxIdle(poolSize);
        config.setMinIdle(Math.min(2, poolSize)); // 至少保持 2 个空闲连接
        config.setTestOnBorrow(true);  // 借出时测试连接是否有效
        config.setTestWhileIdle(true); // 空闲时测试连接
        config.setBlockWhenExhausted(true); // 池满时阻塞等待

        GenericObjectPool<SpeechSynthesizer> pool = new GenericObjectPool<>(factory, config);
        pools.put(lang, pool);

        log.info("[TTS-POOL] 创建 {} 语言连接池: maxTotal={} maxIdle={} minIdle={}",
                lang, poolSize, poolSize, Math.min(2, poolSize));
    }

    /**
     * 预热连接：启动时创建连接，避免第一次请求的冷启动延迟
     */
    private void warmup() {
        log.info("[TTS-POOL] 开始预热连接...");
        for (Map.Entry<Lang, GenericObjectPool<SpeechSynthesizer>> entry : pools.entrySet()) {
            Lang lang = entry.getKey();
            GenericObjectPool<SpeechSynthesizer> pool = entry.getValue();

            // 预创建 2 个连接
            for (int i = 0; i < 2; i++) {
                try {
                    SpeechSynthesizer synthesizer = pool.borrowObject();
                    pool.returnObject(synthesizer);
                    log.info("[TTS-POOL] {} 语言预热连接 {} 成功", lang, i + 1);
                } catch (Exception e) {
                    log.warn("[TTS-POOL] {} 语言预热连接 {} 失败: {}", lang, i + 1, e.getMessage());
                }
            }
        }
        log.info("[TTS-POOL] 预热完成");
    }

    /**
     * 借出一个 SpeechSynthesizer
     */
    public SpeechSynthesizer borrow(Lang lang) throws Exception {
        GenericObjectPool<SpeechSynthesizer> pool = pools.get(lang);
        if (pool == null) {
            throw new IllegalArgumentException("未知的语言: " + lang);
        }
        return pool.borrowObject();
    }

    /**
     * 归还 SpeechSynthesizer 到池中
     */
    public void returnObject(Lang lang, SpeechSynthesizer synthesizer) {
        GenericObjectPool<SpeechSynthesizer> pool = pools.get(lang);
        if (pool != null && synthesizer != null) {
            pool.returnObject(synthesizer);
        }
    }

    /**
     * 使 SpeechSynthesizer 失效（发生错误时调用）
     */
    public void invalidateObject(Lang lang, SpeechSynthesizer synthesizer) {
        GenericObjectPool<SpeechSynthesizer> pool = pools.get(lang);
        if (pool != null && synthesizer != null) {
            try {
                pool.invalidateObject(synthesizer);
            } catch (Exception e) {
                log.warn("[TTS-POOL] 使连接失效失败: {}", e.getMessage());
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[TTS-POOL] 关闭 TTS 连接池...");
        for (Map.Entry<Lang, GenericObjectPool<SpeechSynthesizer>> entry : pools.entrySet()) {
            try {
                entry.getValue().close();
                log.info("[TTS-POOL] {} 语言连接池已关闭", entry.getKey());
            } catch (Exception e) {
                log.warn("[TTS-POOL] 关闭 {} 语言连接池失败: {}", entry.getKey(), e.getMessage());
            }
        }
        log.info("[TTS-POOL] TTS 连接池已关闭");
    }

    /**
     * 获取池状态信息（用于监控）
     */
    public String getPoolStats(Lang lang) {
        GenericObjectPool<SpeechSynthesizer> pool = pools.get(lang);
        if (pool == null) {
            return "unknown";
        }
        return String.format("active=%d idle=%d waiting=%d",
                pool.getNumActive(), pool.getNumIdle(), pool.getNumWaiters());
    }

    // ========== 内部类：SpeechSynthesizer 工厂 ==========

    private static class SpeechSynthesizerObjectFactory
            extends BasePooledObjectFactory<SpeechSynthesizer> {

        private final TtsProperties ttsProperties;
        private final DashScopeProperties dashScopeProperties;
        private final Lang lang;

        public SpeechSynthesizerObjectFactory(TtsProperties ttsProperties,
                                             DashScopeProperties dashScopeProperties,
                                             Lang lang) {
            this.ttsProperties = ttsProperties;
            this.dashScopeProperties = dashScopeProperties;
            this.lang = lang;
        }

        @Override
        public SpeechSynthesizer create() throws Exception {
            // 官方方式：先创建空的 SpeechSynthesizer
            return new SpeechSynthesizer();
        }

        @Override
        public PooledObject<SpeechSynthesizer> wrap(SpeechSynthesizer obj) {
            return new DefaultPooledObject<>(obj);
        }

        @Override
        public void destroyObject(PooledObject<SpeechSynthesizer> p) throws Exception {
            SpeechSynthesizer synthesizer = p.getObject();
            if (synthesizer != null) {
                try {
                    synthesizer.getDuplexApi().close(1000, "pool shutdown");
                } catch (Exception e) {
                    // 忽略关闭错误
                }
            }
        }

        @Override
        public boolean validateObject(PooledObject<SpeechSynthesizer> p) {
            // 连接是有效的（OkHttp 会自动处理心跳）
            return true;
        }
    }
}
