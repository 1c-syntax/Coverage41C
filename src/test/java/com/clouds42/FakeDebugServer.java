/*
 * This file is a part of Coverage41C.
 *
 * Copyright (c) 2020-2026
 * Kosolapov Stanislav aka proDOOMman <prodoomman@gmail.com> and contributors
 *
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * Coverage41C is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * Coverage41C is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with Coverage41C.
 */
package com.clouds42;

import com._1c.g5.v8.dt.debug.model.base.data.AttachDebugUIResult;
import com._1c.g5.v8.dt.debug.model.base.data.BSLModuleIdInternal;
import com._1c.g5.v8.dt.debug.model.base.data.DataFactory;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetId;
import com._1c.g5.v8.dt.debug.model.base.data.DebugTargetType;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.CommandsFactory;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoBase;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmdInfoMeasure;
import com._1c.g5.v8.dt.debug.model.dbgui.commands.DBGUIExtCmds;
import com._1c.g5.v8.dt.debug.model.measure.MeasureFactory;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoLine;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoMain;
import com._1c.g5.v8.dt.debug.model.measure.PerformanceInfoModule;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.MiscRDbgGetAPIVerResponse;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.RDBGAttachDebugUIResponse;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.RDBGDetachDebugUIResponse;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.RDBGPingDebugUIResponse;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.RDBGSGetDbgTargetsResponse;
import com._1c.g5.v8.dt.debug.model.rdbg.request.response.ResponseFactory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

// Имитация сервера отладки 1С (dbgs): отвечает на команды rdbg, которые шлёт Coverage41C,
// и отдаёт в ping заранее поставленные события — замеры и старт предметов отладки.
class FakeDebugServer implements AutoCloseable {

    private static final String RESPONSE_NAMESPACE = "http://v8.1c.ru/8.3/debugger/debugRDBGRequestResponse";

    private final MyRuntimeDebugModelXmlSerializer serializer = new MyRuntimeDebugModelXmlSerializer();
    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final BlockingQueue<DBGUIExtCmdInfoBase> events = new LinkedBlockingQueue<>();

    private final AtomicInteger pings = new AtomicInteger();
    private final BlockingQueue<List<DBGUIExtCmdInfoBase>> finalPolls = new LinkedBlockingQueue<>();
    private volatile boolean profilingDisabled;
    private volatile boolean failFinalPolls;
    private volatile int failedFinalPollNumber;
    private final AtomicInteger finalPollNumber = new AtomicInteger();
    private volatile boolean failTargetLookups;
    private volatile String failedLookupArea;
    private final Map<String, List<DebugTargetId>> areaTargets = new ConcurrentHashMap<>();

    void failTargetLookupForArea(String area) { failedLookupArea = area; }
    void setAreaTargets(String area, DebugTargetId... newTargets) { areaTargets.put(area, List.of(newTargets)); }
    private volatile int overriddenFinalPollNumber;
    private int finalPollStatus;
    private String finalPollContentType;
    private byte[] finalPollBody;

    void finalPollHttpResponse(int number, int status, String contentType, String body) {
        finalPollStatus = status;
        finalPollContentType = contentType;
        finalPollBody = body.getBytes(StandardCharsets.UTF_8);
        overriddenFinalPollNumber = number;
    }

    void failFinalPoll(int number) { failedFinalPollNumber = number; }
    void failTargetLookups() { failTargetLookups = true; }
    void restoreTargetLookups() { failTargetLookups = false; failedLookupArea = null; }
    private final AtomicBoolean failOnePing = new AtomicBoolean();
    private final CountDownLatch failedPing = new CountDownLatch(1);

    void failNextPing() { failOnePing.set(true); }

    boolean awaitFailedPing() throws InterruptedException {
        return failedPing.await(5, TimeUnit.SECONDS);
    }
    private final BlockingQueue<PausedPing> pausedPings = new LinkedBlockingQueue<>();

    static final class PausedPing implements AutoCloseable {
        final CountDownLatch arrived = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<DBGUIExtCmdInfoBase> reply;

        PausedPing(List<DBGUIExtCmdInfoBase> reply) { this.reply = reply; }
        @Override public void close() { release.countDown(); }
    }

    PausedPing pauseNextPing(DBGUIExtCmdInfoBase... reply) {
        PausedPing pause = new PausedPing(List.of(reply));
        pausedPings.add(pause);
        return pause;
    }

    void failFinalPolls() { failFinalPolls = true; }
    private volatile boolean compressResponses;
    // Что отдаёт getDbgTargets: по умолчанию один серверный предмет без имени пользователя, как раньше.
    private volatile List<DebugTargetId> targets = List.of(target(UUID.randomUUID()));

    static final class Request {
        final String command;
        final String body;
        final long receivedAt = System.nanoTime();

        Request(String command, String body) {
            this.command = command;
            this.body = body;
        }
    }

    FakeDebugServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/e1crdbg/rdbg", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    // Как настоящий dbgs, сжимает ответы deflate без заголовка zlib.
    void setCompressResponses(boolean compressResponses) {
        this.compressResponses = compressResponses;
    }

    void setTargets(DebugTargetId... newTargets) {
        targets = List.of(newTargets);
    }

    void finalPolls(List<DBGUIExtCmdInfoBase>... replies) {
        finalPolls.addAll(List.of(replies));
    }

    List<Request> requests(String command) {
        return requests.stream().filter(request -> request.command.equals(command)).collect(Collectors.toList());
    }

    // Отдаёт события в очередной ping и ждёт, пока Coverage41C их обработает и придёт за следующими.
    void deliver(DBGUIExtCmdInfoBase... newEvents) throws InterruptedException {
        events.addAll(List.of(newEvents));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!events.isEmpty()) {
            checkDeadline(deadline);
            Thread.sleep(10);
        }
        int taken = pings.get();
        while (pings.get() <= taken) {
            checkDeadline(deadline);
            Thread.sleep(10);
        }
    }

    private static void checkDeadline(long deadline) {
        if (System.nanoTime() > deadline) {
            throw new IllegalStateException("Coverage41C does not ping the debug server");
        }
    }

    static DBGUIExtCmdInfoMeasure measure(String objectId, String propertyId, int... linesAndHits) {
        return measure(objectId, propertyId, "", linesAndHits);
    }

    static DBGUIExtCmdInfoMeasure measure(String objectId, String propertyId, String extensionName,
                                          int... linesAndHits) {
        BSLModuleIdInternal moduleId = DataFactory.eINSTANCE.createBSLModuleIdInternal();
        moduleId.setObjectID(objectId);
        moduleId.setPropertyID(propertyId);
        moduleId.setExtensionName(extensionName);
        PerformanceInfoModule module = MeasureFactory.eINSTANCE.createPerformanceInfoModule();
        module.setModuleID(moduleId);
        for (int i = 0; i < linesAndHits.length; i += 2) {
            PerformanceInfoLine line = MeasureFactory.eINSTANCE.createPerformanceInfoLine();
            line.setLineNo(new BigDecimal(linesAndHits[i]));
            line.setFrequency(new BigDecimal(linesAndHits[i + 1]));
            module.getLineInfo().add(line);
        }
        PerformanceInfoMain main = MeasureFactory.eINSTANCE.createPerformanceInfoMain();
        main.getModuleData().add(module);
        DBGUIExtCmdInfoMeasure measure = CommandsFactory.eINSTANCE.createDBGUIExtCmdInfoMeasure();
        measure.setCmdID(DBGUIExtCmds.MEASURE_RESULT_PROCESSING);
        measure.setMeasure(main);
        return measure;
    }

    // Замер от конкретного предмета: dbgs ставит его и в команду, и внутрь замера.
    static DBGUIExtCmdInfoMeasure measure(DebugTargetId target, String objectId, String propertyId,
                                          int... linesAndHits) {
        DBGUIExtCmdInfoMeasure measure = measure(objectId, propertyId, "", linesAndHits);
        measure.getMeasure().setTargetID(EcoreUtil.copy(target));
        measure.setTargetID(EcoreUtil.copy(target));
        return measure;
    }

    private static DebugTargetId target(UUID targetId) {
        return target(targetId, DebugTargetType.SERVER, null);
    }

    // userName == null — имя пользователя не задано, как у dbgs без сведений о сеансе.
    static DebugTargetId target(UUID targetId, DebugTargetType type, String userName) {
        DebugTargetId target = DataFactory.eINSTANCE.createDebugTargetId();
        target.setId(targetId.toString());
        target.setSeanceId(UUID.randomUUID().toString());
        target.setSeanceNo(BigDecimal.valueOf(Math.abs(targetId.hashCode()) % 1000));
        target.setTargetType(type);
        if (userName != null) {
            target.setUserName(userName);
        }
        return target;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String query = exchange.getRequestURI().getQuery();
            String command = query.replaceFirst("^cmd=([^&]*).*$", "$1");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(command, body));
            if (command.equals("setMeasureMode")) {
                profilingDisabled = !body.contains("measureModeSeanceID");
            }
            int finalPoll = command.equals("pingDebugUIParams") && profilingDisabled
                    ? finalPollNumber.incrementAndGet() : 0;
            // Reserve the reply even when this poll fails, so the next poll gets its own data.
            List<DBGUIExtCmdInfoBase> finalReply = finalPoll > 0 ? finalPolls.poll() : null;
            if (command.equals("pingDebugUIParams") && failOnePing.compareAndSet(true, false)) {
                exchange.sendResponseHeaders(503, -1);
                failedPing.countDown();
                return;
            }
            if (command.equals("getDbgTargets") && (failTargetLookups
                    || (failedLookupArea != null && body.contains(">" + failedLookupArea + "<")))) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            if (finalPoll > 0 && (finalPoll == failedFinalPollNumber || failFinalPolls)) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            if (finalPoll > 0 && finalPoll == overriddenFinalPollNumber) {
                if (finalPollContentType != null) {
                    exchange.getResponseHeaders().add("Content-Type", finalPollContentType);
                }
                exchange.sendResponseHeaders(finalPollStatus, finalPollStatus == 204 ? -1 : finalPollBody.length);
                if (finalPollStatus != 204) {
                    exchange.getResponseBody().write(finalPollBody);
                }
                return;
            }
            EObject response = response(command, finalReply, body);
            if (response == null) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            byte[] content = toDbgsXml(response).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=UTF-8");
            if (compressResponses) {
                content = deflate(content);
                exchange.getResponseHeaders().add("Content-Encoding", "deflate");
            }
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(content);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private EObject response(String command, List<DBGUIExtCmdInfoBase> finalReply, String body) throws InterruptedException {
        switch (command) {
            case "attachDebugUI":
                RDBGAttachDebugUIResponse attach = ResponseFactory.eINSTANCE.createRDBGAttachDebugUIResponse();
                attach.setResult(AttachDebugUIResult.REGISTERED);
                return attach;
            case "detachDebugUI":
                RDBGDetachDebugUIResponse detach = ResponseFactory.eINSTANCE.createRDBGDetachDebugUIResponse();
                detach.setResult(true);
                return detach;
            case "getRDbgAPIVer":
                MiscRDbgGetAPIVerResponse version = ResponseFactory.eINSTANCE.createMiscRDbgGetAPIVerResponse();
                version.setVersion("8.3.24");
                return version;
            case "getDbgTargets":
                RDBGSGetDbgTargetsResponse list = ResponseFactory.eINSTANCE.createRDBGSGetDbgTargetsResponse();
                List<DebugTargetId> listed = areaTargets.entrySet().stream()
                        .filter(entry -> body.contains(">" + entry.getKey() + "<"))
                        .map(Map.Entry::getValue).findFirst().orElse(targets);
                listed.forEach(target -> list.getId().add(EcoreUtil.copy(target)));
                return list;
            case "pingDebugUIParams":
                return ping(finalReply);
            default:
                return null;
        }
    }

    private EObject ping(List<DBGUIExtCmdInfoBase> finalReply) throws InterruptedException {
        pings.incrementAndGet();
        RDBGPingDebugUIResponse ping = ResponseFactory.eINSTANCE.createRDBGPingDebugUIResponse();
        PausedPing pause = pausedPings.poll();
        if (pause != null) {
            pause.arrived.countDown();
            if (!pause.release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to release ping");
            }
            ping.getResult().addAll(pause.reply);
            return ping;
        }
        if (finalReply != null) {
            ping.getResult().addAll(finalReply);
            return ping;
        }
        DBGUIExtCmdInfoBase event = events.poll(20, TimeUnit.MILLISECONDS);
        if (event != null) {
            ping.getResult().add(event);
            events.drainTo(ping.getResult());
        }
        return ping;
    }

    private static byte[] deflate(byte[] content) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try (DeflaterOutputStream output = new DeflaterOutputStream(compressed, deflater)) {
            output.write(content);
        } finally {
            deflater.end();
        }
        return compressed.toByteArray();
    }

    // dbgs называет корневой элемент ответа просто response.
    private String toDbgsXml(EObject response) throws IOException {
        String root = "response:" + response.getClass().getSimpleName().replaceAll("Impl$", "");
        return serializer.convertToXml(response).trim()
                .replaceFirst("<" + root + " ", "<response xmlns:debugRDBGRequestResponse=\"" + RESPONSE_NAMESPACE + "\" ")
                .replace("</" + root + ">", "</response>");
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
