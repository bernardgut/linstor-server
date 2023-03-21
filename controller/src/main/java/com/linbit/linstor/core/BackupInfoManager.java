package com.linbit.linstor.core;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.annotation.SystemContext;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.core.apicallhandler.controller.backup.CtrlBackupL2LDstApiCallHandler;
import com.linbit.linstor.core.apicallhandler.controller.backup.CtrlBackupL2LSrcApiCallHandler;
import com.linbit.linstor.core.apicallhandler.controller.backup.CtrlBackupL2LSrcApiCallHandler.BackupShippingData;
import com.linbit.linstor.core.identifier.NodeName;
import com.linbit.linstor.core.identifier.RemoteName;
import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.core.identifier.SnapshotName;
import com.linbit.linstor.core.objects.Node;
import com.linbit.linstor.core.objects.ResourceDefinition;
import com.linbit.linstor.core.objects.Snapshot;
import com.linbit.linstor.core.objects.SnapshotDefinition;
import com.linbit.linstor.core.objects.remotes.AbsRemote;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.transaction.TransactionObjectFactory;
import com.linbit.utils.BidirectionalMultiMap;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

@Singleton
public class BackupInfoManager
{
    private final Map<ResourceDefinition, String> restoreMap;
    private final Map<NodeName, Map<SnapshotDefinition.Key, AbortInfo>> abortCreateMap;
    private final Map<ResourceName, Set<Snapshot>> abortRestoreMap;
    private final Map<Snapshot, Snapshot> backupsToDownload;
    private final Object restoreSyncObj = new Object();
    // Map<LinstorRemoteName, Map<StltRemoteName, Data>>
    private final Map<RemoteName, Map<RemoteName, CtrlBackupL2LSrcApiCallHandler.BackupShippingData>> l2lSrcData;
    private final Map<Snapshot, CtrlBackupL2LDstApiCallHandler.BackupShippingData> l2lDstData;
    private final BidirectionalMultiMap<Node, QueueItem> uploadQueues;
    /*
     * set of queueItems whose prevSnap is missing the info which node it was shipped by
     * since QueueItems are comparedTo using an internal (creation-) timestamp, this set can be viewed as a queue of
     * uploadQueues with no node associated
     */
    // uses uploadQueues as sync-object
    private final TreeSet<QueueItem> prevNodeUndecidedQueue;
    private final AccessContext sysCtx;
    private final ErrorReporter errorReporter;

    @Inject
    public BackupInfoManager(
        TransactionObjectFactory transObjFactoryRef,
        @SystemContext AccessContext sysCtxRef,
        ErrorReporter errorReporterRef
    )
    {
        sysCtx = sysCtxRef;
        errorReporter = errorReporterRef;
        restoreMap = transObjFactoryRef.createTransactionPrimitiveMap(new HashMap<>(), null);
        abortCreateMap = new HashMap<>();
        abortRestoreMap = new HashMap<>();
        backupsToDownload = new HashMap<>();
        l2lSrcData = new HashMap<>();
        l2lDstData = new HashMap<>();
        uploadQueues = new BidirectionalMultiMap<>();
        prevNodeUndecidedQueue = new TreeSet<>();
    }

    public boolean addAllRestoreEntries(
        ResourceDefinition rscDfn,
        String metaName,
        String rscNameStr,
        List<Snapshot> snaps,
        Map<Snapshot, Snapshot> snapsToDownload
    )
    {
        synchronized (restoreSyncObj)
        {
            boolean newShipping = restoreAddEntry(rscDfn, metaName);
            boolean addedSuccessfully = true;
            if (newShipping)
            {
                for (Snapshot snap : snaps)
                {
                    abortRestoreAddEntry(rscNameStr, snap);
                }
                for (Entry<Snapshot, Snapshot> toDownload : snapsToDownload.entrySet())
                {
                    addedSuccessfully = backupsToDownloadAddEntry(toDownload.getKey(), toDownload.getValue());
                    if (!addedSuccessfully)
                    {
                        break;
                    }
                }
            }
            return newShipping && addedSuccessfully;
        }
    }

    public void removeAllRestoreEntries(ResourceDefinition rscDfn, String rscName, Snapshot snap)
    {
        synchronized (restoreSyncObj)
        {
            restoreRemoveEntry(rscDfn);
            abortRestoreDeleteAllEntries(rscName);
            backupsToDownloadCleanUp(snap);
        }
    }

    /**
     * mark a rscDfn as target of a backup restore. rscDfns in this map should not be modified in any way
     * also add the backup that is the source of the restore, to avoid multiple restores
     * of the same backup at the same time
     */
    private boolean restoreAddEntry(ResourceDefinition rscDfn, String metaName)
    {
        boolean addFlag = !restoreMap.containsKey(rscDfn);
        if (addFlag)
        {
            restoreMap.put(rscDfn, metaName);
        }
        return addFlag;
    }

    /**
     * unmark the rscDfn to signify the backup restore is done and allow other modifications to take place
     * and also free the source backup for the next restore
     */
    private void restoreRemoveEntry(ResourceDefinition rscDfn)
    {
        restoreMap.remove(rscDfn);
    }

    /**
     * check if a rscDfn has been marked as a target of a restore
     */
    public boolean restoreContainsRscDfn(ResourceDefinition rscDfn)
    {
        synchronized (restoreSyncObj)
        {
            return restoreMap.containsKey(rscDfn);
        }
    }

    /**
     * check if a certain backup is currently being restored
     */
    public boolean restoreContainsMetaFile(String metaName)
    {
        synchronized (restoreSyncObj)
        {
            return restoreMap.containsValue(metaName);
        }
    }

    /**
     * abortRestore saves a list of snapshots used in a restore for each rscDfn that need to be
     * taken care of in case of an abort. This method adds a snapshot to that list
     */
    private void abortRestoreAddEntry(String rscNameStr, Snapshot snap)
    {
        try
        {
            ResourceName rscName = new ResourceName(rscNameStr);
            Set<Snapshot> snaps = abortRestoreMap.computeIfAbsent(rscName, k -> new HashSet<>());
            snaps.add(snap);
        }
        catch (InvalidNameException exc)
        {
            throw new ImplementationError(exc);
        }
    }

    /**
     * get a copy of all the restore-related snapshots that need to be aborted of a specific rscDfn
     */
    public Set<Snapshot> abortRestoreGetEntries(String rscNameStr)
    {
        try
        {
            synchronized (restoreSyncObj)
            {
                ResourceName rscName = new ResourceName(rscNameStr);
                Set<Snapshot> ret = abortRestoreMap.get(rscName);
                return ret != null ? new HashSet<>(ret) : null;
            }
        }
        catch (InvalidNameException exc)
        {
            throw new ImplementationError(exc);
        }
    }

    /**
     * remove the rscDfn and all the remaining snapshots in the list, signifying that the restore or abort is done
     */
    private void abortRestoreDeleteAllEntries(String rscNameStr)
    {
        try
        {
            ResourceName rscName = new ResourceName(rscNameStr);
            abortRestoreMap.remove(rscName);
        }
        catch (InvalidNameException exc)
        {
            throw new ImplementationError(exc);
        }
    }

    /**
     * remove a single snapshot from the list associated with the given rscDfn,
     * signifying that this snapshot is completed and no longer needs aborting
     */
    public void abortRestoreDeleteEntry(String rscNameStr, Snapshot snap)
    {
        try
        {
            synchronized (restoreSyncObj)
            {
                ResourceName rscName = new ResourceName(rscNameStr);
                Set<Snapshot> snaps = abortRestoreMap.get(rscName);
                if (snaps != null)
                {
                    snaps.remove(snap);
                }
            }
        }
        catch (InvalidNameException exc)
        {
            throw new ImplementationError(exc);
        }
    }

    /**
     * add all the information needed to cleanly abort a multipart-upload to s3 to a list for easy access when needed
     */
    public void abortCreateAddS3Entry(
        String nodeName,
        String rscName,
        String snapName,
        String backupName,
        String uploadId,
        String remoteName
    )
    {
        // DO NOT just synchronize within getAbortInfo as the following .add(new Abort*Info(..)) should also be in the
        // same synchronized block as the initial get
        synchronized (abortCreateMap)
        {
            getAbortCreateInfo(nodeName, rscName, snapName).abortS3InfoList.add(
                new AbortS3Info(backupName, uploadId, remoteName)
            );
        }
    }

    /**
     * add information about a l2l-shipping to a list to automatically abort it when issues like loss of connection
     * arise
     */
    public void abortCreateAddL2LEntry(NodeName nodeName, SnapshotDefinition.Key key)
    {
        // DO NOT just synchronize within getAbortInfo as the following .add(new Abort*Info(..)) should also be in the
        // same synchronized block as the initial get
        synchronized (abortCreateMap)
        {
            getAbortCreateInfo(nodeName, key).abortL2LInfoList.add(new AbortL2LInfo());
        }
    }

    private AbortInfo getAbortCreateInfo(String nodeName, String rscName, String snapName)
    {
        try
        {
            return getAbortCreateInfo(
                new NodeName(nodeName),
                new SnapshotDefinition.Key(new ResourceName(rscName), new SnapshotName(snapName))
            );
        }
        catch (InvalidNameException exc)
        {
            throw new ImplementationError(exc);
        }
    }

    private AbortInfo getAbortCreateInfo(NodeName nodeName, SnapshotDefinition.Key snapDfnKey)
    {
        Map<SnapshotDefinition.Key, AbortInfo> map = abortCreateMap.computeIfAbsent(nodeName, k -> new HashMap<>());
        return map.computeIfAbsent(snapDfnKey, a -> new AbortInfo());
    }

    /**
     * delete the abort-information given when it is no longer needed
     */
    public void abortCreateDeleteEntries(String nodeName, String rscName, String snapName) throws InvalidNameException
    {
        synchronized (abortCreateMap)
        {
            abortCreateDeleteEntries(
                new NodeName(nodeName),
                new SnapshotDefinition.Key(new ResourceName(rscName), new SnapshotName(snapName))
            );
        }
    }

    /**
     * delete the abort-information given when it is no longer needed
     */
    public void abortCreateDeleteEntries(NodeName nodeName, SnapshotDefinition.Key snapDfnKey)
    {
        synchronized (abortCreateMap)
        {
            Map<SnapshotDefinition.Key, AbortInfo> map = abortCreateMap.get(nodeName);
            if (map != null)
            {
                map.remove(snapDfnKey);
            }
        }
    }

    /**
     * get a copy of the abort-information to use it
     */
    public Map<SnapshotDefinition.Key, AbortInfo> abortCreateGetEntries(NodeName nodeName)
    {
        synchronized (abortCreateMap)
        {
            Map<SnapshotDefinition.Key, AbortInfo> ret = abortCreateMap.get(nodeName);
            return ret != null ? new HashMap<>(ret) : null;
        }
    }

    /**
     * add a pair of snapshots, with the second snapshot being the first snapshot's successor
     * these are used to determine which backups need to be downloaded during a restore
     */
    private boolean backupsToDownloadAddEntry(Snapshot snap, Snapshot successor)
    {
        boolean addFlag;
        addFlag = !backupsToDownload.containsKey(snap);
        if (addFlag)
        {
            backupsToDownload.put(snap, successor);
        }
        return addFlag;
    }

    /**
     * get the successor of the given snapshot and delete the entry
     */
    public Snapshot getNextBackupToDownload(Snapshot snap)
    {
        synchronized (restoreSyncObj)
        {
            return backupsToDownload.remove(snap);
        }
    }

    /**
     * clean up the download-map when the restore is aborted by anything
     */
    private void backupsToDownloadCleanUp(Snapshot snap)
    {
        Snapshot toDelete = backupsToDownload.remove(snap);
        while (toDelete != null)
        {
            toDelete = backupsToDownload.remove(toDelete);
        }
    }

    public void addL2LSrcData(
        RemoteName linstorRemoteName,
        RemoteName stltRemoteName,
        CtrlBackupL2LSrcApiCallHandler.BackupShippingData data
    )
    {
        synchronized (l2lSrcData)
        {
            Map<RemoteName, BackupShippingData> innerMap = l2lSrcData.computeIfAbsent(
                linstorRemoteName,
                ignore -> new HashMap<>()
            );
            innerMap.put(stltRemoteName, data);
        }
    }

    public CtrlBackupL2LSrcApiCallHandler.BackupShippingData getL2LSrcData(
        RemoteName linstorRemoteName,
        RemoteName stltRemoteName
    )
    {
        synchronized (l2lSrcData)
        {
            return l2lSrcData.get(linstorRemoteName).get(stltRemoteName);
        }
    }

    public CtrlBackupL2LSrcApiCallHandler.BackupShippingData removeL2LSrcData(
        RemoteName linstorRemoteName,
        RemoteName stltRemoteName
    )
    {
        BackupShippingData ret;
        synchronized (l2lSrcData)
        {
            Map<RemoteName, BackupShippingData> innerMap = l2lSrcData.get(linstorRemoteName);
            if (innerMap != null)
            {
                ret = innerMap.remove(stltRemoteName);
                if (innerMap.isEmpty())
                {
                    l2lSrcData.remove(linstorRemoteName);
                }
            }
            else
            {
                ret = null;
            }
        }
        return ret;
    }

    public void addL2LDstData(Snapshot snap, CtrlBackupL2LDstApiCallHandler.BackupShippingData data)
    {
        l2lDstData.put(snap, data);
    }

    public CtrlBackupL2LDstApiCallHandler.BackupShippingData getL2LDstData(Snapshot snap)
    {
        return l2lDstData.get(snap);
    }

    public void removeL2LDstData(Snapshot snap)
    {
        l2lDstData.remove(snap);
    }

    /**
     * Add the snapDfn to the queues of all nodes in usableNodes
     * If usableNodes is empty or null, it will be added to the prevNodeUndecidedQueue
     */
    public void addToQueues(
        SnapshotDefinition snapDfn,
        AbsRemote remote,
        @Nullable SnapshotDefinition prevSnapDfn,
        @Nullable String preferredNode,
        @Nullable Set<Node> usableNodes
    )
    {
        synchronized (uploadQueues)
        {
            QueueItem item = new QueueItem(snapDfn, remote, prevSnapDfn, preferredNode);
            if (usableNodes != null && !usableNodes.isEmpty())
            {
                for (Node node : usableNodes)
                {
                    uploadQueues.add(node, item);
                }
            }
            else
            {
                prevNodeUndecidedQueue.add(item);
            }
        }
    }

    public boolean isSnapshotQueued(SnapshotDefinition snapDfn)
    {
        synchronized (uploadQueues)
        {
            return setContainsSnapDfn(snapDfn, prevNodeUndecidedQueue) ||
                setContainsSnapDfn(snapDfn, uploadQueues.valueSet());
        }
    }

    private boolean setContainsSnapDfn(SnapshotDefinition snapDfn, Set<QueueItem> set)
    {
        boolean ret = false;
        for (QueueItem item : set)
        {
            if (item.snapDfn.equals(snapDfn))
            {
                ret = true;
                break;
            }
        }
        return ret;
    }

    /**
     * Return the next snapDfn from the queue of the given node and remove it from all queues it was in (including the
     * given node's)
     */
    public QueueItem getNextFromQueue(AccessContext accCtx, Node node) throws AccessDeniedException
    {
        QueueItem ret = null;
        synchronized (uploadQueues)
        {
            Set<QueueItem> queue = uploadQueues.getByKey(node);
            if (queue != null && !queue.isEmpty())
            {
                Iterator<QueueItem> iterator = queue.iterator();
                while (iterator.hasNext() && ret == null)
                {
                    QueueItem next = iterator.next();
                    boolean isValid = next.prevSnapDfn == null;
                    isValid |= next.prevSnapDfn.isDeleted();
                    isValid |= next.prevSnapDfn.getFlags().isSet(accCtx, SnapshotDefinition.Flags.SHIPPED);
                    if (isValid)
                    {
                        ret = next;
                    }
                }
                if (ret != null)
                {
                    // make sure this snapDfn gets removed from all queues so that the shipping is only started once
                    uploadQueues.removeValue(ret);
                }
            }
        }
        return ret;
    }

    public void deleteFromQueue(SnapshotDefinition snapDfn, AbsRemote remote)
    {
        synchronized (uploadQueues)
        {
            // this works because hashCode & equals only use snapDfn & remote and ignore the prevSnapDfn
            QueueItem toDelete = new QueueItem(snapDfn, remote, null, null);
            uploadQueues.removeValue(toDelete);
            prevNodeUndecidedQueue.remove(toDelete);
        }
    }

    /**
     * Deletes all entries that require the given remote
     */
    public void deleteFromQueue(AbsRemote remote)
    {
        synchronized (uploadQueues)
        {
            for (Entry<Node, Set<QueueItem>> queue : uploadQueues.entrySet())
            {
                Set<QueueItem> itemsToDelete = getItemsByRemote(remote, queue.getValue());
                for (QueueItem toDelete : itemsToDelete)
                {
                    uploadQueues.removeValue(toDelete);
                }
            }
            Set<QueueItem> itemsToDelete = getItemsByRemote(remote, prevNodeUndecidedQueue);
            prevNodeUndecidedQueue.removeAll(itemsToDelete);
        }
    }

    private Set<QueueItem> getItemsByRemote(AbsRemote remote, Set<QueueItem> items)
    {
        Set<QueueItem> ret = new HashSet<>();
        for (QueueItem item : items)
        {
            if (item.remote.equals(remote))
            {
                ret.add(item);
            }
        }
        return ret;
    }

    /**
     * Deletes the queue of this node
     *
     * @return A list of all backups that need to be queued somewhere else because this node was
     * the last queue they were a part of
     */
    public List<QueueItem> deleteFromQueue(Node node)
    {
        synchronized (uploadQueues)
        {
            List<QueueItem> ret = new ArrayList<>();
            // removes the node from all item-lists, and if the list is empty afterwards, deletes the item as well
            Set<QueueItem> itemsToCheck = uploadQueues.removeKey(node);
            if (itemsToCheck != null)
            {
                for (QueueItem item : itemsToCheck)
                {
                    if (!uploadQueues.containsValue(item))
                    {
                        // the backup is not queued anywhere anymore and new nodes need to be chosen for it
                        ret.add(item);
                    }
                }
            }
            return ret;
        }
    }

    /**
     * Deletes ALL queues
     */
    public void deleteAllQueues()
    {
        synchronized (uploadQueues)
        {
            uploadQueues.clear();
            prevNodeUndecidedQueue.clear();
        }
    }

    public QueueItem getItemFromPrevNodeUndecidedQueue(SnapshotDefinition snapDfn, AbsRemote remote)
    {
        synchronized (uploadQueues)
        {
            QueueItem ret = null;
            for (QueueItem item : prevNodeUndecidedQueue)
            {
                // this list CANNOT contain items where prevSnapDfn == null
                if (!item.prevSnapDfn.isDeleted() && item.prevSnapDfn.equals(snapDfn) && item.remote.equals(remote))
                {
                    ret = item;
                    break;
                }
            }
            if (ret != null)
            {
                prevNodeUndecidedQueue.remove(ret);
            }
            return ret;
        }
    }

    public Map<QueueItem, TreeSet<Node>> getFollowUpSnaps(SnapshotDefinition snapDfn, AbsRemote remote)
    {
        synchronized (uploadQueues)
        {
            /*
             * Does not need to consider prevNodeUndecidedQueue since that queue can only contain snaps whose prevSnap
             * has yet to start shipping, whereas the snaps we are looking for have the snap that just finished shipping
             * as their prevSnap
             */
            Map<QueueItem, TreeSet<Node>> ret = new HashMap<>();
            for (Entry<QueueItem, Set<Node>> entry : uploadQueues.entrySetInverted())
            {
                QueueItem item = entry.getKey();
                if (
                    item.remote.equals(remote) && item.prevSnapDfn != null &&
                        !item.prevSnapDfn.isDeleted() && item.prevSnapDfn.equals(snapDfn)
                )
                {
                    ret.put(item, new TreeSet<>(entry.getValue()));
                }
            }
            return ret;
        }
    }

    public class QueueItem implements Comparable<QueueItem>
    {
        public final SnapshotDefinition snapDfn;
        public final AbsRemote remote;
        /** if prevSnapDfn is null, it means a full backup should be made */
        public final @Nullable SnapshotDefinition prevSnapDfn;
        public final @Nullable String preferredNode;

        private QueueItem(
            SnapshotDefinition snapDfnRef,
            AbsRemote remoteRef,
            SnapshotDefinition prevSnapDfnRef,
            String preferredNodeRef
        )
        {
            snapDfn = snapDfnRef;
            remote = remoteRef;
            prevSnapDfn = prevSnapDfnRef;
            preferredNode = preferredNodeRef;
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((remote == null) ? 0 : remote.hashCode());
            result = prime * result + ((snapDfn == null) ? 0 : snapDfn.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj)
        {
            boolean equals = false;
            if (obj instanceof QueueItem)
            {
                if (this == obj)
                {
                    equals = true;
                }
                else
                {
                    QueueItem other = (QueueItem) obj;
                    equals = Objects.equals(remote, other.remote) &&
                        Objects.equals(snapDfn, other.snapDfn);
                }
            }
            return equals;
        }

        @Override
        public int compareTo(QueueItem other)
        {
            int ret = 0;
            try
            {
                String myDateStr = snapDfn.getProps(sysCtx)
                    .getProp(InternalApiConsts.KEY_BACKUP_START_TIMESTAMP, ApiConsts.NAMESPC_BACKUP_SHIPPING);
                String otherDateStr = other.snapDfn.getProps(sysCtx)
                    .getProp(InternalApiConsts.KEY_BACKUP_START_TIMESTAMP, ApiConsts.NAMESPC_BACKUP_SHIPPING);
                /*
                 * To prevent an ImplementationError from possibly completely stopping whatever thread is currently
                 * trying to sort these QueueItems, it is not thrown but instead creates an ErrorReport
                 */
                if (myDateStr == null || myDateStr.isEmpty())
                {
                    if (otherDateStr == null || otherDateStr.isEmpty())
                    {
                        ret = 0;
                        errorReporter.reportError(
                            new ImplementationError(
                                "The snapDfns '" + snapDfn + "' and '" + other.snapDfn +
                                    "' do not have the property BackupShipping/BackupStartTimestamp set."
                            )
                        );
                    }
                    else
                    {
                        ret = -1;
                        errorReporter.reportError(
                            new ImplementationError(
                                "The snapDfns '" + snapDfn +
                                    "' does not have the property BackupShipping/BackupStartTimestamp set."
                            )
                        );
                    }
                }
                else
                {
                    if (otherDateStr == null || otherDateStr.isEmpty())
                    {
                        ret = 1;
                        errorReporter.reportError(
                            new ImplementationError(
                                "The snapDfns '" + other.snapDfn +
                                    "' does not have the property BackupShipping/BackupStartTimestamp set."
                            )
                        );
                    }
                    else
                    {
                        ret = Long.compare(Long.parseLong(myDateStr), Long.parseLong(otherDateStr));
                    }
                }
            }
            catch (AccessDeniedException exc)
            {
                throw new ImplementationError(exc);
            }
            return ret;
        }

    }

    public static class AbortInfo
    {
        public final List<AbortS3Info> abortS3InfoList = new ArrayList<>();
        public final List<AbortL2LInfo> abortL2LInfoList = new ArrayList<>();

        public boolean isEmpty()
        {
            return abortS3InfoList.isEmpty() && abortL2LInfoList.isEmpty();
        }
    }

    public static class AbortS3Info
    {
        public final String backupName;
        public final String uploadId;
        public final String remoteName;

        AbortS3Info(String backupNameRef, String uploadIdRef, String remoteNameRef)
        {
            backupName = backupNameRef;
            uploadId = uploadIdRef;
            remoteName = remoteNameRef;
        }
    }

    public static class AbortL2LInfo
    {
        // no special data needed (for now?)
    }
}
