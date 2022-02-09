package org.ovirt.engine.ui.uicommonweb.models;

import org.ovirt.engine.core.common.migration.MigrationPolicy;
import org.ovirt.engine.core.common.migration.ParallelMigrationsType;

public interface ModelWithMigrationsOptions {

    ListModel<Boolean> getAutoConverge();

    ListModel<Boolean> getMigrateCompressed();

    ListModel<Boolean> getMigrateEncrypted();

    ListModel<MigrationPolicy> getMigrationPolicies();

    ListModel<ParallelMigrationsType> getParallelMigrationsType();

    EntityModel<Integer> getCustomParallelMigrations();
}
