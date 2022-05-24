package org.ovirt.engine.core.bll.scheduling.utils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.ovirt.engine.core.common.businessentities.CpuPinningPolicy;
import org.ovirt.engine.core.common.businessentities.NumaNodeStatistics;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VdsCpuUnit;
import org.ovirt.engine.core.common.businessentities.VdsNumaNode;
import org.ovirt.engine.core.common.utils.CpuPinningHelper;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.VdsNumaNodeDao;
import org.ovirt.engine.core.vdsbroker.ResourceManager;

public class VdsCpuUnitPinningHelper {
    @Inject
    private ResourceManager resourceManager;
    @Inject
    private VdsNumaNodeDao vdsNumaNodeDao;

    /**
     * This function will tell if the host is capable to run a given dedicated CPU policy VM.
     *
     * The function apply the pending CPU pinning on the host topology, then:
     * Pass on the online sockets for the host, check its available cores and count them as long we don't break
     * the virtual topology.
     * If there are enough pCPUs - return true, otherwise false.
     *
     * @param vmToPendingPinnings Map of VDS GUID keys to list of VdsCpuUnits pending to be taken.
     * @param vm VM object.
     * @param hostId GUID of the VDS object.
     * @return boolean. True if possible to dedicate the VM on the host. Otherwise false.
     */
    public boolean isExclusiveCpuPinningPossibleOnHost(Map<Guid, List<VdsCpuUnit>> vmToPendingPinnings,
                                                       VM vm, Guid hostId) {
        List<VdsCpuUnit> cpuTopology = resourceManager.getVdsManager(hostId).getCpuTopology();

        if (!vm.getCpuPinningPolicy().isExclusive()) {
            // TODO: Implementation for siblings
            return false;
        }

        previewPinOfPendingExclusiveCpus(cpuTopology, vmToPendingPinnings);

        int socketsLeft = vm.getNumOfSockets();

        for (int socket : getOnlineSockets(cpuTopology)) {
            int coresInSocket;
            switch (vm.getCpuPinningPolicy()) {
                case DEDICATED:
                    coresInSocket = getAvailableCores(cpuTopology, socket, vm.getThreadsPerCpu());
                    break;
                case ISOLATE_THREADS:
                    coresInSocket = getAvailableCoresIsolated(cpuTopology, socket);
                    break;
                default:
                    throw new IllegalStateException("Unexpected value: " + vm.getCpuPinningPolicy());
            }
            int totalSocketsTaken = coresInSocket / vm.getCpuPerSocket();
            if (!vm.getvNumaNodeList().isEmpty()) {
                int highestAmountOfvNumaNodesInSocket = getVirtualNumaNodesInSocket(cpuTopology, vm, hostId, socket);
                totalSocketsTaken = Math.min(highestAmountOfvNumaNodesInSocket, totalSocketsTaken);
            }
            socketsLeft -= totalSocketsTaken;
            if (socketsLeft <= 0) {
                return true;
            }
        }
        return false;
    }

    public void previewPinOfPendingExclusiveCpus(List<VdsCpuUnit> cpuTopology, Map<Guid, List<VdsCpuUnit>> vmToPendingPinning) {
        for (var vmToPendingPinningEntry : vmToPendingPinning.entrySet()) {
            vmToPendingPinningEntry.getValue().forEach(pendingPinning -> {
                VdsCpuUnit cpuUnit = getCpu(cpuTopology, pendingPinning.getCpu());
                cpuUnit.pinVm(vmToPendingPinningEntry.getKey(), pendingPinning.getCpuPinningPolicy());
            });
        }
    }

    /**
     * This function will allocate the host CPUs to the given CPU pinning policy.
     *
     * The function apply the pending CPU pinning on the host topology, then:
     * The function will select the most available socket (most free CPUs). It will allocate the CPUs and check if we
     * pass the virtual topology.
     * When there are no more vCPUs to allocate - return the list of VdsCpuUnit we chose.
     *
     * @param vm VM object.
     * @param vmToPendingPinnings Map of VDS GUID keys to list of VdsCpuUnits pending to be taken.
     * @param hostId GUID of the VDS object.
     * @return List<{@link VdsCpuUnit}>. The list of VdsCpuUnit we are going to use. If not possible, return null.
     */
    public List<VdsCpuUnit> updatePhysicalCpuAllocations(VM vm, Map<Guid, List<VdsCpuUnit>> vmToPendingPinnings, Guid hostId) {
        List<VdsCpuUnit> cpuTopology = resourceManager.getVdsManager(hostId).getCpuTopology();
        if (cpuTopology.isEmpty()) {
            return new ArrayList<>();
        }

        previewPinOfPendingExclusiveCpus(cpuTopology, vmToPendingPinnings);
        return updatePhysicalCpuAllocations(vm, cpuTopology, hostId);
    }

    /**
     * This function will allocate the host CPUs to the given CPU pinning policy.
     *
     * The function will select the most available socket (most free CPUs). It will allocate the CPUs and check if we
     * pass the virtual topology.
     * When there are no more vCPUs to allocate - return the list of VdsCpuUnit we chose.
     *
     * @param vm VM object.
     * @param cpuTopology topology we want to apply the pinning to
     * @param hostId GUID of the VDS object.
     * @return List<{@link VdsCpuUnit}>. The list of VdsCpuUnit we are going to use. If not possible, return null.
     */
    public List<VdsCpuUnit> updatePhysicalCpuAllocations(VM vm, List<VdsCpuUnit> cpuTopology, Guid hostId) {
        if (cpuTopology.isEmpty()) {
            return new ArrayList<>();
        }

        // 'Resize and pin NUMA' policy also acts as manual pinning.
        if (!vm.getCpuPinningPolicy().isExclusive()) {
            List<VdsCpuUnit> cpusToBeAllocated = new ArrayList<>();
            String cpuPinning = vm.getVmPinning();
            if (cpuPinning == null || cpuPinning.isEmpty()) {
                return cpusToBeAllocated;
            }
            Set<Integer> requestedCpus = CpuPinningHelper.getAllPinnedPCpus(cpuPinning);
            for (Integer cpuId : requestedCpus) {
                VdsCpuUnit vdsCpuUnit = getCpu(cpuTopology, cpuId);
                if (vdsCpuUnit == null) {
                    // Taking offline CPU, should filter out on CpuPinningPolicyUnit.
                    return new ArrayList<>();
                }
                vdsCpuUnit.pinVm(vm.getId(), vm.getCpuPinningPolicy());
                cpusToBeAllocated.add(vdsCpuUnit);
            }
            return cpusToBeAllocated;
        }

        filterSocketsWithInsufficientMemoryForNumaNode(cpuTopology, vm, hostId);
        List<VdsCpuUnit> cpusToBeAllocated = allocateDedicatedCpus(cpuTopology, vm, hostId);
        if (vm.getCpuPinningPolicy() == CpuPinningPolicy.ISOLATE_THREADS) {
            List<Integer> socketsUsed = cpusToBeAllocated.stream().map(VdsCpuUnit::getSocket).distinct().collect(Collectors.toList());
            int pCores = 0;
            for (int socket: socketsUsed) {
                pCores += getCoresInSocket(cpusToBeAllocated, socket).stream().map(VdsCpuUnit::getCore).distinct().count();
            }
            return pCores == vm.getNumOfCpus() ? cpusToBeAllocated : null;
        }
        return cpusToBeAllocated.size() == vm.getNumOfCpus() ? cpusToBeAllocated : null;
    }

    private List<VdsCpuUnit> allocateDedicatedCpus(List<VdsCpuUnit> cpuTopology, VM vm, Guid hostId) {
        // We can assume that a valid pinning exists here (because the host was filtered beforehand).
        List<VdsCpuUnit> cpusToBeAllocated = new ArrayList<>();
        int socketsLeft = vm.getNumOfSockets();
        int onlineSockets = getOnlineSockets(cpuTopology).size();
        int numOfAllocatedCPUs = 0;
        while (onlineSockets > 0 && socketsLeft > 0) {
            List<VdsCpuUnit> cpusInChosenSocket = getMaxFreedSocket(cpuTopology);
            if (cpusInChosenSocket.isEmpty()) {
                break;
            }
            int amountOfVSocketsInPSockets = Integer.MAX_VALUE;
            if (!vm.getvNumaNodeList().isEmpty()) {
                int highestAmountOfvNumaNodesInSocket = getVirtualNumaNodesInSocket(cpuTopology, vm, hostId, cpusInChosenSocket.get(0).getSocket());
                int vNumaNodesInVirtualSocket = (int) Math.ceil((float)vm.getvNumaNodeList().size() / (float)vm.getNumOfSockets());
                amountOfVSocketsInPSockets = highestAmountOfvNumaNodesInSocket / vNumaNodesInVirtualSocket;
            }
            // coreCount is based on the VM topology
            int coreCount = 0;
            for (int core : getOnlineCores(cpusInChosenSocket)) {
                switch (vm.getCpuPinningPolicy()) {
                    case DEDICATED:
                        List<VdsCpuUnit> freeCpusInCore = getFreeCpusInCore(cpusInChosenSocket, core);
                        int coreThreads = freeCpusInCore.size();
                        while (coreThreads >= vm.getThreadsPerCpu() &&
                                cpusToBeAllocated.size() < vm.getNumOfCpus() &&
                                coreCount / vm.getCpuPerSocket() < amountOfVSocketsInPSockets) {
                            for (int thread = 0; thread < vm.getThreadsPerCpu() && cpusToBeAllocated.size() < vm.getNumOfCpus(); thread++) {
                                VdsCpuUnit cpuUnit = freeCpusInCore.remove(0);
                                cpuUnit.pinVm(vm.getId(), vm.getCpuPinningPolicy());
                                cpusToBeAllocated.add(cpuUnit);
                            }
                            coreCount++;
                            coreThreads -= vm.getThreadsPerCpu();
                        }
                        break;
                    case ISOLATE_THREADS:
                        List<VdsCpuUnit> cpusInCore = getCpusInCore(cpusInChosenSocket, core);
                        if (cpusInCore.stream().anyMatch(VdsCpuUnit::isPinned)) {
                            continue;
                        }
                        if (numOfAllocatedCPUs < vm.getNumOfCpus() && coreCount / vm.getCpuPerSocket() < amountOfVSocketsInPSockets) {
                            cpusInCore.forEach(cpu -> {
                                cpu.pinVm(vm.getId(), vm.getCpuPinningPolicy());
                                cpusToBeAllocated.add(cpu);
                            });
                            numOfAllocatedCPUs++;
                            if (numOfAllocatedCPUs % vm.getThreadsPerCpu() == 0) {
                                coreCount++;
                            }
                        }
                }
            }
            socketsLeft -= coreCount / vm.getCpuPerSocket();
            int coresReminder = coreCount % vm.getCpuPerSocket();
            for (int i = 0; i < coresReminder * vm.getThreadsPerCpu(); i++) {
                if (!cpusToBeAllocated.isEmpty()) {
                    cpusToBeAllocated.remove(cpusToBeAllocated.size() - 1);
                }
            }

            onlineSockets--;
        }
        if (socketsLeft > 0) {
            // We didn't manage to allocate the required sockets. We should never get here.
            return Collections.emptyList();
        }
        return cpusToBeAllocated;
    }

    private int countTakenCores(List<VdsCpuUnit> cpuTopology) {
        if (cpuTopology.isEmpty()) {
            return 0;
        }
        int numOfTakenCores = 0;
        for (int socket : getOnlineSockets(cpuTopology)) {
            for (int core : getOnlineCores(cpuTopology, socket)) {
                if (getNonExclusiveCpusInCore(cpuTopology, socket, core).isEmpty()) {
                    numOfTakenCores++;
                }
            }
        }
        return numOfTakenCores;
    }

    private List<VdsCpuUnit> getCoresInSocket(List<VdsCpuUnit> cpuTopology, int socketId) {
        return cpuTopology.stream().filter(vdsCpuUnit -> vdsCpuUnit.getSocket() == socketId).collect(Collectors.toList());
    }

    private List<VdsCpuUnit> getCpusInCore(List<VdsCpuUnit> cpuTopology, int coreId) {
        return cpuTopology.stream().filter(vdsCpuUnit -> vdsCpuUnit.getCore() == coreId).collect(Collectors.toList());
    }

    private List<VdsCpuUnit> getFreeCpusInSocket(List<VdsCpuUnit> cpuTopology, int socketId) {
        return getCoresInSocket(cpuTopology, socketId).stream().filter(cpu -> !cpu.isPinned()).collect(Collectors.toList());
    }

    private List<VdsCpuUnit> getFreeCpusInCore(List<VdsCpuUnit> cpuTopology, int socketId, int coreId) {
        return getCpusInCore(getCoresInSocket(cpuTopology, socketId), coreId).stream().filter(cpu -> !cpu.isPinned()).collect(Collectors.toList());
    }

    private List<VdsCpuUnit> getFreeCpusInCore(List<VdsCpuUnit> cpuTopology, int coreId) {
        return getCpusInCore(cpuTopology, coreId).stream().filter(cpu -> !cpu.isPinned()).collect(Collectors.toList());
    }

    private VdsCpuUnit getCpu(List<VdsCpuUnit> cpuTopology, int cpuId) {
        return cpuTopology.stream().filter(vdsCpuUnit -> vdsCpuUnit.getCpu() == cpuId).findFirst().orElse(null);
    }

    private List<VdsCpuUnit> getNonExclusiveCpusInCore(List<VdsCpuUnit> cpuTopology, int socketId, int coreId) {
        return getCpusInCore(getCoresInSocket(cpuTopology, socketId), coreId).stream().filter(cpu -> !cpu.isExclusive()).collect(Collectors.toList());
    }

    private int getDedicatedCount(List<VdsCpuUnit> cpuTopology) {
        return (int) cpuTopology.stream().filter(VdsCpuUnit::isExclusive).count();
    }

    /**
     * Counts how many CPUs (cores or threads, depending on the countThreadsAsCores) will be unavailable
     * due to the exclusive pinning.
     *
     * @param cpuTopology CPU topology we want to process
     * @param countThreadsAsCores If the threads should be counted as cores
     * @return Number of CPUs that are exclusively pinned or blocked by an exclusive pinning
     */
    public int countUnavailableCpus(List<VdsCpuUnit> cpuTopology, boolean countThreadsAsCores) {
        if (countThreadsAsCores) {
            return getDedicatedCount(cpuTopology);
        } else {
            return countTakenCores(cpuTopology);
        }
    }

    private List<Integer> getOnlineSockets(List<VdsCpuUnit> cpuTopology) {
        return cpuTopology.stream().map(VdsCpuUnit::getSocket).distinct().collect(Collectors.toList());
    }

    private List<Integer> getOnlineCores(List<VdsCpuUnit> cpuTopology, int socket) {
        return getCoresInSocket(cpuTopology, socket).stream().map(VdsCpuUnit::getCore).distinct().collect(Collectors.toList());
    }

    private List<Integer> getOnlineCores(List<VdsCpuUnit> cpuTopology) {
        return cpuTopology.stream().map(VdsCpuUnit::getCore).distinct().collect(Collectors.toList());
    }

    private List<VdsCpuUnit> getMaxFreedSocket(List<VdsCpuUnit> cpuTopology) {
        List<VdsCpuUnit> chosenSocket = Collections.emptyList();
        List<VdsCpuUnit> temp;
        for (int socket : getOnlineSockets(cpuTopology)) {
            temp = getFreeCpusInSocket(cpuTopology, socket);
            if (temp.size() > chosenSocket.size()) {
                chosenSocket = temp;
            }
        }
        return chosenSocket;
    }

    private int getAvailableCores(List<VdsCpuUnit> cpuTopology, int socket, int vThreads) {
        int count = 0;
        for (int core : getOnlineCores(cpuTopology, socket)) {
            List<VdsCpuUnit> freeCpusInCore = getFreeCpusInCore(cpuTopology, socket, core);
            count += freeCpusInCore.size() / vThreads;
        }
        return count;
    }

    private int getAvailableCoresIsolated(List<VdsCpuUnit> cpuTopology, int socket) {
        int count = 0;
        for (int core : getOnlineCores(cpuTopology, socket)) {
            List<VdsCpuUnit> cpusInCore = getCpusInCore(getCoresInSocket(cpuTopology, socket), core);
            if (cpusInCore.stream().anyMatch(VdsCpuUnit::isPinned)) {
                continue;
            }
            count++;
        }
        return count;
    }

    /**
     * Filters and removes physical sockets from being available based on the VM NUMA nodes memory requirements.
     * The memory is accumulated per physical socket (can be multiple physical NUMA nodes), as we use INTERLEAVED
     * tune for the pinning.
     * The cpuTopology is a deep copy made locally for this specific call, therefore we can change it.
     * @param cpuTopology List<{@link VdsCpuUnit}>. The list of VdsCpuUnit.
     * @param vm VM object.
     * @param vdsId GUID of the VDS object.
     */
    private void filterSocketsWithInsufficientMemoryForNumaNode(List<VdsCpuUnit> cpuTopology, VM vm, Guid vdsId) {
        if (vm.getvNumaNodeList().isEmpty()) {
            return;
        }

        List<VdsNumaNode> vdsNumaNodes = vdsNumaNodeDao.getAllVdsNumaNodeByVdsId(vdsId);
        if (vdsNumaNodes == null || vdsNumaNodes.isEmpty()) {
            return;
        }

        // assuming the memory is split equally between all vNuma nodes
        Long memRequired = vm.getvNumaNodeList().get(0).getMemTotal();
        for (int socket : getOnlineSockets(cpuTopology)) {
            List<Integer> numaIdsInSocket = getNumaIdsInSocket(cpuTopology, socket);
            List<VdsNumaNode> numasInSocket = vdsNumaNodes.stream()
                    .filter(numa -> numaIdsInSocket.contains(numa.getIndex()))
                    .collect(Collectors.toList());
            Long totalMemory = numasInSocket.stream()
                    .map(VdsNumaNode::getNumaNodeStatistics)
                    .map(NumaNodeStatistics::getMemFree)
                    .reduce(0L, Long::sum);
            if (totalMemory < memRequired) {
                // Memory is missing or there is no enough total memory available for the socket.
                cpuTopology.removeAll(cpuTopology.stream().filter(cpu -> cpu.getSocket() == socket).collect(Collectors.toList()));
            }
        }
    }

    private List<Integer> getNumaIdsInSocket(List<VdsCpuUnit> cpuTopology, int socket) {
        return cpuTopology.stream().filter(cpu -> cpu.getSocket() == socket).map(VdsCpuUnit::getNuma).distinct().collect(Collectors.toList());
    }

    private int getVirtualNumaNodesInSocket(List<VdsCpuUnit> cpuTopology, VM vm, Guid vdsId, int socket) {
        List<VdsNumaNode> vdsNumaNodes = vdsNumaNodeDao.getAllVdsNumaNodeByVdsId(vdsId);
        if (vdsNumaNodes == null || vdsNumaNodes.isEmpty()) {
            return Integer.MAX_VALUE;
        }

        List<Integer> numaIdsInSocket = getNumaIdsInSocket(cpuTopology, socket);
        List<VdsNumaNode> numasInSocket = vdsNumaNodes.stream()
                .filter(numa -> numaIdsInSocket.contains(numa.getIndex()))
                .collect(Collectors.toList());
        Long totalMemory = numasInSocket.stream()
                .map(VdsNumaNode::getNumaNodeStatistics)
                .map(NumaNodeStatistics::getMemFree)
                .reduce(0L, Long::sum);
        return (int) (totalMemory / vm.getvNumaNodeList().get(0).getMemTotal());
    }
}
