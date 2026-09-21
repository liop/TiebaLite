package com.huanchengfly.tieba.post.ai;

import android.util.Log;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.BenchmarkInfo;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.ThinkingConfig;

/**
 * Java boundary around LiteRT-LM.
 *
 * <p>TiebaLite currently builds with Kotlin 1.9 while LiteRT-LM is published with a newer
 * Kotlin compiler. Keeping this facade in a Java-only Android module isolates that metadata from
 * the application's Kotlin annotation processors.</p>
 */
public final class GemmaLocalInference {
    private static final String TAG = "GemmaLocalInference";
    private static Engine engine;
    private static String loadedModelPath;
    private static String loadedBackend;

    private GemmaLocalInference() {}

    /** Preloads one GPU engine and keeps it alive for subsequent analysis requests. */
    public static synchronized void initialize(String modelPath) {
        if (engine != null && modelPath.equals(loadedModelPath)) {
            return;
        }
        closeEngine();

        try {
            engine = createInitializedEngine(modelPath, new Backend.GPU());
            loadedBackend = "GPU";
        } catch (RuntimeException | Error gpuException) {
            // LiteRT-LM 0.17 reports unsupported GPU buffers without crashing, so retain
            // GPU-first behavior and safely use CPU on incompatible drivers.
            Log.w(TAG, "GPU initialization failed; falling back to CPU", gpuException);
            engine = createInitializedEngine(modelPath, new Backend.CPU());
            loadedBackend = "CPU";
        }
        loadedModelPath = modelPath;
    }

    private static Engine createInitializedEngine(String modelPath, Backend backend) {
        EngineConfig engineConfig = new EngineConfig(
                modelPath,
                backend,
                null,
                null,
                2048,
                null,
                null
        );
        Engine initializedEngine = new Engine(engineConfig);
        try {
            initializedEngine.initialize();
            if (!initializedEngine.isInitialized()) {
                throw new IllegalStateException("LiteRT-LM engine did not finish initialization");
            }
            return initializedEngine;
        } catch (RuntimeException | Error exception) {
            // Engine.close() itself requires successful initialization and would otherwise mask
            // the original accelerator error (for example, a missing vendor OpenCL ICD).
            if (initializedEngine.isInitialized()) {
                initializedEngine.close();
            }
            throw exception;
        }
    }

    public static synchronized boolean isInitialized() {
        return engine != null;
    }

    public static synchronized String getBackendName() {
        return loadedBackend;
    }

    public static synchronized void closeEngine() {
        if (engine != null) {
            engine.close();
            engine = null;
            loadedModelPath = null;
            loadedBackend = null;
        }
    }

    public static synchronized Result generate(
            String modelPath,
            String cacheDir,
            String systemInstruction,
            String prompt
    ) {
        initialize(modelPath);
        ConversationConfig defaults = new ConversationConfig(
                Contents.Companion.of(systemInstruction)
        );
        ConversationConfig conversationConfig = new ConversationConfig(
                defaults.getSystemInstruction(),
                defaults.getInitialMessages(),
                defaults.getTools(),
                defaults.getSamplerConfig(),
                defaults.getAutomaticToolCalling(),
                defaults.getChannels(),
                defaults.getExtraContext(),
                defaults.getLoraConfig(),
                defaults.getPrefillPrefaceOnInit(),
                700,
                new ThinkingConfig(false, 0),
                defaults.getEnableResponseFormat()
        );
        try (Conversation conversation = engine.createConversation(conversationConfig)) {
            Message message = conversation.sendMessage(prompt);
            double timeToFirstTokenSeconds = Double.NaN;
            double prefillTokensPerSecond = Double.NaN;
            double decodeTokensPerSecond = Double.NaN;
            try {
                BenchmarkInfo benchmark = conversation.getBenchmarkInfo();
                timeToFirstTokenSeconds = benchmark.getTimeToFirstTokenInSecond();
                prefillTokensPerSecond = benchmark.getLastPrefillTokensPerSecond();
                decodeTokensPerSecond = benchmark.getLastDecodeTokensPerSecond();
            } catch (RuntimeException benchmarkException) {
                // BenchmarkParams are optional in LiteRT-LM. A completed response must not be
                // discarded merely because this build does not collect performance counters.
                Log.d(TAG, "LiteRT-LM benchmark info is unavailable", benchmarkException);
            }
            return new Result(
                    message.toString(),
                    loadedBackend,
                    timeToFirstTokenSeconds,
                    prefillTokensPerSecond,
                    decodeTokensPerSecond
            );
        }
    }

    public static final class Result {
        private final String text;
        private final String backend;
        private final double timeToFirstTokenSeconds;
        private final double prefillTokensPerSecond;
        private final double decodeTokensPerSecond;

        Result(
                String text,
                String backend,
                double timeToFirstTokenSeconds,
                double prefillTokensPerSecond,
                double decodeTokensPerSecond
        ) {
            this.text = text;
            this.backend = backend;
            this.timeToFirstTokenSeconds = timeToFirstTokenSeconds;
            this.prefillTokensPerSecond = prefillTokensPerSecond;
            this.decodeTokensPerSecond = decodeTokensPerSecond;
        }

        public String getText() { return text; }
        public String getBackend() { return backend; }
        public double getTimeToFirstTokenSeconds() { return timeToFirstTokenSeconds; }
        public double getPrefillTokensPerSecond() { return prefillTokensPerSecond; }
        public double getDecodeTokensPerSecond() { return decodeTokensPerSecond; }
    }
}
