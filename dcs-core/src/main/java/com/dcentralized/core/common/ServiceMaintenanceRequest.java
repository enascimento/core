/*
 * Copyright (c) 2014-2015 dCentralizedSystems, LLC. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.dcentralized.core.common;

import java.util.EnumSet;

import com.dcentralized.core.services.common.NodeGroupService.NodeGroupState;

/**
 * Maintenance request body used to specify maintenance type
 */
public class ServiceMaintenanceRequest {
    public static final String KIND = Utils.buildKind(ServiceMaintenanceRequest.class);

    public static ServiceMaintenanceRequest create() {
        ServiceMaintenanceRequest r = new ServiceMaintenanceRequest();
        r.kind = KIND;
        return r;
    }

    public enum MaintenanceReason {
        PERIODIC_SCHEDULE, NODE_GROUP_CHANGE, SERVICE_OPTION_TOGGLE
    }

    public EnumSet<MaintenanceReason> reasons = EnumSet.noneOf(MaintenanceReason.class);

    /**
     * Changes to configuration associated with this request
     */
    public ServiceConfigUpdateRequest configUpdate;

    /**
     * Cached node group state associated with this request
     */
    public NodeGroupState nodeGroupState;

    public String kind;
}