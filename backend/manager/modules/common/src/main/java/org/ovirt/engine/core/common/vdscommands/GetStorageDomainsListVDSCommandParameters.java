package org.ovirt.engine.core.common.vdscommands;

import org.ovirt.engine.core.compat.*;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlType;

@XmlAccessorType(XmlAccessType.NONE)
@XmlType(name = "GetStorageDomainsListVDSCommandParameters")
public class GetStorageDomainsListVDSCommandParameters extends IrsBaseVDSCommandParameters {
    public GetStorageDomainsListVDSCommandParameters(Guid storagePoolId) {
        super(storagePoolId);
    }

    public GetStorageDomainsListVDSCommandParameters() {
    }
}
