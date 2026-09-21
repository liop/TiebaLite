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
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.ThinkingConfig;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

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
    private static int loadedMaxNumTokens;
    private static volatile Conversation activeConversation;

    private GemmaLocalInference() {}

    /** Preloads one GPU engine and keeps it alive for subsequent analysis requests. */
    public static synchronized void initialize(String modelPath) {
        initialize(modelPath, 4096, null);
    }

    public static synchronized void initialize(String modelPath, int maxNumTokens, String cacheDir) {
        if (engine != null && modelPath.equals(loadedModelPath) && loadedMaxNumTokens == maxNumTokens) {
            return;
        }
        closeEngine();

        try {
            engine = createInitializedEngine(modelPath, new Backend.GPU(), maxNumTokens, cacheDir);
            loadedBackend = "GPU";
        } catch (RuntimeException | Error gpuException) {
            // LiteRT-LM 0.17 reports unsupported GPU buffers without crashing, so retain
            // GPU-first behavior and safely use CPU on incompatible drivers.
            Log.w(TAG, "GPU initialization failed; falling back to CPU", gpuException);
            engine = createInitializedEngine(modelPath, new Backend.CPU(), maxNumTokens, cacheDir);
            loadedBackend = "CPU";
        }
        loadedModelPath = modelPath;
        loadedMaxNumTokens = maxNumTokens;
    }

    private static Engine createInitializedEngine(
            String modelPath,
            Backend backend,
            int maxNumTokens,
            String cacheDir
    ) {
        EngineConfig engineConfig = new EngineConfig(
                modelPath,
                backend,
                null,
                null,
                maxNumTokens,
                null,
                cacheDir
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
            loadedMaxNumTokens = 0;
        }
    }

    public static synchronized Result generate(
            String modelPath,
            String cacheDir,
            String systemInstruction,
            String prompt
    ) {
        return generateStreaming(
                modelPath,
                cacheDir,
                systemInstruction,
                prompt,
                4096,
                700,
                null
        );
    }

    public interface StreamCallback {
        void onUpdate(String text, int outputTokens, double elapsedSeconds, double tokensPerSecond);
    }

    public static synchronized Result generateStreaming(
            String modelPath,
            String cacheDir,
            String systemInstruction,
            String prompt,
            int maxNumTokens,
            int maxOutputTokens,
            StreamCallback streamCallback
    ) {
        initialize(modelPath, maxNumTokens, cacheDir);
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
                maxOutputTokens,
                new ThinkingConfig(false, 0),
                defaults.getEnableResponseFormat()
        );
        final long startedAtNanos = System.nanoTime();
        try (Conversation conversation = engine.createConversation(conversationConfig)) {
            activeConversation = conversation;
            CountDownLatch completion = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            StringBuilder generatedText = new StringBuilder();
            final int[] streamedTokens = {0};

            conversation.sendMessageAsync(prompt, new MessageCallback() {
                @Override
                public void onMessage(Message message) {
                    String chunk = message.toString();
                    synchronized (generatedText) {
                        String current = generatedText.toString();
                        if (chunk.startsWith(current)) {
                            generatedText.setLength(0);
                            generatedText.append(chunk);
                        } else {
                            generatedText.append(chunk);
                        }
                        streamedTokens[0]++;
                        double elapsed = (System.nanoTime() - startedAtNanos) / 1_000_000_000.0;
                        if (streamCallback != null) {
                            streamCallback.onUpdate(
                                    generatedText.toString(),
                                    streamedTokens[0],
                                    elapsed,
                                    elapsed > 0 ? streamedTokens[0] / elapsed : 0.0
                            );
                        }
                    }
                }

                @Override
                public void onDone() {
                    completion.countDown();
                }

                @Override
                public void onError(Throwable throwable) {
                    failure.set(throwable);
                    completion.countDown();
                }
            });
            try {
                completion.await();
            } catch (InterruptedException interruptedException) {
                conversation.cancelProcess();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("本地生成已停止", interruptedException);
            }
            if (failure.get() != null) {
                throw new IllegalStateException("本地模型生成失败", failure.get());
            }
            double timeToFirstTokenSeconds = Double.NaN;
            double prefillTokensPerSecond = Double.NaN;
            double decodeTokensPerSecond = Double.NaN;
            int prefillTokenCount = 0;
            int decodeTokenCount = streamedTokens[0];
            try {
                BenchmarkInfo benchmark = conversation.getBenchmarkInfo();
                timeToFirstTokenSeconds = benchmark.getTimeToFirstTokenInSecond();
                prefillTokenCount = benchmark.getLastPrefillTokenCount();
                decodeTokenCount = benchmark.getLastDecodeTokenCount();
                prefillTokensPerSecond = benchmark.getLastPrefillTokensPerSecond();
                decodeTokensPerSecond = benchmark.getLastDecodeTokensPerSecond();
            } catch (RuntimeException benchmarkException) {
                // BenchmarkParams are optional in LiteRT-LM. A completed response must not be
                // discarded merely because this build does not collect performance counters.
                Log.d(TAG, "LiteRT-LM benchmark info is unavailable", benchmarkException);
            }
            return new Result(
                    generatedText.toString(),
                    loadedBackend,
                    prefillTokenCount,
                    decodeTokenCount,
                    (System.nanoTime() - startedAtNanos) / 1_000_000_000.0,
                    timeToFirstTokenSeconds,
                    prefillTokensPerSecond,
                    decodeTokensPerSecond
            );
        } finally {
            activeConversation = null;
        }
    }

    public static void cancelGeneration() {
        Conversation conversation = activeConversation;
        if (conversation != null) conversation.cancelProcess();
    }

    public static final class Result {
        private final String text;
        private final String backend;
        private final int prefillTokenCount;
        private final int decodeTokenCount;
        private final double elapsedSeconds;
        private final double timeToFirstTokenSeconds;
        private final double prefillTokensPerSecond;
        private final double decodeTokensPerSecond;

        Result(
                String text,
                String backend,
                int prefillTokenCount,
                int decodeTokenCount,
                double elapsedSeconds,
                double timeToFirstTokenSeconds,
                double prefillTokensPerSecond,
                double decodeTokensPerSecond
        ) {
            this.text = text;
            this.backend = backend;
            this.prefillTokenCount = prefillTokenCount;
            this.decodeTokenCount = decodeTokenCount;
            this.elapsedSeconds = elapsedSeconds;
            this.timeToFirstTokenSeconds = timeToFirstTokenSeconds;
            this.prefillTokensPerSecond = prefillTokensPerSecond;
            this.decodeTokensPerSecond = decodeTokensPerSecond;
        }

        public String getText() { return text; }
        public String getBackend() { return backend; }
        public int getPrefillTokenCount() { return prefillTokenCount; }
        public int getDecodeTokenCount() { return decodeTokenCount; }
        public double getElapsedSeconds() { return elapsedSeconds; }
        public double getTimeToFirstTokenSeconds() { return timeToFirstTokenSeconds; }
        public double getPrefillTokensPerSecond() { return prefillTokensPerSecond; }
        public double getDecodeTokensPerSecond() { return decodeTokensPerSecond; }
    }
}
