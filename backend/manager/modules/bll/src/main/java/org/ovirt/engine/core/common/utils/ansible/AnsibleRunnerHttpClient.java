/*
 * Copyright oVirt Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.ovirt.engine.core.common.utils.ansible;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Singleton;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

@Singleton
public class AnsibleRunnerHttpClient {

//    private static final Object inventoryLock = new Object();
//    private static final Object executeLock = new Object();
//    private static final String HOST_GROUP = "ovirt";
//    private static final String API_VERSION = "/api/v1";

    private static Logger log = LoggerFactory.getLogger(AnsibleRunnerHttpClient.class);

    private ObjectMapper mapper;
    private AnsibleRunnerLogger runnerLogger;
    private String lastEvent = "";
    private static final int POLL_INTERVAL = 3000;
//    private UUID uuid;
    private AnsibleReturnValue returnValue;

    public AnsibleRunnerHttpClient() {
        this.mapper = JsonMapper
                .builder()
                .findAndAddModules()
                .build()
                .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
        this.returnValue = new AnsibleReturnValue(AnsibleReturnCode.ERROR);
    }

    public Boolean playHasEnded(UUID uuid) {
        String jobEvents = getJobEventsDir(uuid.toString());
        File lastEventFile = new File(jobEvents + lastEvent);
        String res = "";
        try {
                res = Files.readString(lastEventFile.toPath());
            } catch (IOException e) {
                return false;
            }
        return res.contains("playbook_on_stats");
    }

    public AnsibleReturnValue artifactHandler(UUID uuid, int lastEventID, int timeout, BiConsumer<String, String> fn)
            throws Exception { // TODO is this the correct place?
        int iteration = 0;
        setReturnValue(uuid);
        // retrieve timeout from engine constants.
        while (!playHasEnded(uuid)) { //return -1 incase of an error)
            if (lastEventID == -1) {
                return returnValue;
            }
            if (iteration > timeout * 60) {
                // Cancel playbook, and raise exception in case timeout occur:
                //            runnerClient.cancelPlaybook(playUuid);
                cancelPlaybook(uuid, timeout);
                throw new TimeoutException(
                        "Play execution has reached timeout");
            }
            lastEventID = processEvents(uuid.toString(), lastEventID, fn, "", Paths.get(""));
            iteration += POLL_INTERVAL / 1000;
        }
        returnValue.setAnsibleReturnCode(AnsibleReturnCode.OK);
        return returnValue;
    }

    public void setReturnValue(UUID uuid) {
        returnValue.setPlayUuid(uuid.toString());
        returnValue.setLogFile(runnerLogger.getLogFile());
    }

    public List<String> getSortedEvents(String playUuid, int lastEventId) throws InterruptedException, IOException {
        Boolean artifactsIsPopulated = false;
        List<String> sortedEvents = new ArrayList<>();

        while (!artifactsIsPopulated) {
            Thread.sleep(1500);

            // ignoring incompleted json files, add to list only events that haven't been handles yet.
            String jobEvents = getJobEventsDir(playUuid);
            if (Files.exists(Paths.get(jobEvents))) {
                sortedEvents = Stream.of(new File(jobEvents).listFiles())
                        .map(File::getName)
                        .distinct()
                        .filter(item -> !item.contains("partial"))
                        .filter(item -> (Integer.valueOf(item.split("-")[0])) > lastEventId)
                        .collect(Collectors.toList());
                artifactsIsPopulated = true;
            }
        }
        return sortedEvents;
    }

    public int getLastEventId() {
        return Integer.valueOf(lastEvent.split("-")[0]);
    }

    public String getJobEventsDir(String playUuid) {
        return String.format("%1$s/artifacts/%2$s/job_events/", AnsibleConstants.HOST_DEPLOY_PROJECT_DIR, playUuid);
    }


    public int processEvents(String playUuid,
            int lastEventId,
            BiConsumer<String, String> fn,
            String msg,
            Path logFile) {
        List<String> sortedEvents = new ArrayList<>();
        try {
            sortedEvents = getSortedEvents(playUuid, lastEventId);
        } catch (InterruptedException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        String jobEvents = getJobEventsDir(playUuid);
        for (String event : sortedEvents) {
            JsonNode currentNode = getEvent(jobEvents + event);
            String stdout = RunnerJsonNode.getStdout(currentNode);

            if (RunnerJsonNode.isEventUnreachable(currentNode)) {
                runnerLogger.log(currentNode);
                returnValue.setAnsibleReturnCode(AnsibleReturnCode.UNREACHABLE);
                // returnValue.setStderr(""); // TODO: set status from file
                return -1;
            }

            // might need special attention
            if (RunnerJsonNode.isEventVerbose(currentNode)) {
                if (!stdout.contains("Identity added")) {
                    runnerLogger.log(stdout);
                }
            }

            // want to log only these kind of events:
            if (RunnerJsonNode.isEventStart(currentNode) || RunnerJsonNode.isEventOk(currentNode)
                    || RunnerJsonNode.playbookStats(currentNode) || RunnerJsonNode.isEventFailed(currentNode)) {

                String taskName = "";
                JsonNode eventNode = currentNode.get("event_data");

                JsonNode taskNode = eventNode.get("task");
                if (taskNode != null) {
                    taskName = taskNode.textValue();
                }

                if (RunnerJsonNode.isEventStart(currentNode) || RunnerJsonNode.playbookStats(currentNode)) {
                    runnerLogger.log(stdout);
                }

                String action = "";
                JsonNode eventAction = eventNode.get("task_action");
                if (eventAction != null) {
                    action = eventAction.asText();
                }

                if (RunnerJsonNode.isEventOk(currentNode)) {
                    runnerLogger.log(currentNode);

                    String taskText = action.equals("debug")
                            ? RunnerJsonNode.formatDebugMessage(taskName, stdout)
                            : taskName;
                    fn.accept(taskText, String.format(jobEvents + event));

                } else if (RunnerJsonNode.isEventFailed(currentNode)) {
                    runnerLogger.log(currentNode);
                    if (!RunnerJsonNode.ignore(currentNode)) {
                        returnValue.setAnsibleReturnCode(AnsibleReturnCode.FAIL);
                        throw new AnsibleRunnerCallException(
                                String.format(
                                        "Task %1$s failed to execute. Please check logs for more details: %2$s",
                                        taskName,
                                        runnerLogger.getLogFile()));
                    }
                }
            }
            lastEvent = event;
            returnValue.setLastEventId(getLastEventId());
        }
        return lastEvent.isEmpty() ? lastEventId : getLastEventId();
    }

    private Boolean jsonIsValid(String content) {
        try {
            final ObjectMapper mapper = new ObjectMapper();
            mapper.readTree(content);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void cancelPlaybook(UUID uuid, int timeout) throws Exception {
        Process ansibleProcess;
        File output = File.createTempFile("output", ".log");
        String command = String.format("ansible-runner stop %1$s", uuid);
        ProcessBuilder ansibleProcessBuilder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output);
        ansibleProcess = ansibleProcessBuilder.start();
        if (!ansibleProcess.waitFor(timeout, TimeUnit.MINUTES)) {
            throw new Exception("Timeout occurred while canceling Ansible playbook.");
        }
        if (ansibleProcess.exitValue() != 0) {
            throw new AnsibleRunnerCallException(
                    "Failed to execute call to cancel playbook. %1$s",
                    output.toString());
        }
    }

    public void runPlaybook(List<String> command, int timeout) throws Exception {
        final Object executeLock = new Object();
        log.error("***inside run playbook***");
        Process ansibleProcess;
        File output = File.createTempFile("output", ".log");
        synchronized (executeLock) {
            ProcessBuilder ansibleProcessBuilder =
                    new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output);
            ansibleProcess = ansibleProcessBuilder.start();
            if (!ansibleProcess.waitFor(timeout, TimeUnit.MINUTES)) {
                throw new Exception("Timeout occurred while executing Ansible playbook.");
            }
            if (ansibleProcess.exitValue() != 0) {
                throw new AnsibleRunnerCallException(
                        "Failed to execute call to start playbook. %1$s",
                        output.toString()); // TODO: need to pass not path but content
            }
        }
    }

    protected String formatCommandVariables(Map<String, Object> variables, String playAction) {
        String result;
        try {
            result = mapper.writeValueAsString(variables);
        } catch (IOException ex) {
            throw new AnsibleRunnerCallException("Failed to create host deploy variables mapper", ex);
        }
        return result;
    }

    public PlaybookStatus getPlaybookStatus(String playUuid) {
        String status = "";
        String rc = "";
        try {
            status = Files.readString(Paths.get(AnsibleConstants.ARTIFACTS_DIR, playUuid, "/status"));
            rc = Files.readString(Paths.get(AnsibleConstants.ARTIFACTS_DIR, playUuid, "/rc"));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new PlaybookStatus(rc, status);
    }

    private String[] getEvents(String playUuid) {
        File jobEvents = new File(getJobEventsDir(playUuid));
        return jobEvents.list();
    }

    public int getTotalEvents(String playUuid) {
        String[] events = getEvents(playUuid);
        return events.length;
    }

    private JsonNode getEvent(String eventPath) {
        // Fetch the event info:
        JsonNode currentNode = null;
        ObjectMapper mapper = new ObjectMapper();
        try {
            String jsonOutput = Files.readString(Paths.get(eventPath), StandardCharsets.UTF_8);
            if (!jsonIsValid(jsonOutput)) {
//                throw new AnsibleRunnerCallException(
//                        "Failed to fetch info about event. %1$s: %2$s",
//                        RunnerJsonNode.status(eventDir), //TODO check where and how to retrieve status & msg?
//                        RunnerJsonNode.msg(eventDir)
//                );
            }
            currentNode = mapper.readTree(jsonOutput);
        } catch(Exception e) {
            e.printStackTrace();
        }
        return currentNode;
    }

    public String getVdsmId(String eventUrl) {
        // Fetch the event info:
        JsonNode event = getEvent(eventUrl);

        // Parse the output of the events info:
        JsonNode taskNode = RunnerJsonNode.taskNode(event);
        return RunnerJsonNode.content(taskNode);
    }

    public Set<String> getYumPackages(String eventUrl) {
        // Fetch the event info:
        JsonNode event = getEvent(eventUrl);

        // Parse the output of the events info:
        JsonNode taskNode = RunnerJsonNode.taskNode(event);
        Set<String> packages = new HashSet<>();
        if (RunnerJsonNode.hasUpdates(taskNode)) {
            packages = RunnerJsonNode.getPackages(taskNode);
        }

        if (!packages.isEmpty()) {
            log.info("Found updates of packages: {}", StringUtils.join(packages, ","));
        }

        return packages;

    }

    public String getCommandStdout(String eventUrl) {
        JsonNode event = getEvent(eventUrl);
        JsonNode taskNode = RunnerJsonNode.taskNode(event);
        return RunnerJsonNode.getStdout(taskNode);
    }

    public void setLogger(AnsibleRunnerLogger runnerLogger) {
        this.runnerLogger = runnerLogger;
    }

    public AnsibleRunnerLogger getLogger() {
        return this.runnerLogger;
    }

    public static class PlaybookStatus {
        private String status;
        private String msg;

        public PlaybookStatus(String status, String msg) {
            this.status = status;
            this.msg = msg;
        }

        public String getMsg() {
            return msg;
        }

        public String getStatus() {
            return status;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            PlaybookStatus that = (PlaybookStatus) o;
            return Objects.equals(status, that.status) &&
                    Objects.equals(msg, that.msg);
        }

        @Override
        public int hashCode() {
            return Objects.hash(status, msg);
        }

        @Override
        public String toString() {
            return String.format("Playbook status: '%1$s' message: '%2$s'", this.status, this.msg);
        }
    }
}
