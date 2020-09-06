package org.ovirt.engine.core.bll.storage.disk;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.inject.Inject;

import org.apache.commons.lang.StringUtils;
import org.ovirt.engine.core.bll.LockMessagesMatchUtil;
import org.ovirt.engine.core.bll.ValidationResult;
import org.ovirt.engine.core.bll.VmCommand;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.storage.connection.IStorageHelper;
import org.ovirt.engine.core.bll.storage.connection.StorageHelperDirector;
import org.ovirt.engine.core.bll.storage.disk.cinder.CinderBroker;
import org.ovirt.engine.core.bll.storage.disk.managedblock.ManagedBlockStorageCommandUtil;
import org.ovirt.engine.core.bll.validator.VmValidator;
import org.ovirt.engine.core.bll.validator.storage.DiskOperationsValidator;
import org.ovirt.engine.core.bll.validator.storage.DiskValidator;
import org.ovirt.engine.core.bll.validator.storage.DiskVmElementValidator;
import org.ovirt.engine.core.bll.validator.storage.ManagedBlockStorageDomainValidator;
import org.ovirt.engine.core.common.action.ActionType;
import org.ovirt.engine.core.common.action.VmDiskOperationParameterBase;
import org.ovirt.engine.core.common.businessentities.StorageServerConnections;
import org.ovirt.engine.core.common.businessentities.VMStatus;
import org.ovirt.engine.core.common.businessentities.VmDevice;
import org.ovirt.engine.core.common.businessentities.network.VmNic;
import org.ovirt.engine.core.common.businessentities.storage.CinderDisk;
import org.ovirt.engine.core.common.businessentities.storage.Disk;
import org.ovirt.engine.core.common.businessentities.storage.DiskImage;
import org.ovirt.engine.core.common.businessentities.storage.DiskInterface;
import org.ovirt.engine.core.common.businessentities.storage.DiskVmElement;
import org.ovirt.engine.core.common.businessentities.storage.LUNs;
import org.ovirt.engine.core.common.businessentities.storage.LunDisk;
import org.ovirt.engine.core.common.businessentities.storage.ManagedBlockStorageDisk;
import org.ovirt.engine.core.common.businessentities.storage.StorageType;
import org.ovirt.engine.core.common.businessentities.storage.VolumeFormat;
import org.ovirt.engine.core.common.errors.EngineError;
import org.ovirt.engine.core.common.errors.EngineException;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.common.locks.LockingGroup;
import org.ovirt.engine.core.common.vdscommands.HotPlugDiskVDSParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.DiskVmElementDao;
import org.ovirt.engine.core.dao.StorageServerConnectionDao;
import org.ovirt.engine.core.dao.VmDeviceDao;
import org.ovirt.engine.core.dao.network.VmNicDao;
import org.ovirt.engine.core.utils.StringMapUtils;
import org.ovirt.engine.core.utils.archstrategy.ArchStrategyFactory;
import org.ovirt.engine.core.utils.lock.EngineLock;
import org.ovirt.engine.core.vdsbroker.architecture.GetControllerIndices;
import org.ovirt.engine.core.vdsbroker.builder.vminfo.VmInfoBuildUtils;
import org.ovirt.engine.core.vdsbroker.libvirt.DomainXmlUtils;
import org.ovirt.engine.core.vdsbroker.vdsbroker.VdsProperties;

public abstract class AbstractDiskVmCommand<T extends VmDiskOperationParameterBase> extends VmCommand<T> {

    private CinderBroker cinderBroker;

    @Inject
    private VmInfoBuildUtils vmInfoBuildUtils;
    @Inject
    private StorageServerConnectionDao storageServerConnectionDao;
    @Inject
    private VmNicDao vmNicDao;
    @Inject
    private DiskVmElementDao diskVmElementDao;
    @Inject
    private VmDeviceDao vmDeviceDao;
    @Inject
    private ManagedBlockStorageCommandUtil managedBlockStorageCommandUtil;
    @Inject
    private StorageHelperDirector storageHelperDirector;

    protected AbstractDiskVmCommand(Guid commandId) {
        super(commandId);
    }

    public AbstractDiskVmCommand(T parameters, CommandContext commandContext) {
        super(parameters, commandContext);
    }

    protected void performPlugCommand(VDSCommandType commandType,
            Disk disk,
            VmDevice vmDevice) {
        switch (disk.getDiskStorageType()) {
        case LUN:
            LunDisk lunDisk = (LunDisk) disk;
            if (commandType == VDSCommandType.HotPlugDisk) {
                LUNs lun = lunDisk.getLun();
                updateLUNConnectionsInfo(lun);

                lun.getLunConnections().stream().map(StorageServerConnections::getStorageType).distinct().forEach(t -> {
                    if (!getStorageHelper(t).connectStorageToLunByVdsId(null,
                            getVm().getRunOnVds(),
                            lun,
                            getVm().getStoragePoolId())) {
                        throw new EngineException(EngineError.StorageServerConnectionError);
                    }
                });
            }
            break;

        case CINDER:
            CinderDisk cinderDisk = (CinderDisk) disk;
            setStorageDomainId(cinderDisk.getStorageIds().get(0));
            getCinderBroker().updateConnectionInfoForDisk(cinderDisk);
            break;

        case MANAGED_BLOCK_STORAGE:
            if (commandType == VDSCommandType.HotPlugDisk) {
                ManagedBlockStorageDisk managedBlockStorageDisk = (ManagedBlockStorageDisk) disk;
                setStorageDomainId(managedBlockStorageDisk.getStorageIds().get(0));
                managedBlockStorageCommandUtil.saveDevices(managedBlockStorageDisk, getVds(), vmDevice);
            }
            break;

        default:
        }

        if (commandType == VDSCommandType.HotPlugDisk) {
            var address = getDiskAddress(vmDevice.getAddress(), getDiskVmElement().getDiskInterface());
            // Updating device's address immediately (instead of waiting to VmsMonitoring)
            // to prevent a duplicate unit value (i.e. ensuring a unique unit value).
            updateVmDeviceAddress(address, vmDevice);
        }

        disk.setDiskVmElements(Collections.singleton(getDiskVmElement()));
        runVdsCommand(commandType,
                new HotPlugDiskVDSParameters(getVm().getRunOnVds(),
                        getVm(),
                        disk,
                        vmDevice,
                        getDiskVmElement().getDiskInterface(),
                        getDiskVmElement().isPassDiscard()));
    }

    private IStorageHelper getStorageHelper(StorageType storageType) {
        return storageHelperDirector.getItem(storageType);
    }

    protected ValidationResult isOperationSupportedByManagedBlockStorage(ActionType actionType) {
        return ManagedBlockStorageDomainValidator.isOperationSupportedByManagedBlockStorage(actionType);
    }

    /**
     * Sets the LUN connection list from the DB.
     *
     * @param lun
     *            - The lun we set the connection at.
     */
    private void updateLUNConnectionsInfo(LUNs lun) {
        lun.setLunConnections(storageServerConnectionDao.getAllForLun(lun.getLUNId()));
    }

    protected boolean isDiskPassPciAndIdeLimit() {
        List<VmNic> vmInterfaces = vmNicDao.getAllForVm(getVmId());
        List<DiskVmElement> diskVmElements = diskVmElementDao.getAllForVm(getVmId());

        diskVmElements.add(getDiskVmElement());

        return validate(VmValidator.checkPciAndIdeLimit(getVm().getOs(),
                getVm().getCompatibilityVersion(),
                getVm().getNumOfMonitors(),
                vmInterfaces,
                diskVmElements,
                isVirtioScsiControllerAttached(getVmId()),
                hasWatchdog(getVmId()),
                isBalloonEnabled(getVmId()),
                isSoundDeviceEnabled(getVmId())));
    }

    protected boolean isVirtioScsiControllerAttached(Guid vmId) {
        return getVmDeviceUtils().hasVirtioScsiController(vmId);
    }

    protected boolean isBalloonEnabled(Guid vmId) {
        return getVmDeviceUtils().hasMemoryBalloon(vmId);
    }

    protected boolean isSoundDeviceEnabled(Guid vmId) {
        return getVmDeviceUtils().hasSoundDevice(vmId);
    }

    protected boolean hasWatchdog(Guid vmId) {
        return getVmDeviceUtils().hasWatchdog(vmId);
    }

    /** Updates the VM's disks from the database */
    protected void updateDisksFromDb() {
        vmHandler.updateDisksFromDb(getVm());
    }

    protected boolean isVolumeFormatSupportedForShareable(VolumeFormat volumeFormat) {
        return volumeFormat == VolumeFormat.RAW;
    }

    protected boolean isVmInUpPausedDownStatus() {
        if (getVm().getStatus() != VMStatus.Up && getVm().getStatus() != VMStatus.Down
                && getVm().getStatus() != VMStatus.Paused) {
            return failVmStatusIllegal();
        }
        return true;
    }

    protected boolean isDiskExistAndAttachedToVm(Disk disk) {
        DiskValidator diskValidator = getDiskValidator(disk);
        return validate(diskValidator.isDiskExists()) && validate(diskValidator.isDiskAttachedToVm(getVm()));
    }

    protected boolean checkOperationAllowedOnDiskContentType(Disk disk) {
        return validate(new DiskOperationsValidator(disk).isOperationAllowedOnDisk(getActionType()));
    }

    public String getDiskAlias() {
        return getParameters().getDiskInfo().getDiskAlias();
    }

    protected boolean validateDiskVmData() {
        if (getDiskVmElement() == null || getDiskVmElement().getId() == null ||
                !Objects.equals(getDiskVmElement().getId().getVmId(), getVmId())) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_DISK_VM_DATA_MISSING);
        }
        return true;
    }

    protected boolean validateQuota() {
        if (!getParameters().getDiskInfo().getDiskStorageType().isInternal()) {
            return true;
        }

        return validateQuota(((DiskImage) getParameters().getDiskInfo()).getQuotaId());
    }

    protected DiskVmElement getDiskVmElement() {
        return getParameters().getDiskVmElement();
    }

    @Override
    public Map<String, String> getJobMessageProperties() {
        if (jobProperties == null) {
            jobProperties = super.getJobMessageProperties();
            jobProperties.put("diskalias", getDiskAlias());
        }
        return jobProperties;
    }

    protected DiskValidator getDiskValidator(Disk disk) {
        return new DiskValidator(disk);
    }

    protected DiskVmElementValidator getDiskVmElementValidator(Disk disk, DiskVmElement diskVmElement) {
        return new DiskVmElementValidator(disk, diskVmElement);
    }

    protected boolean isVmNotInPreviewSnapshot() {
        return getVmId() != null &&
                validate(snapshotsValidator.vmNotDuringSnapshot(getVmId())) &&
                validate(snapshotsValidator.vmNotInPreview(getVmId()));
    }

    public CinderBroker getCinderBroker() {
        if (cinderBroker == null) {
            cinderBroker = new CinderBroker(getStorageDomainId(), getReturnValue().getExecuteFailedMessages());
        }
        return cinderBroker;
    }

    /**
     * Returns a possibly new PCI address allocated for a disk that is set the specified address and interface
     *
     * @return an address allocated to the given disk
     */
    public String getDiskAddress(final String currentAddress, DiskInterface diskInterface) {
        switch (diskInterface) {
        case VirtIO_SCSI:
        case SPAPR_VSCSI:
            int controllerIndex = ArchStrategyFactory.getStrategy(getVm().getClusterArch())
                    .run(new GetControllerIndices())
                    .returnValue()
                    .get(diskInterface);
            try (EngineLock vmDiskHotPlugEngineLock = lockVmDiskHotPlugWithWait()) {
                switch (diskInterface) {
                case VirtIO_SCSI:
                    var vmDeviceUnitMap = vmInfoBuildUtils.getVmDeviceUnitMapForVirtioScsiDisks(getVm());
                    var vmDeviceUnitMapForController =
                            vmDeviceUnitMapForController(currentAddress, vmDeviceUnitMap, diskInterface);
                    var addressMap = getAddressMapForScsiDisk(currentAddress,
                            vmDeviceUnitMapForController,
                            controllerIndex,
                            false,
                            false);
                    return addressMap.toString();
                case SPAPR_VSCSI:
                    vmDeviceUnitMap = vmInfoBuildUtils.getVmDeviceUnitMapForSpaprScsiDisks(getVm());
                    vmDeviceUnitMapForController =
                            vmDeviceUnitMapForController(currentAddress, vmDeviceUnitMap, diskInterface);
                    addressMap = getAddressMapForScsiDisk(currentAddress,
                            vmDeviceUnitMapForController,
                            controllerIndex,
                            true,
                            true);
                    return addressMap.toString();
                }
            }
        default:
            return currentAddress;
        }
    }

    private Map<VmDevice, Integer> vmDeviceUnitMapForController(String address,
            Map<Integer, Map<VmDevice, Integer>> vmDeviceUnitMap,
            DiskInterface diskInterface) {
        int numOfDisks = getVm().getDiskMap().values().size();
        int controllerId = vmInfoBuildUtils.getControllerForScsiDisk(address, getVm(), diskInterface, numOfDisks);
        if (!vmDeviceUnitMap.containsKey(controllerId)) {
            return new HashMap<>();
        }
        return vmDeviceUnitMap.get(controllerId);
    }

    private Map<String, String> getAddressMapForScsiDisk(String address,
            Map<VmDevice, Integer> vmDeviceUnitMap,
            int controllerIndex,
            boolean reserveFirstAddress,
            boolean reserveForScsiCd) {
        // If address has been already set before, verify its uniqueness;
        // Otherwise, set address according to the next available unit.
        if (StringUtils.isNotBlank(address)) {
            var addressMap = StringMapUtils.string2Map(address);
            int unit = Integer.parseInt(addressMap.get(VdsProperties.Unit));
            if (vmDeviceUnitMap.containsValue(unit)) {
                int availableUnit = vmInfoBuildUtils.getAvailableUnitForScsiDisk(vmDeviceUnitMap,
                        reserveFirstAddress,
                        reserveForScsiCd && controllerIndex == 0);
                addressMap = vmInfoBuildUtils.createAddressForScsiDisk(controllerIndex, availableUnit);
            }
            return addressMap;
        } else {
            int availableUnit = vmInfoBuildUtils.getAvailableUnitForScsiDisk(vmDeviceUnitMap,
                    reserveFirstAddress,
                    reserveForScsiCd && controllerIndex == 0);
            return vmInfoBuildUtils.createAddressForScsiDisk(controllerIndex, availableUnit);
        }
    }

    protected void updateVmDeviceAddress(final String address, final VmDevice vmDevice) {
        if (vmDevice.getAddress().equals(address)) {
            return;
        }
        vmDevice.setAddress(address);
        getCompensationContext().snapshotEntity(vmDevice);
        getCompensationContext().stateChanged();
        vmDeviceDao.update(vmDevice);
    }

    protected EngineLock lockVmDiskHotPlugWithWait() {
        EngineLock vmDiskHotPlugEngineLock = new EngineLock();
        vmDiskHotPlugEngineLock.setExclusiveLocks(Collections.singletonMap(getVmId().toString(),
                LockMessagesMatchUtil.makeLockingPair(LockingGroup.VM_DISK_HOT_PLUG,
                        EngineMessage.ACTION_TYPE_FAILED_OBJECT_LOCKED)));
        lockManager.acquireLockWait(vmDiskHotPlugEngineLock);
        return vmDiskHotPlugEngineLock;
    }

    @Override
    protected boolean shouldUpdateHostedEngineOvf() {
        return true;
    }

    protected String getDeviceAliasForDisk(Disk disk) {
        return String.format("%s%s", DomainXmlUtils.USER_ALIAS_PREFIX, disk.getId());
    }
}
