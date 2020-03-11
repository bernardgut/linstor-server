package com.linbit.linstor.storage.utils;

import com.linbit.extproc.ExtCmd;
import com.linbit.extproc.ExtCmd.OutputData;
import com.linbit.linstor.storage.StorageException;
import com.linbit.linstor.storage.kinds.RaidLevel;
import com.linbit.linstor.storage.layer.provider.utils.Commands;
import com.linbit.linstor.storage.layer.provider.utils.Commands.RetryHandler;
import com.linbit.linstor.storage.layer.provider.utils.RetryIfDeviceBusy;
import com.linbit.utils.StringUtils;

import static com.linbit.linstor.storage.layer.provider.utils.Commands.genericExecutor;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public class LvmCommands
{
    public static final int LVS_COL_IDENTIFIER = 0;
    public static final int LVS_COL_PATH = 1;
    public static final int LVS_COL_SIZE = 2;
    public static final int LVS_COL_VG = 3;
    public static final int LVS_COL_POOL_LV = 4;
    public static final int LVS_COL_DATA_PERCENT = 5;
    public static final int LVS_COL_ATTRIBUTES = 6;

    public static OutputData lvs(ExtCmd extCmd, Set<String> volumeGroups, String lvmConfig) throws StorageException
    {
        return genericExecutor(
            extCmd,
            StringUtils.concat(
                new String[] {
                    "lvs",
                    "--config", lvmConfig,
                    "-o", "lv_name,lv_path,lv_size,vg_name,pool_lv,data_percent,lv_attr",
                    "--separator", LvmUtils.DELIMITER,
                    "--noheadings",
                    "--units", "k",
                    "--nosuffix"
                },
                volumeGroups
            ),
            "Failed to list lvm volumes",
            "Failed to query 'lvs' info",
            Commands.SKIP_EXIT_CODE_CHECK
        );
    }

    public static OutputData getExtentSize(ExtCmd extCmd, Set<String> volumeGroups, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            StringUtils.concat(
                new String[] {
                    "vgs",
                    "--config", lvmConfig,
                    "-o", "vg_name,vg_extent_size",
                    "--separator", LvmUtils.DELIMITER,
                    "--units", "k",
                    "--noheadings",
                    "--nosuffix"
                },
                volumeGroups
            ),
            "Failed to query lvm extent size",
            "Failed to query extent size of volume group(s) " + volumeGroups,
            Commands.SKIP_EXIT_CODE_CHECK
        );
    }

    public static OutputData createFat(
        ExtCmd extCmd,
        String volumeGroup,
        String vlmId,
        long size,
        String lvmConfig,
        String... additionalParameters
    )
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            StringUtils.concat(
                new String[] {
                    "lvcreate",
                    "--config", lvmConfig,
                    "--size", size + "k",
                    "-n", volumeGroup + "/" + vlmId,
                    "-y" // force, skip "wipe signature question"
                },
                additionalParameters
            ),
            "Failed to create lvm volume",
            "Failed to create new lvm volume '" + vlmId + "' in volume group '" + volumeGroup +
                "' with size " + size + "kb"
        );
    }

    public static OutputData createThinPool(
        ExtCmd extCmd,
        String volumeGroupFull,
        String thinPoolName,
        String lvmConfig,
        String... additionalParameters
    )
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            StringUtils.concat(
                new String[] {
                    "lvcreate",
                    "--config", lvmConfig,
                    "-l", "100%FREE",
                    "-T",
                    "-n", volumeGroupFull + "/" + thinPoolName
                },
                additionalParameters
            ),
            "Failed to create lvm volume",
            "Failed to create new lvm thin pool in volume group '" + volumeGroupFull + "'"
        );
    }

    public static OutputData createThin(
        ExtCmd extCmd,
        String volumeGroup,
        String thinPoolName,
        String vlmId,
        long size,
        String lvmConfig,
        String... additionalParameters
    )
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            StringUtils.concat(
                new String[] {
                    "lvcreate",
                    "--config", lvmConfig,
                    "--virtualsize", size + "k", // -V
                    "--thinpool", thinPoolName,
                    "--name", volumeGroup + "/" + vlmId        // -n
                },
                additionalParameters
            ),
            "Failed to create lvm volume",
            "Failed to create new lvm volume '" + vlmId + "' in volume group '" + volumeGroup +
            "' with size " + size + "kb"
        );
    }

    public static OutputData delete(ExtCmd extCmd, String volumeGroup, String vlmId, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            new String[] {
                "lvremove",
                "--config", lvmConfig,
                "-f", // skip the "are you sure?"
                volumeGroup + File.separator + vlmId
            },
            "Failed to delete lvm volume",
            "Failed to delete lvm volume '" + vlmId + "' from volume group '" + volumeGroup,
            new RetryIfDeviceBusy()
        );
    }

    public static OutputData resize(ExtCmd extCmd, String volumeGroup, String vlmId, long size, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            new String[]
            {
                "lvresize",
                "--config", lvmConfig,
                "--size", size + "k",
                volumeGroup + File.separator + vlmId
            },
            "Failed to resize lvm volume",
            "Failed to resize lvm volume '" + vlmId + "' in volume group '" + volumeGroup + "' to size " + size
        );
    }

    public static OutputData rename(
        ExtCmd extCmd,
        String volumeGroup,
        String vlmCurrentId,
        String vlmNewId,
        String lvmConfig
    )
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            new String[] {
                "lvrename",
                "--config", lvmConfig,
                volumeGroup,
                vlmCurrentId,
                vlmNewId
            },
            "Failed to rename lvm volume from '" + vlmCurrentId + "' to '" + vlmNewId + "'",
            "Failed to rename lvm volume from '" + vlmCurrentId + "' to '" + vlmNewId + "'",
            new RetryHandler()
            {
                @Override
                public boolean retry(OutputData outputData)
                {
                    return false;
                }

                @Override
                public boolean skip(OutputData outData)
                {
                    boolean skip = false;

                    String err = new String(outData.stderrData);
                    if (err.contains("Volume group \"" + volumeGroup + "\" not found"))
                    {
                        // well - resource is gone... with the whole volume-group
                        skip = true;
                    }
                    return skip;
                }
            }
        );
    }

    public static OutputData createSnapshotThin(
        ExtCmd extCmd,
        String volumeGroup,
        String thinPool,
        String identifier,
        String snapshotIdentifier,
        String lvmConfig
    )
        throws StorageException
    {
        String failMsg = "Failed to create snapshot " + snapshotIdentifier + " from " + identifier +
            " within thin volume group " + volumeGroup + File.separator + thinPool;
        return genericExecutor(
            extCmd,
            new String[] {
                "lvcreate",
                "--config", lvmConfig,
                "--snapshot",
                "--name", snapshotIdentifier,
                volumeGroup + File.separator + identifier,
            },
            failMsg,
            failMsg
        );
    }

    public static OutputData restoreFromSnapshot(
        ExtCmd extCmd,
        String sourceLvIdWithSnapName,
        String volumeGroup,
        String targetId,
        String lvmConfig
    )
        throws StorageException
    {
        String failMsg = "Failed to restore snapshot " + sourceLvIdWithSnapName +
            " into new volume " + volumeGroup + File.separator + targetId;
        return genericExecutor(
            extCmd,
            new String[] {
                "lvcreate",
                "--config", lvmConfig,
                "--snapshot",
                "--name", targetId,
                volumeGroup + File.separator + sourceLvIdWithSnapName
            },
            failMsg,
            failMsg
        );
    }

    public static OutputData rollbackToSnapshot(
        ExtCmd extCmd,
        String volumeGroup,
        String sourceResource,
        String lvmConfig
    )
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            new String[]
            {
                "lvconvert",
                "--config", lvmConfig,
                "--merge",
                volumeGroup + File.separator + sourceResource
            },
            "Failed to rollback to snapshot " + volumeGroup + File.separator + sourceResource,
            "Failed to rollback to snapshot " + volumeGroup + File.separator + sourceResource
        );
    }

    public static OutputData getVgTotalSize(ExtCmd extCmd, Set<String> volumeGroups, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            StringUtils.concat(
                new String[]
                {
                    "vgs",
                    "--config", lvmConfig,
                   "-o", "vg_name,vg_size",
                    "--units", "k",
                    "--separator", LvmUtils.DELIMITER,
                    "--noheadings",
                    "--nosuffix"
                },
                volumeGroups
            ),
            "Failed to query total size of volume group(s) " + volumeGroups,
            "Failed to query total size of volume group(s) " + volumeGroups,
            Commands.SKIP_EXIT_CODE_CHECK
        );
    }

    public static OutputData getVgFreeSize(ExtCmd extCmd, Set<String> volumeGroups, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            StringUtils.concat(
                new String[]
                {
                    "vgs",
                    "--config", lvmConfig,
                    "-o", "vg_name,vg_free",
                    "--units", "k",
                    "--separator", LvmUtils.DELIMITER,
                    "--noheadings",
                    "--nosuffix"
                },
                volumeGroups
            ),
            "Failed to query free size of volume group(s) " + volumeGroups,
            "Failed to query free size of volume group(s) " + volumeGroups,
            Commands.SKIP_EXIT_CODE_CHECK
        );
    }

    public static OutputData getVgThinTotalSize(ExtCmd extCmd, Set<String> volumeGroups, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            StringUtils.concat(
                new String[]
                    {
                        "lvs",
                        "--config", lvmConfig,
                        "-o", "lv_name,lv_size",
                        "--units", "k",
                        "--separator", LvmUtils.DELIMITER,
                        "--noheadings",
                        "--nosuffix"
                    },
                    volumeGroups
                ),
            "Failed to query total size of volume group(s) " + volumeGroups,
            "Failed to query total size of volume group(s) " + volumeGroups,
            Commands.SKIP_EXIT_CODE_CHECK
        );
    }

    public static OutputData getVgThinFreeSize(ExtCmd extCmd, Set<String> volumeGroups, String lvmConfig)
        throws StorageException
    {
        return genericExecutor(
            extCmd,
            StringUtils.concat(
                new String[]
                    {
                        "vgs",
                        "--config", lvmConfig,
                        "-o", "lv_name,lv_size,data_percent",
                        "--units", "b", // intentionally not "k" as usual
                        "--separator", LvmUtils.DELIMITER,
                        "--noheadings",
                        "--nosuffix"
                    },
                    volumeGroups
                ),
            "Failed to query free size of volume group(s) " + volumeGroups,
            "Failed to query free size of volume group(s) " + volumeGroups,
            Commands.SKIP_EXIT_CODE_CHECK
        );
    }

    public static OutputData activateVolume(ExtCmd extCmd, String volumeGroup, String targetId, String lvmConfig)
        throws StorageException
    {
        String failMsg = "Failed to activate volume " + volumeGroup + File.separator + targetId;
        return genericExecutor(
            extCmd,
            new String[]
            {
                "lvchange",
                "--config", lvmConfig,
                "-ay",  // activate volume
                "-K",   // these parameters are needed to set a
                // snapshot to active and enabled
                volumeGroup + File.separator + targetId
            },
            failMsg,
            failMsg
        );
    }

    public static OutputData deactivateVolume(ExtCmd extCmd, String volumeGroup, String targetId, String lvmConfig)
        throws StorageException
    {
        String failMsg = "Failed to deactivate volume " + volumeGroup + File.separator + targetId;
        return genericExecutor(
            extCmd,
            new String[]
            {
                "lvchange",
                "--config", lvmConfig,
                "-an",  // deactivate volume
                volumeGroup + File.separator + targetId
            },
            failMsg,
            failMsg
        );
    }

    public static OutputData listExistingVolumeGroups(ExtCmd extCmd, String lvmConfig) throws StorageException
    {
        String failMsg = "Failed to query list of volume groups";
        return genericExecutor(
            extCmd,
            new String[]
            {
                "vgs",
                "--config", lvmConfig,
                "-o", "vg_name",
                "--noheadings"
            },
            failMsg,
            failMsg
        );
    }

    public static OutputData pvCreate(ExtCmd extCmd, String devicePath, String lvmConfig) throws StorageException
    {
        final String failMsg = "Failed to pvcreate on device: " + devicePath;
        return genericExecutor(
            extCmd,
            new String[]
            {
                "pvcreate",
                "--config", lvmConfig,
                devicePath
            },
            failMsg,
            failMsg
        );
    }

    public static OutputData pvRemove(ExtCmd extCmd, Collection<String> devicePaths, String lvmConfig)
        throws StorageException
    {
        // no lvm config for pvremove!
        final String failMsg = "Failed to pvremove on device(s): " + String.join(", ", devicePaths);
        return genericExecutor(
            extCmd,
            StringUtils.concat(
                new String[]
                {
                    "pvremove",
                    "--config", lvmConfig,
                },
                devicePaths
            ),
            failMsg,
            failMsg
        );
    }

    public static OutputData vgCreate(
        ExtCmd extCmd,
        final String vgName,
        final RaidLevel raidLevel,  // ignore for now as we only support JBOD
        final List<String> devicePaths,
        String lvmConfig
    )
        throws StorageException
    {
        // no lvm config for vgcreate!
        final String failMsg = "Failed to vgcreate on device(s): " + String.join(" ", devicePaths);
        return genericExecutor(
            extCmd,
            StringUtils.concat(
                new String[]
                {
                    "vgcreate",
                    "--config", lvmConfig,
                    vgName
                },
                devicePaths),
            failMsg,
            failMsg
        );
    }

    public static OutputData listPhysicalVolumes(ExtCmd extCmdRef, String volumeGroupRef, String lvmConfig)
        throws StorageException
    {
        final String failMsg = "Failed to get physical devices for volume group: " + volumeGroupRef;
        return genericExecutor(
            extCmdRef,
            StringUtils.concat(
                new String[]
                {
                    "pvdisplay",
                    "--columns",
                    "-o",
                    "pv_name",
                    "-S",
                    "vg_name=" + volumeGroupRef,
                    "--noheadings",
                    "--nosuffix"
                }
            ),
            failMsg,
            failMsg
        );
    }


    public static OutputData vgRemove(
        ExtCmd extCmd,
        final String vgName,
        String lvmConfig
    )
        throws StorageException
    {
        // no lvm config for vgremove!
        final String failMsg = "Failed to vgremove on volume group: " + vgName;
        return genericExecutor(
            extCmd,
            new String[]
            {
                "vgremove",
                "--config", lvmConfig,
                vgName
            },
            failMsg,
            failMsg
        );
    }

    private LvmCommands()
    {
    }

}
