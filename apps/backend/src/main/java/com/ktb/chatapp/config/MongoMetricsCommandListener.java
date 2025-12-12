package com.ktb.chatapp.config;

import com.mongodb.event.CommandFailedEvent;
import com.mongodb.event.CommandListener;
import com.mongodb.event.CommandStartedEvent;
import com.mongodb.event.CommandSucceededEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.lang3.ObjectUtils.Null;
import org.bson.BsonDocument;
import org.bson.BsonValue;
import org.slf4j.MDC;

@Slf4j
public class MongoMetricsCommandListener implements CommandListener {

    private final MeterRegistry registry;

    // Use ConcurrentHashMap instead of ThreadLocal to support async operations
    private final Map<Integer, CommandContext> contextMap = new ConcurrentHashMap<>();

    private static class CommandContext {
        final long startTime;
        final String collection;
        final String traceId;
        final String apiPath;

        CommandContext(long startTime, String collection, String traceId, String apiPath) {
            this.startTime = startTime;
            this.collection = collection;
            this.traceId = traceId;
            this.apiPath = apiPath;
        }
    }

    public MongoMetricsCommandListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void commandStarted(CommandStartedEvent event) {
        String collection = "unknown";
        try {
            BsonDocument command = event.getCommand();
            if (command.containsKey(event.getCommandName())) {
                BsonValue val = command.get(event.getCommandName());
                if (val.isString()) {
                    collection = val.asString().getValue();
                }
            }
        } catch (Exception e) {
            // ignore
        }

        String traceId = MDC.get("traceId");
        String apiPath = MDC.get("apiPath");

        // Store context using RequestId which is consistent across start/success/fail
        // events
        contextMap.put(event.getRequestId(),
                new CommandContext(System.currentTimeMillis(), collection, traceId, apiPath));

    }

    @Override
    public void commandSucceeded(CommandSucceededEvent event) {
        CommandContext ctx = contextMap.remove(event.getRequestId());
        if (ctx == null)
            return;

        long duration = System.currentTimeMillis() - ctx.startTime;
        String traceId = ctx.traceId;
        String apiPath = ctx.apiPath;
        String collection = ctx.collection;
        if (collection == null)
            collection = "unknown";

        // Slow Query Check (Example: > 100ms)
        if (duration > 100) {
            Counter.builder("mongo.command.slow")
                    .tag("command", event.getCommandName())
                    .tag("db", event.getDatabaseName())
                    .tag("collection", collection)
                    .tag("api", apiPath != null ? apiPath : "none")
                    .tag("traceId", traceId != null ? traceId : "none")
                    .register(registry)
                    .increment();
        }

        Timer.builder("mongo.command.duration")
                .tag("command", event.getCommandName())
                .tag("db", event.getDatabaseName())
                .tag("collection", collection)
                .tag("traceId", traceId != null ? traceId : "none")
                .tag("api", apiPath != null ? apiPath : "none")
                .publishPercentileHistogram()
                .register(registry)
                .record(duration, TimeUnit.MILLISECONDS);

        log.info("[Mongo][OK] traceId={} cmd={} coll={} duration={}ms",
                traceId, event.getCommandName(), collection, duration);
    }

    @Override
    public void commandFailed(CommandFailedEvent event) {
        CommandContext ctx = contextMap.remove(event.getRequestId());
        if (ctx == null)
            return;

        String traceId = ctx.traceId;
        String apiPath = ctx.apiPath;

        Counter.builder("mongo.command.failed")
                .tag("command", event.getCommandName())
                .tag("traceId", traceId != null ? traceId : "none")
                .tag("api", apiPath != null ? apiPath : "none")
                .register(registry)
                .increment();
    }
}
